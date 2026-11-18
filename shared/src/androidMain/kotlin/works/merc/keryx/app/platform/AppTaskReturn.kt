package works.merc.keryx.app.platform

import android.content.Intent
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.BrowserLoginPageLauncher
import works.merc.keryx.app.domain.LoginPageLauncher

private const val TAG = "AppTaskReturn"

/**
 * Brings the app's own task back to the front, dismissing a Custom Tab [BrowserOpener] opened on top
 * of it: the app's launch intent is started with `FLAG_ACTIVITY_CLEAR_TOP | FLAG_ACTIVITY_SINGLE_TOP`,
 * so the (`singleTask`) main activity is resumed through `onNewIntent` — which ignores a plain launcher
 * intent — and every activity above it in the task, the Custom Tab included, is finished.
 *
 * Best effort: Android may refuse to start an activity while the app is in the background, in which
 * case the user returns by hand (a Nextcloud sign-in still completes, since the app learns of it by
 * polling the server).
 */
fun bringAppTaskToFront() {
    val app = AndroidAppContext.application
    val intent = app.packageManager.getLaunchIntentForPackage(app.packageName) ?: return
    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
    try {
        app.startActivity(intent)
    } catch (e: RuntimeException) {
        Log.warn(TAG, "Could not bring the app back to the front", e)
    }
}

/** Android's [LoginPageLauncher]: a Custom Tab, dismissed by bringing the app's task back ([bringAppTaskToFront]). */
val androidLoginPageLauncher: LoginPageLauncher = BrowserLoginPageLauncher(returnToApp = ::bringAppTaskToFront)
