package works.merc.keryx.app.platform

import org.sqlite.SQLiteConfig
import works.merc.keryx.app.core.LocalWriteFailureKind
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [localWriteFailureKind]'s desktop actual against the exceptions the real JDBC driver throws — a
 * write that loses the write lock, one that runs out of pages, and one on a read-only connection.
 */
class LocalWriteFailureDesktopTest {
    private val file = File.createTempFile("keryx-write-failure-", ".db")
    private val connections = mutableListOf<Connection>()

    @AfterTest
    fun tearDown() {
        connections.forEach { it.close() }
        File(file.path + "-journal").delete()
        file.delete()
    }

    private fun connect(config: SQLiteConfig = SQLiteConfig()): Connection =
        config.createConnection("jdbc:sqlite:${file.path}").also { connections += it }

    private fun Connection.exec(sql: String) = createStatement().use { it.execute(sql) }

    @Test
    fun aWriteBlockedByAnotherConnectionsLockIsBusy() {
        connect().exec("CREATE TABLE t (v TEXT)")
        val holder = connect().apply { exec("BEGIN EXCLUSIVE") }
        val writer = connect(SQLiteConfig().apply { setBusyTimeout(0) })

        val e = assertFailsWith<SQLException> { writer.exec("INSERT INTO t VALUES ('x')") }

        assertEquals(LocalWriteFailureKind.BUSY, e.localWriteFailureKind())
        holder.exec("ROLLBACK")
    }

    @Test
    fun aWriteThatRunsOutOfPagesIsDiskFull() {
        val c = connect()
        c.exec("CREATE TABLE t (v TEXT)")
        c.exec("PRAGMA max_page_count = ${c.pageCount() + 1}")

        val e = assertFailsWith<SQLException> {
            repeat(1_000) { c.exec("INSERT INTO t VALUES (randomblob(4096))") }
        }

        assertEquals(LocalWriteFailureKind.DISK_FULL, e.localWriteFailureKind())
    }

    @Test
    fun aWriteOnAReadOnlyConnectionIsOther() {
        connect().exec("CREATE TABLE t (v TEXT)")
        val readOnly = connect(SQLiteConfig().apply { setReadOnly(true) })

        val e = assertFailsWith<SQLException> { readOnly.exec("INSERT INTO t VALUES ('x')") }

        assertEquals(LocalWriteFailureKind.OTHER, e.localWriteFailureKind())
    }

    @Test
    fun aWrappedSqliteFailureIsClassifiedByItsCause() {
        connect().exec("CREATE TABLE t (v TEXT)")
        val holder = connect().apply { exec("BEGIN EXCLUSIVE") }
        val writer = connect(SQLiteConfig().apply { setBusyTimeout(0) })
        val busy = assertFailsWith<SQLException> { writer.exec("INSERT INTO t VALUES ('x')") }

        assertEquals(LocalWriteFailureKind.BUSY, IllegalStateException("wrapped", busy).localWriteFailureKind())
        holder.exec("ROLLBACK")
    }

    @Test
    fun aFailureWithNoSqliteCauseIsOther() {
        assertEquals(LocalWriteFailureKind.OTHER, IllegalStateException("not SQLite").localWriteFailureKind())
    }

    private fun Connection.pageCount(): Int =
        createStatement().use { st -> st.executeQuery("PRAGMA page_count").use { it.next(); it.getInt(1) } }
}
