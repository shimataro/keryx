package works.merc.keryx.app.domain

import works.merc.keryx.app.platform.BrowserOpener

/**
 * Shows a sign-in page that the app does not receive a redirect from — Nextcloud's Login Flow v2,
 * where the app learns that the user signed in by polling the server instead (see
 * `data/cloud/nextcloud/NextcloudLoginFlow.kt`). [close] is called once the flow ends, whatever the
 * outcome, so the platform can put the app back in front of (or dismiss) the page it opened.
 *
 * Unlike [AuthorizationLauncher], there is no callback URL to listen for.
 */
interface LoginPageLauncher {
    /** Opens [url] for the user to sign in on. */
    fun open(url: String)

    /** Ends what [open] showed, as far as the platform allows; a no-op when nothing is open. */
    fun close()
}

/**
 * Opens the sign-in page in the system browser ([BrowserOpener]) — desktop and Android. A browser
 * tab cannot be closed from outside, so [close] runs [returnToApp] instead: desktop brings its
 * window back to the front, Android brings its own task back over the Custom Tab.
 */
class BrowserLoginPageLauncher(private val returnToApp: () -> Unit) : LoginPageLauncher {
    override fun open(url: String) {
        BrowserOpener.open(url)
    }

    override fun close() {
        returnToApp()
    }
}
