package works.merc.keryx.app

import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.data.local.DatabaseTooNewException
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.database_too_new_message
import works.merc.keryx.app.resources.database_too_new_title
import javax.swing.JOptionPane
import kotlin.system.exitProcess

private const val LOG_TAG = "DatabaseTooNew"

/**
 * Tells the user this build cannot open their data, then exits.
 *
 * Shown before any window exists, so it is a plain Swing message box rather than a Compose dialog.
 * The process exits with a non-zero status afterwards; nothing has been written to the database
 * (see [works.merc.keryx.app.data.local.DatabaseDriverFactory]).
 */
internal fun showDatabaseTooNewAndExit(e: DatabaseTooNewException): Nothing {
    Log.error(LOG_TAG, "Refusing to open keryx.db", e)
    val (title, message) = runBlocking {
        getString(Res.string.database_too_new_title) to getString(Res.string.database_too_new_message)
    }
    JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE)
    exitProcess(1)
}
