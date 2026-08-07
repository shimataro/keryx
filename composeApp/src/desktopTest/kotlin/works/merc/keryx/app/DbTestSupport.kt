package works.merc.keryx.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.KeryxDatabase
import java.io.File

/**
 * A transparent [SqlDriver] delegate that counts how often an article-list query
 * (`watchAll` / `watchStarred` / `watchByFeed` / `watchByTag` / `watchByFolder`) is executed, so a
 * test can assert that a purely display-level change (sort, unread-only, selection) does not
 * re-execute it.
 *
 * Identified by projecting `is_starred` while ordering by `published_at DESC`. The ordering
 * separates these five from the by-id fetches (`getById` / `getListRowsByIds`, which have no
 * ORDER BY) and from the FTS search (which orders by rank); the projected column separates them
 * from the unread-count aggregates, which select `ft.tag_id` / `f.folder_id` and must still re-run
 * on a read-state change. `softDeleteExpired` matches both conditions but goes through `execute`,
 * not `executeQuery`, so it never reaches this counter.
 *
 * Keep this in step with the list queries' projection in `articles.sq`: the previous sentinel used
 * `thumbnail_url`, and when that column was dropped from the projection the counter silently stayed
 * at zero, which would have made every "must not re-query" assertion pass vacuously.
 */
class CountingSqlDriver(private val delegate: SqlDriver) : SqlDriver {
    var listQueryExecutions = 0
        private set

    /**
     * How many `UPDATE feeds` statements were executed. The article-list query is registered against
     * `feeds` as well as `articles` (it joins them), so every feeds write re-runs it — which is why
     * a refresh that changes nothing must not write.
     */
    var feedUpdates = 0
        private set

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (app.cash.sqldelight.db.SqlCursor) -> app.cash.sqldelight.db.QueryResult<R>,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): app.cash.sqldelight.db.QueryResult<R> {
        if (sql.contains("is_starred") && sql.contains("published_at DESC")) listQueryExecutions++
        return delegate.executeQuery(identifier, sql, mapper, parameters, binders)
    }

    /**
     * Executes a SQL statement using the delegated driver.
     */
    override fun execute(
        identifier: Int?,
        sql: String,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): app.cash.sqldelight.db.QueryResult<Long> {
        if (sql.startsWith("UPDATE feeds")) feedUpdates++
        return delegate.execute(identifier, sql, parameters, binders)
    }

    /**
 * Starts a new database transaction.
 *
 * @return The new database transaction.
 */
override fun newTransaction() = delegate.newTransaction()
    override fun currentTransaction() = delegate.currentTransaction()
    /**
         * Registers a listener for changes to the specified query keys.
         *
         * @param queryKeys The query keys whose changes trigger the listener.
         * @param listener The listener to register.
         */
        override fun addListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) =
        delegate.addListener(queryKeys = queryKeys, listener = listener)
    /**
         * Removes a listener from the specified query keys.
         *
         * @param queryKeys The query keys associated with the listener.
         * @param listener The listener to remove.
         */
        override fun removeListener(vararg queryKeys: String, listener: app.cash.sqldelight.Query.Listener) =
        delegate.removeListener(queryKeys = queryKeys, listener = listener)
    /**
 * Notifies registered listeners for the specified query keys.
 *
 * @param queryKeys The query keys associated with the changed data.
 */
override fun notifyListeners(vararg queryKeys: String) = delegate.notifyListeners(queryKeys = queryKeys)
    override fun close() = delegate.close()
}

/** An in-memory KeryxDatabase for tests, with the schema applied. */
fun inMemoryDb(): Pair<SqlDriver, KeryxDatabase> {
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
    KeryxDatabase.Schema.create(driver)
    driver.execute(null, "PRAGMA foreign_keys=ON;", 0)
    return driver to KeryxDatabase(driver)
}

/** A file-backed KeryxDatabase (needed for ATTACH DATABASE merge tests). */
fun fileDb(): Triple<File, SqlDriver, KeryxDatabase> {
    val file = File.createTempFile("keryx-test-", ".db").apply { deleteOnExit() }
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
    KeryxDatabase.Schema.create(driver)
    return Triple(file, driver, KeryxDatabase(driver))
}

/**
 * Creates an FtsManager for the supplied SQL driver.
 *
 * @return The configured FtsManager.
 */
fun ftsManager(driver: SqlDriver): FtsManager = FtsManager(driver)

/**
     * Creates an [FtsManager] with its search index created and backfilled.
     *
     * @param driver The SQL driver used by the manager.
     * @return An [FtsManager] with an initialized search index.
     */
fun ftsManagerIndexed(driver: SqlDriver): FtsManager =
    FtsManager(driver).also { manager -> runBlocking { manager.ensureIndexed() } }

/**
 * Inserts a feed (satisfies the articles → feeds FK). If [folderId] is non-null, callers must
 * have already inserted that folder (e.g. via [insertFolder]) — `upsert` itself never touches
 * `folder_id` (see `feeds.sq`), so this calls `updateFolder` separately after the `upsert`.
 */
fun KeryxDatabase.insertFeed(
    id: String,
    now: Long = 0L,
    url: String = "https://feed/$id",
    deletedAt: Long? = null,
    folderId: String? = null,
    sortOrder: Long = 0L,
) {
    feedsQueries.upsert(
        id = id, url = url, site_url = null, title = "Feed $id", description = null,
        favicon_url = null, etag = null, last_modified = null, error_count = 0, last_error = null,
        custom_title = null, deleted_at = deletedAt, updated_at = now, created_at = now,
        sort_order = sortOrder,
    )
    if (folderId != null) {
        feedsQueries.updateFolder(folderId, now, now, id)
    }
    // A deleted fixture carries its subscription-state timestamp (deleted_updated_at), matching
    // what unsubscribeFeed does, so merge tests resolve deleted_at by that field.
    if (deletedAt != null) {
        feedsQueries.softDelete(deletedAt, now, deletedAt, id)
    }
}

/** Inserts a folder. */
fun KeryxDatabase.insertFolder(id: String, name: String, now: Long = 0L, deletedAt: Long? = null, sortOrder: Long = 0L) {
    foldersQueries.upsert(
        id = id, name = name, sort_order = sortOrder,
        deleted_at = deletedAt, updated_at = now, created_at = now,
    )
}

/** Inserts a tag. */
fun KeryxDatabase.insertTag(id: String, name: String, now: Long = 0L, deletedAt: Long? = null, sortOrder: Long = 0L) {
    tagsQueries.upsert(
        id = id, name = name, color = null, sort_order = sortOrder,
        deleted_at = deletedAt, updated_at = now, created_at = now,
    )
}

/** Attaches (or updates) a feed-tag link. */
fun KeryxDatabase.insertFeedTag(feedId: String, tagId: String, now: Long = 0L, deletedAt: Long? = null) {
    feed_tagsQueries.upsert(feed_id = feedId, tag_id = tagId, deleted_at = deletedAt, updated_at = now)
}

/** Sets a global_settings key. */
fun KeryxDatabase.insertGlobalSetting(key: String, value: String, now: Long = 0L) {
    global_settingsQueries.upsert(key = key, value_ = value, updated_at = now)
}

/**
 * Sets an article's soft-deletion timestamps for test fixtures.
 *
 * Passing `null` for `deletedAt` clears the deletion state. When omitted, `deletedUpdatedAt`
 * uses the same value as `deletedAt`.
 *
 * @param id The article identifier.
 * @param deletedAt The soft-deletion timestamp, or `null` to clear it.
 * @param deletedUpdatedAt The timestamp for the deletion-state update.
 */
fun SqlDriver.stampArticleDeleted(id: String, deletedAt: Long?, deletedUpdatedAt: Long? = deletedAt) {
    execute(null, "UPDATE articles SET deleted_at = ?, deleted_updated_at = ? WHERE id = ?", 3) {
        bindLong(0, deletedAt)
        bindLong(1, deletedUpdatedAt)
        bindString(2, id)
    }
}
