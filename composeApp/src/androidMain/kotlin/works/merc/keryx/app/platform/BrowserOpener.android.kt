package works.merc.keryx.app.platform

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import works.merc.keryx.app.core.Log

private const val TAG = "BrowserOpener"

/**
 * Android [BrowserOpener]. [AndroidAppContext.application] is an application, not an activity,
 * `Context`, so the launched `Intent` needs `FLAG_ACTIVITY_NEW_TASK` — without it, starting an
 * activity from a non-activity context throws.
 */
actual object BrowserOpener {
    actual fun open(url: String) {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            AndroidAppContext.application.startActivity(intent)
        } catch (e: ActivityNotFoundException) {
            Log.warn(TAG, "No activity found to open URL: $url", e)
        }
    }
}
