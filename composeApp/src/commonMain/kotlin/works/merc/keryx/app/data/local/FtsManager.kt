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
     * Rebuilds the full-text index from the current articles, including removing entries for deleted articles.
     */
    suspend fun rebuildIndex(): Unit = indexWriteMutex.withLock {
        driver.execute(null, "INSERT INTO articles_fts(articles_fts) VALUES('rebuild');", 0)
    }
}
