package works.merc.keryx.app.platform

import works.merc.keryx.app.domain.SqliteFailureCategory

/** Apple: a dedicated [RawSqliteConnection], like [DatabaseMerger]'s. */
actual object TransportConnections {
    actual fun open(path: String, mode: TransportOpenMode): TransportConnection =
        RawTransportConnection(
            RawSqliteConnection.open(
                path,
                create = mode == TransportOpenMode.CREATE,
                readOnly = mode == TransportOpenMode.READ_ONLY,
            ),
        )

    actual fun failureCategory(failure: Throwable): SqliteFailureCategory? =
        failure.findSqliteResultCode()?.let(DatabaseMerger::failureCategory)
}

private class RawTransportConnection(private val db: RawSqliteConnection) : TransportConnection {
    override fun exec(sql: String) = db.exec(sql)

    override fun queryRows(sql: String): List<List<String?>> = db.queryRowsStrict(sql)

    override fun <T> transaction(block: () -> T): T {
        db.exec("BEGIN")
        val result = try {
            block()
        } catch (e: Throwable) {
            // A rollback failure must not replace (and hide) the failure itself.
            runCatching { if (db.inTransaction()) db.exec("ROLLBACK") }.exceptionOrNull()?.let(e::addSuppressed)
            throw e
        }
        db.exec("COMMIT")
        return result
    }

    override fun close() = db.close()
}
