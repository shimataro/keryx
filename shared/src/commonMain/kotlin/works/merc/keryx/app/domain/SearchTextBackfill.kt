package works.merc.keryx.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.HtmlText
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.SEARCH_TEXT_BACKFILL_BATCH_SIZE
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.KeryxDatabase


/**
 * Recomputes `articles.search_text` for the rows listed in `ks_search_pending` — the articles whose
 * body a sync merge filled in or changed (a newly merged article starts with `''`), and those the
 * schema-3 migration found still holding raw HTML. See "FTS5 Handling" in
 * `docs/sync-architecture.md`.
 *
 * Each batch reads up to [batchSize] pending rows with their body, converts the HTML **outside any
 * transaction**, then writes the texts and removes the rows from `ks_search_pending` in one
 * transaction — but only for a row whose `content` / `summary` are still the ones its text was
 * computed from. A row whose body changed in between (a concurrent feed refresh) stays pending and
 * is recomputed from its new body by a later pass, so a stale text is never written over a newer
 * body. A conversion that throws writes `''` (logged) rather than blocking the rest.
 *
 * The pending table is committed state, so a pass that is cancelled or fails resumes from where it
 * stopped at the next call. [FtsManager.indexMissing] skips pending rows, so an article is indexed
 * only once its text has been computed — [recomputePendingAndIndex] indexes them afterwards.
 */
class SearchTextBackfill(
    private val db: KeryxDatabase,
    private val ftsManager: FtsManager,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
    private val batchSize: Long = SEARCH_TEXT_BACKFILL_BATCH_SIZE,
    private val toPlainText: (String) -> String = HtmlText::toPlainText,
) {
    init {
        require(batchSize > 0) { "batchSize must be positive" }
    }

    /**
     * Serializes passes: the startup pass and a post-merge pass would otherwise convert the same
     * rows twice. Correctness does not depend on it (every write is guarded on the body).
     */
    private val mutex = Mutex()

    private val queries get() = db.sync_bookkeepingQueries

    /**
     * Recomputes every pending row. Each pass walks the pending rows once in `rowid` order, so it
     * always ends; another pass follows only when a row's body changed under the previous one, up to
     * [MAX_PASSES] — a row still changing after that stays pending for the next call.
     *
     * @return how many rows had their `search_text` written and left `ks_search_pending`.
     * @throws CancellationException when cancelled; batches already committed stay done.
     */
    suspend fun recomputePending(): Int = mutex.withLock {
        withContext(dispatcher) {
            var done = 0
            for (pass in 1..MAX_PASSES) {
                val outcome = runPass()
                done += outcome.done
                if (!outcome.bodyChanged) break
            }
            done
        }
    }

    /**
     * [recomputePending], then — when it wrote anything and the index exists —
     * [FtsManager.indexMissing], so the rows it cleared are indexed with their computed text. A
     * table created later is filled by its creator's own [FtsManager.indexMissing].
     */
    suspend fun recomputePendingAndIndex() {
        val done = recomputePending()
        if (done > 0 && ftsManager.exists()) ftsManager.indexMissing()
    }

    private class PassOutcome(val done: Int, val bodyChanged: Boolean)

    private class Recomputed(val rowid: Long, val articleExists: Boolean, val content: String?, val summary: String?, val text: String)

    private suspend fun runPass(): PassOutcome {
        var after = 0L
        var done = 0
        var bodyChanged = false
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = queries.searchPendingBatch(after, batchSize).executeAsList()
            if (batch.isEmpty()) break
            after = batch.last().pending_rowid
            // Off the DB lock: no transaction is open while the HTML is converted.
            val recomputed = batch.map { row ->
                Recomputed(
                    rowid = row.pending_rowid,
                    articleExists = row.article_id != null,
                    content = row.content,
                    summary = row.summary,
                    text = if (row.article_id == null) "" else plainText(row.pending_rowid, row.content ?: row.summary),
                )
            }
            currentCoroutineContext().ensureActive()
            val written = db.transactionWithResult {
                var count = 0
                for (r in recomputed) {
                    if (!r.articleExists) {
                        queries.removeSearchPendingOrphan(r.rowid)
                        continue
                    }
                    // Both statements are guarded on the body read above, inside one transaction, so
                    // they hold or fail together.
                    queries.setSearchTextIfBodyUnchanged(r.text, r.rowid, r.content, r.summary)
                    if (queries.removeSearchPendingIfBodyUnchanged(r.rowid, r.content, r.summary).value > 0) count++
                }
                count
            }
            done += written
            if (written < recomputed.count { it.articleExists }) bodyChanged = true
            if (batch.size < batchSize) break
        }
        return PassOutcome(done, bodyChanged)
    }

    private fun plainText(rowid: Long, html: String?): String {
        if (html == null) return ""
        return try {
            toPlainText(html)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // One unconvertible body must not keep every other row (and itself) unindexed forever.
            Log.warn(TAG, "search_text conversion failed for article rowid $rowid; indexing it without body text", e)
            ""
        }
    }

    private companion object {
        const val TAG = "SearchTextBackfill"

        /** Passes per call; only a body changing under a pass leads to another. */
        const val MAX_PASSES = 3
    }
}
