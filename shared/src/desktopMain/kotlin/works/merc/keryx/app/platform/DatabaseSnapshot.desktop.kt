package works.merc.keryx.app.platform

import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import works.merc.keryx.app.domain.SnapshotSql
import java.io.File
import java.sql.DriverManager

actual object DatabaseSnapshot {
    /**
     * Creates a consistent SQLite database snapshot for upload and removes the derived full-text index.
     *
     * @param localDbPath Path to the live SQLite database.
     * @param destPath Path where the snapshot should be written.
     */
    actual fun exportForUpload(localDbPath: String, destPath: String) {
        // VACUUM INTO refuses to write to an existing file.
        File(destPath).delete()

        // Consistent snapshot of the whole DB (preserves user_version). Concurrent writes on the
        // SQLDelight driver's connections wait via their busy_timeout while VACUUM INTO holds its
        // read transaction; busy_timeout here likewise lets VACUUM INTO wait out (rather than error
        // on) a mark-as-read write mid-commit (those run outside the sync mutex).
        DriverManager.getConnection("jdbc:sqlite:$localDbPath").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA busy_timeout=$SQLITE_BUSY_TIMEOUT_MS")
                st.execute(SnapshotSql.vacuumIntoStatement(destPath))
            }
        }

        // See SnapshotSql's own KDoc for why each of these statements is dropped/run, and in this order.
        DriverManager.getConnection("jdbc:sqlite:$destPath").use { conn ->
            conn.createStatement().use { st ->
                SnapshotSql.cleanupStatements().forEach { st.execute(it) }
            }
        }
    }
}
