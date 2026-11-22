package works.merc.keryx.app.data.local

import app.cash.sqldelight.TransacterImpl
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import works.merc.keryx.app.core.HtmlText
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.runCatchingUnlessCancelled

/**
 * Manages the `articles_fts` FTS5 virtual table with raw SQL, deliberately
 * *outside* the SQLDelight-managed schema, so it is never part of a `.sq` file.
 * The live table is never dropped: the sync flow excludes it from the uploaded
 * file by dropping it on a throwaway [DatabaseSnapshot] copy, not on the live DB.
 *
 * External-content FTS5 (`content='articles'`) keeps only the index; the text is
 * read from `articles.search_text` via rowid. New rows are indexed incrementally
 * ([indexMissing], after feed refresh / sync merge); the whole index is only
 * rebuilt in the rare healing passes ([rebuildIndex], daily idle).
 *
 * `articles.search_text` is device-local and never synced: an article whose body a sync merge
 * filled in or changed is listed in `ks_search_pending` (a newly merged one starts with `''`), and
 * [indexMissing] recomputes those rows' `search_text` before it indexes anything.
 *
 * @param beforePendingWrite Test seam: runs after a batch of `ks_search_pending` rows has been read
 * and converted, before that batch's write transaction — so a test can force a concurrent write
 * into exactly that gap.
 * @param toPlainText The HTML → plain-text conversion behind `search_text`; a test seam so a
 * conversion failure can be forced.
 * @param inlineBodyMaxBytes Longest body column, in bytes, read inline with its pending batch
 * ([INLINE_BODY_MAX_BYTES] in production); a test seam so the chunked path can be reached with a
 * small body.
 */
class FtsManager internal constructor(
    private val driver: SqlDriver,
    private val beforePendingWrite: suspend () -> Unit,
    private val toPlainText: (String) -> String,
    private val inlineBodyMaxBytes: Long = INLINE_BODY_MAX_BYTES,
) {
    constructor(driver: SqlDriver) : this(driver, beforePendingWrite = {}, toPlainText = HtmlText::toPlainText)

    /**
     * Opens SQLDelight transactions on [driver]. A raw `BEGIN` would not do: the JVM
     * `JdbcSqliteDriver` uses a fresh connection per statement outside a transaction.
     */
    private val transacter = object : TransacterImpl(driver) {}

    /**
     * Serializes the two index writers against each other. Without it, the daily idle
     * [rebuildIndex] can start moments before a user-triggered refresh's [indexMissing] — the idle
     * gate in `main.kt` is a lock-free check of `ActivityCenter`, so a refresh beginning just after
     * it passes is not seen. That overlap is always wasted work (a rebuild subsumes an incremental
     * insert) and, on a corpus whose rebuild holds the write lock longer than `busy_timeout`, the
     * loser throws a raw `SQLiteException` that no caller catches.
     *
     * This is mutual exclusion between *writers only*. Searches are deliberately not serialized
     * here: they rely on the rebuild being one atomic transaction plus `busy_timeout`, so a
     * reader waits rather than erroring and never observes a half-built index.
     */
    private val indexWriteMutex = Mutex()

    /**
     * Startup: create the table on first run, then index any articles not yet in the index. This
     * backfills rows that were never indexed — e.g. articles fetched before the FTS feature existed,
     * or feeds whose refresh returned no new articles. Idempotent and cheap when nothing is missing
     * — [indexMissing]'s scan still runs every call, though, which is fine for a call site that runs
     * once per app launch (desktop's `main.kt`). For a call site that instead runs on every process
     * start — including a background wakeup with no new articles to backfill — see
     * [ensureIndexedIfTableAbsent].
     */
    suspend fun ensureIndexed() {
        createTable() // create on first run (IF NOT EXISTS otherwise)
        indexMissing()
    }

    /**
     * Cheaper counterpart to [ensureIndexed] for a call site that runs on **every process start**
     * rather than once per app launch — Android's `KeryxApplication.onCreate`, which also runs when
     * `WorkManager` wakes the process to run `FeedRefreshWorker` (up to ~96 times/day at the
     * platform's 15-minute minimum interval). [exists] is a single `sqlite_master` lookup, so once
     * the table has been created (the very first launch after install, or after a sync reset), every
     * later call is a cheap no-op instead of [indexMissing]'s `O(articles)` scan.
     *
     * This does not weaken indexing: backfill of new articles keeps happening through the normal
     * hot-path calls to [indexMissing] (feed refresh, sync merge) and the daily [rebuildIndex] heal —
     * this function's only job is guaranteeing the table exists and is backfilled *once* per
     * install, not re-scanning it on every wakeup that finds it already does.
     */
    suspend fun ensureIndexedIfTableAbsent() {
        if (exists()) return
        createTable()
        indexMissing()
    }

    /**
     * Incrementally indexes any articles not yet in the index, without touching already-indexed rows
     * and without ever wiping the index. Used after feed refresh / sync merge (in place of a full
     * [rebuildIndex]) so a concurrent search never observes an empty/absent index — new articles
     * simply become searchable once inserted. Requires the table to already exist (it always does
     * after startup [ensureIndexed]).
     *
     * First, still under the writer mutex, it recomputes `search_text` for the rows listed in
     * `ks_search_pending` ([recomputePendingSearchText]); a row still listed afterwards (its body
     * changed while it was being converted) is not indexed by this call, so an article is never
     * indexed with the `''` a merge gave it. The pending list is committed state, so work an earlier
     * call left undone — interrupted, or skipped for a concurrent change — is picked up by the next.
     *
     * Content edits to *existing* rows (e.g. a feed re-publishing edited text, or a sync merge
     * changing a body — whose `search_text` is recomputed here) are not re-indexed — that row keeps
     * its old tokens until the next full [rebuildIndex] (the daily healing pass). This is the
     * accepted "temporarily stale" trade-off; the row still matches its previous tokens, so search
     * never regresses to zero hits.
     */
    suspend fun indexMissing(): Unit = indexWriteMutex.withLock {
        recomputePendingSearchText()
        driver.execute(
            null,
            """
                INSERT INTO articles_fts(rowid, title, search_text)
                SELECT a.rowid, a.title, a.search_text FROM articles a
                WHERE a.rowid NOT IN (SELECT id FROM articles_fts_docsize)
                  AND a.rowid NOT IN (SELECT rowid FROM ks_search_pending);
            """.trimIndent(),
            0,
        )
    }

    /**
     * One `ks_search_pending` row as read, with its article's body (`null` columns when it has none).
     * [content] and [summary] are the columns' stored bytes (`CAST(... AS BLOB)`), kept as read so the
     * conditional update compares exactly what was converted — whatever the text holds (a NUL, invalid
     * UTF-8) and however the driver would decode it.
     */
    private class PendingRow(val rowid: Long, val articleExists: Boolean, val content: ByteArray?, val summary: ByteArray?)

    /**
     * Recomputes `articles.search_text` (`HtmlText.toPlainText` of `content`, else `summary`, else
     * `''`) for every row in `ks_search_pending`, in batches of [PENDING_BATCH_SIZE], in one pass in
     * rowid order — a row left pending is not revisited by the same call, so this always ends.
     *
     * Each batch is read outside any transaction, converted, then written in one transaction whose
     * statements are all writes (no read inside it has to upgrade to a write lock — see "A concurrent
     * write can fail a read-then-write transaction" in `docs/known-issues.md`). A row's update is
     * conditional on `content` and `summary` still holding the bytes that were read (compared as
     * `CAST(... AS BLOB)`, so a NUL or invalid UTF-8 in the body compares exactly), and the row leaves
     * `ks_search_pending` only when that update matched: a body another writer (a refresh, a merge)
     * changed after the read stays pending, so neither a newer body nor a merge's re-registration is
     * ever discarded. A row whose conversion throws gets `''` (logged) rather than holding up the rest.
     * A pending row whose article no longer exists is just removed from the list.
     *
     * `search_text` is not one of the columns the capture trigger watches, so this records nothing
     * in `sync_outbox`.
     */
    private suspend fun recomputePendingSearchText() {
        var after = Long.MIN_VALUE
        while (true) {
            val batch = readPendingBatch(after)
            if (batch.isEmpty()) return
            after = batch.last().rowid
            val texts = batch.map { row -> if (row.articleExists) plainTextOf(row) else null }
            beforePendingWrite()
            transacter.transaction {
                batch.forEachIndexed { index, row ->
                    val text = texts[index]
                    if (text == null) {
                        driver.execute(
                            null,
                            "DELETE FROM ks_search_pending WHERE rowid = ? AND NOT EXISTS (SELECT 1 FROM articles WHERE rowid = ?);",
                            2,
                        ) {
                            bindLong(0, row.rowid)
                            bindLong(1, row.rowid)
                        }
                        return@forEachIndexed
                    }
                    val updated = driver.execute(
                        null,
                        "UPDATE articles SET search_text = ? WHERE rowid = ? " +
                            "AND CAST(content AS BLOB) IS ? AND CAST(summary AS BLOB) IS ?;",
                        4,
                    ) {
                        bindString(0, text)
                        bindLong(1, row.rowid)
                        bindBytes(2, row.content)
                        bindBytes(3, row.summary)
                    }.value
                    if (updated > 0L) {
                        driver.execute(null, "DELETE FROM ks_search_pending WHERE rowid = ?;", 1) {
                            bindLong(0, row.rowid)
                        }
                    }
                }
            }
            if (batch.size < PENDING_BATCH_SIZE) return
        }
    }

    private fun plainTextOf(row: PendingRow): String {
        val html = (row.content ?: row.summary ?: return "").decodeToString()
        return runCatchingUnlessCancelled { toPlainText(html) }.getOrElse { e ->
            Log.warn(TAG, "search_text conversion failed for article rowid ${row.rowid}; indexing it without body text", e)
            ""
        }
    }

    /**
     * The batch read behind [readPendingBatch]. Parameters: 1–4 the inline bound in bytes, 5 the rowid
     * to read after, 6 the batch size. Columns: pending rowid, whether the article exists, then per
     * body column (content, summary) its bytes when at most the bound (else `NULL`) and whether it is
     * over the bound.
     *
     * The bound is measured in bytes (`length(CAST(... AS BLOB))`), not with `length()` on the text:
     * for TEXT that counts only the characters before the first NUL, so a multi-MiB body with an
     * early NUL would be read inline and overflow Android's `CursorWindow` on every call.
     */
    internal fun pendingBatchSql(): String = """
        SELECT p.rowid, a.rowid IS NOT NULL,
            CASE WHEN length(CAST(a.content AS BLOB)) <= ? THEN CAST(a.content AS BLOB) END,
            length(CAST(a.content AS BLOB)) > ?,
            CASE WHEN length(CAST(a.summary AS BLOB)) <= ? THEN CAST(a.summary AS BLOB) END,
            length(CAST(a.summary AS BLOB)) > ?
        FROM ks_search_pending p LEFT JOIN articles a ON a.rowid = p.rowid
        WHERE p.rowid > ?
        ORDER BY p.rowid
        LIMIT ?;
    """.trimIndent()

    /**
     * Reads the next [PENDING_BATCH_SIZE] pending rows after rowid [after] ([pendingBatchSql]). A body
     * column longer than [inlineBodyMaxBytes] bytes is read separately, in chunks ([readBytesInChunks]),
     * so no single cursor row outgrows Android's `CursorWindow`.
     */
    private fun readPendingBatch(after: Long): List<PendingRow> {
        class Raw(val rowid: Long, val exists: Boolean, val content: ByteArray?, val contentLong: Boolean, val summary: ByteArray?, val summaryLong: Boolean)
        val raws = mutableListOf<Raw>()
        driver.executeQuery(
            identifier = null,
            sql = pendingBatchSql(),
            mapper = { cursor ->
                while (cursor.next().value) {
                    raws += Raw(
                        rowid = cursor.getLong(0)!!,
                        exists = cursor.getLong(1) == 1L,
                        content = cursor.getBytes(2),
                        contentLong = cursor.getLong(3) == 1L,
                        summary = cursor.getBytes(4),
                        summaryLong = cursor.getLong(5) == 1L,
                    )
                }
                QueryResult.Unit
            },
            parameters = 6,
        ) {
            bindLong(0, inlineBodyMaxBytes)
            bindLong(1, inlineBodyMaxBytes)
            bindLong(2, inlineBodyMaxBytes)
            bindLong(3, inlineBodyMaxBytes)
            bindLong(4, after)
            bindLong(5, PENDING_BATCH_SIZE.toLong())
        }
        return raws.map { raw ->
            PendingRow(
                rowid = raw.rowid,
                articleExists = raw.exists,
                content = if (raw.contentLong) readBytesInChunks(raw.rowid, "content") else raw.content,
                summary = if (raw.summaryLong) readBytesInChunks(raw.rowid, "summary") else raw.summary,
            )
        }
    }

    /**
     * Reads the stored bytes of one long body column of the article at [rowid], in chunks of
     * [inlineBodyMaxBytes] bytes (`substr` over `CAST(... AS BLOB)`, so offsets and lengths count
     * bytes), concatenated; `null` when the column is `NULL` or the row is gone.
     *
     * The column is read as a BLOB rather than as TEXT: TEXT `substr` stops at the first NUL, which
     * would truncate a body whose first NUL lies past the first chunk — the conditional update in
     * [recomputePendingSearchText] compares the stored bytes, so it would never match and the row
     * would stay pending (and unindexed) forever. Byte chunks also keep a multibyte character split
     * across a chunk boundary intact; the bytes are decoded once, after assembly ([plainTextOf]).
     *
     * The chunks are separate statements, so a concurrent write can leave the result mixed from two
     * versions (or `null` when the row went away) — harmless, since the conditional update then
     * matches nothing and the row stays pending.
     */
    private fun readBytesInChunks(rowid: Long, column: String): ByteArray? {
        val chunks = mutableListOf<ByteArray>()
        var start = 1L
        while (true) {
            var chunk: ByteArray? = null
            driver.executeQuery(
                identifier = null,
                // `column` is one of two fixed names chosen by this class, never input.
                sql = "SELECT substr(CAST($column AS BLOB), ?, ?) FROM articles WHERE rowid = ?;",
                mapper = { cursor ->
                    if (cursor.next().value) chunk = cursor.getBytes(0)
                    QueryResult.Unit
                },
                parameters = 3,
            ) {
                bindLong(0, start)
                bindLong(1, inlineBodyMaxBytes)
                bindLong(2, rowid)
            }
            val read = chunk ?: return if (chunks.isEmpty()) null else concat(chunks)
            chunks += read
            if (read.size < inlineBodyMaxBytes) return concat(chunks)
            start += inlineBodyMaxBytes
        }
    }

    private fun concat(chunks: List<ByteArray>): ByteArray {
        val out = ByteArray(chunks.sumOf { it.size })
        var offset = 0
        for (c in chunks) {
            c.copyInto(out, offset)
            offset += c.size
        }
        return out
    }

    fun exists(): Boolean {
        var found = false
        driver.executeQuery(
            identifier = null,
            sql = "SELECT 1 FROM sqlite_master WHERE type='table' AND name='articles_fts';",
            mapper = { cursor ->
                found = cursor.next().value
                QueryResult.Unit
            },
            parameters = 0,
        )
        return found
    }

    fun createTable() {
        driver.execute(
            identifier = null,
            sql = """
                CREATE VIRTUAL TABLE IF NOT EXISTS articles_fts USING fts5(
                    title, search_text,
                    content='articles', content_rowid='rowid',
                    tokenize='trigram'
                );
            """.trimIndent(),
            parameters = 0,
        )
    }

    /**
     * Rebuilds the full-text index from the current articles, including removing entries for deleted articles.
     *
     * `'rebuild'` indexes every article's current `search_text`, so it first recomputes the rows
     * listed in `ks_search_pending` ([recomputePendingSearchText]) — otherwise a merged article whose
     * recompute an earlier call left undone would be indexed with its `''`, and the next
     * [indexMissing] would recompute it but never index it (it is already in the index), leaving its
     * body unsearchable until the next day's rebuild.
     *
     * A row still pending after the recompute (its body changed while it was being converted) is
     * then taken back out of the index, in the same transaction as `'rebuild'`: there its `title` and
     * `search_text` are exactly what `'rebuild'` just indexed, as an external-content `'delete'`
     * requires. The next [indexMissing] recomputes and indexes it. The transaction's first statement
     * is a write, so it takes the write lock from the start (see "A concurrent write can fail a
     * read-then-write transaction" in `docs/known-issues.md`); to a concurrent search the whole
     * transaction is still one atomic change.
     */
    suspend fun rebuildIndex(): Unit = indexWriteMutex.withLock {
        recomputePendingSearchText()
        transacter.transaction {
            driver.execute(null, "INSERT INTO articles_fts(articles_fts) VALUES('rebuild');", 0)
            driver.execute(
                null,
                """
                    INSERT INTO articles_fts(articles_fts, rowid, title, search_text)
                    SELECT 'delete', a.rowid, a.title, a.search_text FROM articles a
                    WHERE a.rowid IN (SELECT rowid FROM ks_search_pending);
                """.trimIndent(),
                0,
            )
        }
    }

    internal companion object {
        private const val TAG = "FtsManager"

        /** Pending rows read, converted and written per transaction by [recomputePendingSearchText]. */
        private const val PENDING_BATCH_SIZE = 200

        /**
         * Longest body column, in bytes, read inline with its batch; a longer one is read in chunks of
         * this many bytes ([readBytesInChunks]). A row with both columns at the limit is 1 MiB, well
         * under Android's 2 MiB `CursorWindow`.
         */
        const val INLINE_BODY_MAX_BYTES = 512L * 1024
    }
}
