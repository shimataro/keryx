package works.merc.keryx.app.platform

import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.domain.DeltaExportSql
import works.merc.keryx.app.domain.SnapshotSql
import works.merc.keryx.app.domain.TransportFileKind
import works.merc.keryx.app.domain.TransportFileName
import works.merc.keryx.app.domain.TransportMeta

/**
 * A delta written by [DeltaExporter.exportDelta].
 *
 * @property highWaterSeq `S`: every outbox entry `seq <= S` is covered by the file (and may be
 * deleted once it is committed); every later write carries a greater `seq`.
 * @property rowCounts rows written per synced table.
 */
data class DeltaExport(val highWaterSeq: Long, val rowCounts: Map<String, Long>)

/**
 * A full send written by [DeltaExporter.exportFull].
 *
 * @property highWaterSeq `S` of the copy it was built from, or `null` when the copy's outbox was empty.
 * @property rowCounts rows written per synced table.
 */
data class FullExport(val highWaterSeq: Long?, val rowCounts: Map<String, Long>)

/**
 * Builds the transport files this device sends — see "Sending" in `docs/sync-architecture.md`.
 *
 * The SQL is [DeltaExportSql]'s; only the connection is per platform, through
 * [TransportConnections] (JDBC on desktop, requery on Android, [RawSqliteConnection] on Apple) — one
 * dedicated connection per export, outside the SQLDelight driver, like [DatabaseMerger]: the desktop
 * driver opens a connection per statement, so neither the `ATTACH` nor the single read transaction
 * that ties `S` to the rows would survive on it.
 *
 * Blocking; call off the main thread.
 */
object DeltaExporter {

    /**
     * Writes an ordinary delta of the live database at [localDbPath] to [destPath] (replacing it),
     * carrying [meta] (a [TransportFileKind.DELTA]).
     *
     * In **one** transaction on one connection: `S = MAX(sync_outbox.seq)`, then the current row of
     * every key with an outbox entry `<= S`, the rows they reference, and light rows for articles
     * without a heavy entry ([DeltaExportSql.deltaStatements]). `S` and the rows come from the same
     * snapshot of the live database, so a write committed after `S` was read is not in the file and
     * carries a `seq > S` — it goes into the next delta. (In rollback-journal mode a writer instead
     * waits on `busy_timeout` until the export's read transaction ends.)
     *
     * @return the delta, or `null` when the outbox is empty — then no file is left at [destPath].
     * @throws IllegalStateException when the result would hold a dangling reference (the local
     * database references a row it does not have); nothing is left at [destPath].
     */
    fun exportDelta(localDbPath: String, destPath: String, meta: TransportMeta): DeltaExport? =
        exportDelta(localDbPath, destPath, meta, afterHighWaterMark = {})

    /** [exportDelta], with a hook run inside the transaction right after `S` is read — for tests. */
    internal fun exportDelta(
        localDbPath: String,
        destPath: String,
        meta: TransportMeta,
        afterHighWaterMark: () -> Unit,
    ): DeltaExport? {
        require(meta.kind == TransportFileKind.DELTA) { "An ordinary delta must be a delta" }
        return writeTransportFile(destPath, meta, listOf(DeltaExportSql.SOURCE to localDbPath)) { connection ->
            val s = connection.highWaterMark() ?: return@writeTransportFile null
            afterHighWaterMark()
            DeltaExportSql.deltaStatements(s).forEach(connection::exec)
            DeltaExport(s, finish(connection))
        }
    }

    /**
     * Writes a consistent copy of the live database at [localDbPath] to [destPath] with `VACUUM
     * INTO` (replacing it), the input of [exportFull]. Unlike [DatabaseSnapshot.exportForUpload] it
     * keeps every table — [exportFull] reads `S` from the copy's `sync_outbox` — and the live
     * database's read lock is held only for the copy.
     */
    fun copyForFullSend(localDbPath: String, destPath: String) {
        FileIO.delete(destPath)
        FileIO.delete("$destPath-journal")
        TransportConnections.open(localDbPath, TransportOpenMode.READ_WRITE).use { connection ->
            connection.setBusyTimeout()
            connection.exec(SnapshotSql.vacuumIntoStatement(destPath))
        }
    }

    /**
     * Writes a full send to [destPath] (replacing it), carrying [meta], from [snapshotPath] — a
     * [copyForFullSend] copy, never the live database: every synced row with its body, plus the rows
     * they reference. When [exceptPath] (a validated transport file of the current version, e.g. the
     * base just merged) is given, a row identical on every transport column to that file's row is left
     * out ([DeltaExportSql.fullStatements]).
     *
     * @param vector the `ks_vector` rows to write in the same transaction — a full send uploaded as a
     * base (the first device's, a reset's) covers its own delta `1` this way. Device ids must be sync
     * ids.
     * @return the export, whose `S` is the copy's outbox high-water mark (`null` for an empty outbox:
     * a file is still written, since a full send covers every row, not only recorded changes).
     * @throws IllegalStateException when the result would hold a dangling reference; nothing is left
     * at [destPath].
     */
    fun exportFull(
        snapshotPath: String,
        destPath: String,
        meta: TransportMeta,
        exceptPath: String?,
        vector: Map<String, Long> = emptyMap(),
    ): FullExport {
        require(vector.all { (device, seq) -> TransportFileName.isSyncId(device) && seq >= 1 }) { "Malformed vector" }
        val attachments = buildList {
            add(DeltaExportSql.SOURCE to snapshotPath)
            if (exceptPath != null) add(DeltaExportSql.EXCEPT to exceptPath)
        }
        return checkNotNull(
            writeTransportFile(destPath, meta, attachments) { connection ->
                val s = connection.highWaterMark()
                DeltaExportSql.fullStatements(withExcept = exceptPath != null).forEach(connection::exec)
                for ((device, seq) in vector) {
                    connection.exec("INSERT INTO main.ks_vector (device, seq) VALUES ('$device', $seq)")
                }
                FullExport(s, finish(connection))
            },
        )
    }

    /**
     * Creates the empty transport file, attaches [attachments] (alias to path) and runs [write] in one
     * transaction. The file is kept only when [write] returns a result; on `null` or a failure it is
     * deleted.
     */
    private fun <T : Any> writeTransportFile(
        destPath: String,
        meta: TransportMeta,
        attachments: List<Pair<String, String>>,
        write: (TransportConnection) -> T?,
    ): T? {
        var result: T? = null
        try {
            TransportFiles.createEmpty(destPath, meta)
            TransportConnections.open(destPath, TransportOpenMode.READ_WRITE).use { connection ->
                connection.setBusyTimeout()
                val attached = mutableListOf<String>()
                try {
                    // ATTACH must run outside a transaction.
                    for ((alias, path) in attachments) {
                        connection.exec(DeltaExportSql.attach(path, alias))
                        attached += alias
                    }
                    result = connection.transaction { write(connection) }
                } finally {
                    attached.asReversed().forEach { alias -> runCatching { connection.exec(DeltaExportSql.detach(alias)) } }
                }
            }
            return result
        } finally {
            if (result == null) {
                FileIO.delete(destPath)
                FileIO.delete("$destPath-journal")
            }
        }
    }

    /** Closure check, platform cleanup and row counts — the last steps inside the transaction. */
    private fun finish(connection: TransportConnection): Map<String, Long> {
        for (check in DeltaExportSql.closureChecks) {
            check(connection.queryRows(check.query).isEmpty()) {
                "The exported file would hold a dangling reference (${check.description})"
            }
        }
        DeltaExportSql.platformCleanupStatements.forEach(connection::exec)
        return DeltaExportSql.tableNames.associateWith { table ->
            connection.queryRows(DeltaExportSql.countQuery(table)).firstOrNull()?.firstOrNull()?.toLongOrNull() ?: 0L
        }
    }

    private fun TransportConnection.highWaterMark(): Long? =
        queryRows(DeltaExportSql.HIGH_WATER_MARK_QUERY).firstOrNull()?.firstOrNull()?.toLong()

    /**
     * `PRAGMA busy_timeout` through [TransportConnection.queryRows]: the pragma returns a row, which
     * requery's `execSQL` rejects.
     */
    private fun TransportConnection.setBusyTimeout() {
        queryRows("PRAGMA busy_timeout = $SQLITE_BUSY_TIMEOUT_MS")
    }
}
