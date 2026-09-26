package works.merc.keryx.app.platform

import platform.AppKit.NSWorkspace
import platform.Foundation.NSURL
import works.merc.keryx.app.core.Log

actual object BrowserOpener {
    /** Opens [url] with the user's default handler (browser, or mail client for `mailto:`). */
    actual fun open(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: run {
            Log.warn(TAG, "Not a valid URL: $url")
            return
        }
        NSWorkspace.sharedWorkspace.openURL(nsUrl)
    }

    private const val TAG = "BrowserOpener"
}
