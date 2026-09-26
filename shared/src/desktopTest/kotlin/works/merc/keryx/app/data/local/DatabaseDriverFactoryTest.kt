package works.merc.keryx.app.data.local

import works.merc.keryx.app.data.local.db.KeryxDatabase
import java.io.File
import java.sql.DriverManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class DatabaseDriverFactoryTest {

    private fun tempDb(): File = File.createTempFile("keryx-factory-", ".db").apply { deleteOnExit() }

    private fun userVersion(file: File): Long =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st -> st.executeQuery("PRAGMA user_version").use { it.getLong(1) } }
        }

    private fun tableNames(file: File): Set<String> =
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT name FROM sqlite_master WHERE type = 'table'").use { rs ->
                    buildSet { while (rs.next()) add(rs.getString(1)) }
                }
            }
        }

    private fun setUserVersion(file: File, version: Long) {
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { it.execute("PRAGMA user_version = $version") }
        }
    }

    @Test
    fun freshFileIsCreatedAtTheCurrentVersion() {
        val file = tempDb()
        DatabaseDriverFactory().createDriver(file).close()
        assertEquals(KeryxDatabase.Schema.version, userVersion(file))
        assertTrue("articles" in tableNames(file))
    }

    @Test
    fun currentVersionFileOpensUnchanged() {
        val file = tempDb()
        DatabaseDriverFactory().createDriver(file).close()
        DatabaseDriverFactory().createDriver(file).close()
        assertEquals(KeryxDatabase.Schema.version, userVersion(file))
    }

    @Test
    fun newerVersionFileIsRefusedWithoutBeingWritten() {
        val file = tempDb()
        val newer = KeryxDatabase.Schema.version + 1
        setUserVersion(file, newer)

        val e = assertFailsWith<DatabaseTooNewException> { DatabaseDriverFactory().createDriver(file) }

        assertEquals(newer, e.databaseVersion)
        assertEquals(KeryxDatabase.Schema.version, e.supportedVersion)
        // Neither created nor migrated: the version stamp and the (empty) table set are untouched.
        assertEquals(newer, userVersion(file))
        assertTrue(tableNames(file).isEmpty())
    }
}
