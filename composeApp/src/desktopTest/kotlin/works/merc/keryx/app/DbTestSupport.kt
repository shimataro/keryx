package works.merc.keryx.app

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.KeryxDatabase
import java.io.File

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

/** A ready FtsManager bound to the given test driver. */
fun ftsManager(driver: SqlDriver): FtsManager = FtsManager(driver)

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
