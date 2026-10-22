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
        // The app is about to leave the foreground on its own account (ExternalAppLaunch); a launch the
        // system refuses never leaves, so its mark is dropped again.
        ExternalAppLaunch.markLaunched()
        UIApplication.sharedApplication.openURL(nsUrl, options = emptyMap<Any?, Any>()) { opened ->
            if (!opened) ExternalAppLaunch.clear()
        }
    }

    private const val TAG = "BrowserOpener"
}
