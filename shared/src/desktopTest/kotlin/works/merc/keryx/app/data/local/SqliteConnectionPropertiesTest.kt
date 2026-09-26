package works.merc.keryx.app.data.local

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.data.local.db.KeryxDatabase
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The driver opens a fresh connection per statement for file DBs, so a PRAGMA must travel in the
 * connection [java.util.Properties] to hold for every statement — issuing it once through
 * `driver.execute` applies it to a connection that is closed immediately afterwards. These tests
 * exercise the real production properties against a temp-file DB (the factory itself hard-references
 * `AppDirs.appDataDir()` and cannot be pointed at a test directory).
 */
class SqliteConnectionPropertiesTest {

    private fun productionDriver(): Pair<File, JdbcSqliteDriver> {
        val file = File.createTempFile("keryx-props-", ".db").apply { deleteOnExit() }
        val driver = JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}", sqliteConnectionProperties())
        KeryxDatabase.Schema.create(driver)
        return file to driver
    }

    private fun pragma(driver: JdbcSqliteDriver, name: String): Long =
        driver.executeQuery(
            identifier = null,
            sql = "PRAGMA $name;",
            mapper = { cursor ->
                app.cash.sqldelight.db.QueryResult.Value(
                    if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L,
                )
            },
            parameters = 0,
        ).value

    @Test
    fun foreignKeysAreEnforcedOnEveryConnectionNotJustTheFirst() {
        val (file, driver) = productionDriver()
        try {
            val db = KeryxDatabase(driver)
            // A separate statement from the one that opened the DB: this is where a one-off
            // `PRAGMA foreign_keys=ON` would already have been lost with the connection it ran on.
            val failure = assertFailsWith<Exception> {
                db.articlesQueries.insert(
                    id = "a1", feed_id = "no-such-feed", guid = "g1", url = "https://article/a1",
                    title = "t", summary = null, content = null, author = null, published_at = null,
                    thumbnail_url = null, is_read = 0L, read_at = null, is_starred = 0L,
                    starred_at = null, cached_at = 0L, search_text = "", updated_at = 0L,
                    created_at = 0L,
                )
            }
            assertTrue(
                generateSequence<Throwable>(failure) { it.cause }
                    .any { it.message?.contains("FOREIGN KEY", ignoreCase = true) == true },
                "expected a foreign-key violation, got: $failure",
            )
        } finally {
            driver.close()
            file.delete()
        }
    }

    @Test
    fun busyTimeoutAppliesToEveryConnection() {
        val (file, driver) = productionDriver()
        try {
            assertEquals(SQLITE_BUSY_TIMEOUT_MS, pragma(driver, "busy_timeout"))
        } finally {
            driver.close()
            file.delete()
        }
    }
}
