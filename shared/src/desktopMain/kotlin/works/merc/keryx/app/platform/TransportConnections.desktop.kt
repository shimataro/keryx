package works.merc.keryx.app.platform

import org.sqlite.SQLiteConfig
import org.sqlite.SQLiteOpenMode
import works.merc.keryx.app.domain.SqliteFailureCategory
import java.sql.Connection
import java.sql.DriverManager

/** Desktop: a dedicated JDBC connection, like [DatabaseMerger]'s. */
actual object TransportConnections {
    actual fun open(path: String, mode: TransportOpenMode): TransportConnection {
        val config = SQLiteConfig()
        when (mode) {
            TransportOpenMode.READ_ONLY -> config.setReadOnly(true)
            TransportOpenMode.READ_WRITE -> {
                config.setOpenMode(SQLiteOpenMode.READWRITE)
                // Non-creating: a missing file must fail, not become an empty database.
                config.resetOpenMode(SQLiteOpenMode.CREATE)
            }
            TransportOpenMode.CREATE -> Unit
        }
        return JdbcTransportConnection(DriverManager.getConnection("jdbc:sqlite:$path", config.toProperties()))
    }

    actual fun failureCategory(failure: Throwable): SqliteFailureCategory? =
        failure.findSqliteCause()?.let { with(DatabaseMerger) { it.failureCategory() } }
}

private class JdbcTransportConnection(private val connection: Connection) : TransportConnection {
    override fun exec(sql: String) {
        connection.createStatement().use { it.execute(sql) }
    }

    override fun queryRows(sql: String): List<List<String?>> =
        connection.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                val columns = rs.metaData.columnCount
                buildList {
                    while (rs.next()) add(List(columns) { rs.getString(it + 1) })
                }
            }
        }

    override fun <T> transaction(block: () -> T): T {
        connection.autoCommit = false
        val result = try {
            block().also { connection.commit() }
        } catch (e: Throwable) {
            val rollbackFailure = runCatching { connection.rollback() }.exceptionOrNull()
            if (rollbackFailure == null) {
                // No transaction is open after a successful rollback, so restoring autoCommit (which
                // runs `commit;`) cannot keep anything. After a failed one it is skipped: closing the
                // connection rolls back whatever is still open.
                runCatching { connection.autoCommit = true }
            } else {
                e.addSuppressed(rollbackFailure)
            }
            throw e
        }
        connection.autoCommit = true
        return result
    }

    override fun close() {
        connection.close()
    }
}
