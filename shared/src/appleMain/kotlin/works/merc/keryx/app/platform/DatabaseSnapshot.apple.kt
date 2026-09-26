package works.merc.keryx.app.platform

import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.domain.SnapshotSql

actual object DatabaseSnapshot {
    /** `VACUUM INTO` a copy of [localDbPath], then strip the local-only tables/indexes from the copy. */
    actual fun exportForUpload(localDbPath: String, destPath: String) {
        FileIO.delete(destPath)
        RawSqliteConnection.open(localDbPath).use { conn ->
            conn.busyTimeout(SQLITE_BUSY_TIMEOUT_MS)
            conn.exec(SnapshotSql.vacuumIntoStatement(destPath))
        }
        RawSqliteConnection.open(destPath).use { conn ->
            SnapshotSql.cleanupStatements().forEach(conn::exec)
        }
    }
}
