package works.merc.keryx.app

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.runBlocking
import org.jetbrains.compose.resources.getString
import org.koin.core.Koin
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.data.local.DatabaseTooNewException
import works.merc.keryx.app.data.local.findDatabaseTooNew
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.database_too_new_message
import works.merc.keryx.app.resources.database_too_new_title
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

private const val LOG_TAG = "DatabaseTooNew"

/**
 * Tells the user this build cannot open their data, then exits.
 *
 * Shown before any window exists, so it is a plain Swing message box rather than a Compose dialog.
 * [title] and [message] arrive already localized: the caller ([openDatabaseOrExit], on `main`'s startup
 * path) resolves them inside its own `runBlocking`, so this function never blocks on a resource read itself. The box is shown on
 * the Swing Event Dispatch Thread via [SwingUtilities.invokeAndWait] (Swing starts the EDT lazily on
 * first use, so this works even though no window has been created yet), and this call returns only
 * once the user has dismissed it. The process exits with a non-zero status afterwards; nothing has
 * been written to the database (see [works.merc.keryx.app.data.local.DatabaseDriverFactory]).
 */
internal fun showDatabaseTooNewAndExit(e: DatabaseTooNewException, title: String, message: String): Nothing {
    Log.error(LOG_TAG, "Refusing to open keryx.db", e)
    SwingUtilities.invokeAndWait {
        JOptionPane.showMessageDialog(null, message, title, JOptionPane.ERROR_MESSAGE)
    }
    exitProcess(1)
}

/**
 * Opens the database through Koin's [SqlDriver] binding; if the file was migrated by a newer build,
 * shows [showDatabaseTooNewAndExit] and exits. Any other failure is rethrown unchanged.
 */
internal fun openDatabaseOrExit(koin: Koin) {
    try {
        koin.get<SqlDriver>()
    } catch (e: Exception) {
        val tooNew = findDatabaseTooNew(e) ?: throw e
        // Resolved here rather than inside showDatabaseTooNewAndExit so the blocking resource read
        // stays on main()'s startup path, alongside its other sanctioned runBlocking calls.
        val (title, message) = runBlocking {
            getString(Res.string.database_too_new_title) to getString(Res.string.database_too_new_message)
        }
        showDatabaseTooNewAndExit(tooNew, title, message)
    }
}
