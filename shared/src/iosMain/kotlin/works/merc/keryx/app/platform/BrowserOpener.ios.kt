package works.merc.keryx.app.platform

import platform.Foundation.NSURL
import platform.UIKit.UIApplication
import works.merc.keryx.app.core.Log

actual object BrowserOpener {
    /** Opens [url] with the user's default handler (browser, or mail client for `mailto:`). */
    actual fun open(url: String) {
        val nsUrl = NSURL.URLWithString(url) ?: run {
            Log.warn(TAG, "Not a valid URL: $url")
            return
        }
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>(), completionHandler = null)
    }

    private const val TAG = "BrowserOpener"
}
