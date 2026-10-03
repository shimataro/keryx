package works.merc.keryx.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import works.merc.keryx.app.core.Log

private const val TAG = "ShareSheet"

/**
 * Android [ShareSheet]: the system share sheet, via `Intent.createChooser` around a `text/plain`
 * `ACTION_SEND` carrying the text as `EXTRA_TEXT` and the subject as `EXTRA_SUBJECT` (read by mail
 * and similar apps) and `EXTRA_TITLE` (the Sharesheet's own preview title).
 *
 * Like `BrowserOpener`, the launch starts from the resumed activity
 * ([AndroidAppContext.resumedActivity]) when there is one, so the sheet joins the app's task; with
 * none it falls back to the application context, which needs `FLAG_ACTIVITY_NEW_TASK` to start an
 * activity at all.
 */
actual object ShareSheet {
    actual fun shareText(text: String, subject: String?, chooserTitle: String) {
        val activity = AndroidAppContext.resumedActivity
        val context: Context = activity ?: AndroidAppContext.application
        val send = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_TEXT, text)
            if (subject != null) {
                putExtra(Intent.EXTRA_SUBJECT, subject)
                putExtra(Intent.EXTRA_TITLE, subject)
            }
        }
        val chooser = Intent.createChooser(send, chooserTitle)
        if (activity == null) chooser.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            context.startActivity(chooser)
        } catch (e: ActivityNotFoundException) {
            Log.warn(TAG, "No activity found to share text", e)
        }
    }
}
