package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import works.merc.keryx.app.data.local.DatabaseTooNewException
import javax.swing.JOptionPane
import javax.swing.SwingUtilities
import kotlin.system.exitProcess

private const val LOG_TAG = "DatabaseTooNew"

/**
 * Tells the user this build cannot open their data, then exits.
 *
 * Shown before any window exists, so it is a plain Swing message box rather than a Compose dialog.
 * [title] and [message] arrive already localized: the caller (`main`) resolves them inside its own
 * startup `runBlocking`, so this function never blocks on a resource read itself. The box is shown on
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
