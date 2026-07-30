package works.merc.keryx.app.platform

import works.merc.keryx.app.core.SQLITE_BUSY_TIMEOUT_MS
import java.io.File
import java.sql.DriverManager

actual object DatabaseSnapshot {
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
                st.execute("VACUUM INTO '${destPath.replace("'", "''")}'")
            }
        }

        // Exclude the derived FTS index from the uploaded file — on the copy, never the live DB.
        // Dropping the virtual table also removes its shadow tables (_data/_idx/_docsize/_config).
        DriverManager.getConnection("jdbc:sqlite:$destPath").use { conn ->
            conn.createStatement().use { st ->
                st.execute("DROP TABLE IF EXISTS articles_fts")
            }
        }
    }
}
