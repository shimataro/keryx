package works.merc.keryx.app.data.local

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
 */
class FtsManager(private val driver: SqlDriver) {

    /**
     * Serializes the two index writers against each other. Without it, the daily idle
     * [rebuildIndex] can start moments before a user-triggered refresh's [indexMissing] — the idle
     * gate in `main.kt` is a lock-free check of `ActivityCenter`, so a refresh beginning just after
     * it passes is not seen. That overlap is always wasted work (a rebuild subsumes an incremental
     * insert) and, on a corpus whose rebuild holds the write lock longer than `busy_timeout`, the
     * loser throws a raw `SQLiteException` that no caller catches.
     *
     * This is mutual exclusion between *writers only*. Searches are deliberately not serialized
     * here: they rely on `'rebuild'` being a single atomic statement plus `busy_timeout`, so a
     * reader waits rather than erroring and never observes a half-built index.
     */
    private val indexWriteMutex = Mutex()

    /**
     * Startup: create the table on first run, then index any articles not yet in the index. This
     * backfills rows that were never indexed — e.g. articles fetched before the FTS feature existed,
     * or feeds whose refresh returned no new articles. Idempotent and cheap when nothing is missing.
     */
    suspend fun ensureIndexed() {
        createTable() // create on first run (IF NOT EXISTS otherwise)
        indexMissing()
    }

    /**
     * Incrementally indexes any articles not yet in the index, without touching already-indexed rows
     * and without ever wiping the index. Used after feed refresh / sync merge (in place of a full
     * [rebuildIndex]) so a concurrent search never observes an empty/absent index — new articles
     * simply become searchable once inserted. Requires the table to already exist (it always does
     * after startup [ensureIndexed]).
     *
     * Content edits to *existing* rows (e.g. a feed re-publishing edited text, or a sync OR-merge
     * filling in content) are not re-indexed here — that row keeps its old tokens until the next full
     * [rebuildIndex] (the daily healing pass). This is the accepted "temporarily stale"
     * trade-off; the row still matches its previous tokens, so search never regresses to zero hits.
     */
    suspend fun indexMissing(): Unit = indexWriteMutex.withLock {
        driver.execute(
            null,
            """
                INSERT INTO articles_fts(rowid, title, search_text)
                SELECT a.rowid, a.title, a.search_text FROM articles a
                WHERE a.rowid NOT IN (SELECT id FROM articles_fts_docsize);
            """.trimIndent(),
            0,
        )
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
     * Rebuilds the whole index from `articles` (the `'rebuild'` command deletes all index content and
     * repopulates it in a single atomic statement — a concurrent reader never sees a half-built index,
     * and with `busy_timeout` set it waits rather than erroring). Used only for the rare healing passes
     * (the daily idle pass); the table is assumed to already exist (created once at startup
     * by [ensureIndexed], and never dropped from the live DB). Also sweeps stale entries left by
     * cache-cleanup article deletions.
     */
    suspend fun rebuildIndex(): Unit = indexWriteMutex.withLock {
        driver.execute(null, "INSERT INTO articles_fts(articles_fts) VALUES('rebuild');", 0)
    }
}
