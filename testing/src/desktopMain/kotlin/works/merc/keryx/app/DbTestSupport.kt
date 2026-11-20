package works.merc.keryx.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.sqliteConnectionProperties
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

    /**
     * How many single-row `getById` fetches ran — the shape an N+1 shows up as. Matched on the
     * trailing clause, not the projection: SQLDelight expands `SELECT *` into explicit columns, so
     * a `startsWith("SELECT * ...")` sentinel would silently never match. `WHERE id = ?` separates
     * it from `getListRowsByIds` and `aliveFlagsIn`, which both use `WHERE id IN`.
     */
    var articleGetByIdExecutions = 0
        private set

    /**
     * Executes a SQL query while recording article-list and single-article query executions.
     *
     * @param identifier The query identifier.
     * @param sql The SQL statement to execute.
     * @param mapper Maps the query cursor to a result.
     * @param parameters The number of query parameters.
     * @param binders Binds query parameters when provided.
     * @return The query result.
     */
    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (app.cash.sqldelight.db.SqlCursor) -> app.cash.sqldelight.db.QueryResult<R>,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): app.cash.sqldelight.db.QueryResult<R> {
        if (sql.contains("is_starred") && sql.contains("published_at DESC")) listQueryExecutions++
        if (sql.endsWith("FROM articles WHERE id = ?")) articleGetByIdExecutions++
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
    val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, sqliteConnectionProperties())
    KeryxDatabase.Schema.create(driver)
    return driver to KeryxDatabase(driver)
}

/**
 * Creates a file-backed SQLite database, e.g. for merge tests or for a UI-fixture test whose
 * gesture-triggered write must run on a real per-thread connection instead of [inMemoryDb]'s
 * single shared one (see [FeedListDragTest][works.merc.keryx.app.ui.home.FeedListDragTest]'s
 * `dragsAFeedAboveAnotherAndPersistsTheNewOrder` for why).
 *
 * @param foreignKeys Whether to enforce foreign keys. Defaults to `false`, matching production's
 * properties minus foreign_keys: the merge tests seed states that FK enforcement forbids on
 * purpose — an article whose feed is missing, a feed_tag whose tag is missing, a feed pointing at
 * an absent folder — because those are exactly what MergeSql's EXISTS/NOT EXISTS guards exist to
 * skip when a cloud DB written by another device or version carries them. Pass `true` for a test
 * that needs full production-parity connection properties without deliberately violating FKs. The
 * production configuration (foreign_keys included) is covered against a real file DB by
 * SqliteConnectionPropertiesTest.
 * @return A triple containing the temporary database file, SQL driver, and initialized database.
 */
fun fileDb(foreignKeys: Boolean = false): Triple<File, SqlDriver, KeryxDatabase> {
    val file = File.createTempFile("keryx-test-", ".db").apply { deleteOnExit() }
    val properties = sqliteConnectionProperties().apply { if (!foreignKeys) remove("foreign_keys") }
    val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", properties)
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
fun KeryxDatabase.insertTag(id: String, name: String, now: Long = 0L, deletedAt: Long? = null, sortOrder: Long = 0L, color: String? = null) {
    tagsQueries.upsert(
        id = id, name = name, color = color, sort_order = sortOrder,
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

/**
 * Plays another writer — a sync merge, or a create running elsewhere — that inserts a row named
 * [name] into [table] (`folders` or `tags`) under [otherId] right after the first lookup by name on
 * that table this driver runs. The insert carries `MergeSql`'s own guard (nothing is inserted while a
 * row of that name exists), so it only lands when the lookup came before the caller's own write:
 * exactly the window a create that looks the name up and then writes would race.
 */
class SameNameInsertingDriver(
    private val delegate: SqlDriver,
    private val table: String,
    private val name: String,
    private val otherId: String,
) : SqlDriver by delegate {
    /** Whether the lookup has been seen and the insert attempted. */
    var fired = false
        private set

    override fun <R> executeQuery(
        identifier: Int?,
        sql: String,
        mapper: (app.cash.sqldelight.db.SqlCursor) -> app.cash.sqldelight.db.QueryResult<R>,
        parameters: Int,
        binders: (app.cash.sqldelight.db.SqlPreparedStatement.() -> Unit)?,
    ): app.cash.sqldelight.db.QueryResult<R> {
        val result = delegate.executeQuery(identifier, sql, mapper, parameters, binders)
        if (!fired && sql.contains("FROM $table WHERE name = ?")) {
            fired = true
            val columns = if (table == "tags") "id, name, color, sort_order, deleted_at, updated_at, created_at" else "id, name, sort_order, deleted_at, updated_at, created_at"
            val values = if (table == "tags") "?, ?, NULL, 0, NULL, 1, 1" else "?, ?, 0, NULL, 1, 1"
            delegate.execute(
                null,
                "INSERT INTO $table ($columns) SELECT $values WHERE NOT EXISTS (SELECT 1 FROM $table WHERE name = ?)",
                3,
            ) {
                bindString(0, otherId)
                bindString(1, name)
                bindString(2, name)
            }
        }
        return result
    }
}
