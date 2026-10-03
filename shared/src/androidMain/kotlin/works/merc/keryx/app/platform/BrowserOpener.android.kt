package works.merc.keryx.app.platform

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.browser.customtabs.CustomTabsIntent
import works.merc.keryx.app.core.Log

private const val TAG = "BrowserOpener"

/**
 * Android [BrowserOpener]. Which launch a URL gets is decided by [browserLaunchKind]:
 * - http(s) opens in a Custom Tab — the user's default browser's in-app tab — themed to the app's
 *   own light/dark setting ([AndroidAppearance.isDark], not the system's) and with the browser's
 *   share action shown. `CustomTabsIntent.launchUrl` itself falls back to the plain default browser
 *   when no installed browser supports Custom Tabs.
 * - `mailto:` opens a mail composer (`ACTION_SENDTO`); anything else is a plain `ACTION_VIEW`.
 *
 * The launch starts from the resumed activity ([AndroidAppContext.resumedActivity]) when there is
 * one, so the tab joins the app's task and Back returns straight to it. With none (e.g. the app is
 * in the background) it falls back to [AndroidAppContext.application], which is not an activity
 * `Context`, so the `Intent` then needs `FLAG_ACTIVITY_NEW_TASK` — without it, starting an
 * activity from a non-activity context throws.
 */
actual object BrowserOpener {
    actual fun open(url: String) {
        val activity = AndroidAppContext.resumedActivity
        val context: Context = activity ?: AndroidAppContext.application
        // Trim once and normalize the scheme: browserLaunchKind judges the trimmed text without regard
        // to case, but Android's intent-filter scheme matching is case-sensitive and does not trim.
        val target = url.trim()
        val uri = Uri.parse(target).normalizeScheme()
        try {
            when (browserLaunchKind(target)) {
                BrowserLaunchKind.IN_APP_BROWSER_TAB -> {
                    val customTab = CustomTabsIntent.Builder()
                        .setColorScheme(
                            if (AndroidAppearance.isDark) {
                                CustomTabsIntent.COLOR_SCHEME_DARK
                            } else {
                                CustomTabsIntent.COLOR_SCHEME_LIGHT
                            },
                        )
                        .setShareState(CustomTabsIntent.SHARE_STATE_ON)
                        .build()
                    if (activity == null) customTab.intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    customTab.launchUrl(context, uri)
                }
                BrowserLaunchKind.MAIL_COMPOSE ->
                    context.startActivity(Intent(Intent.ACTION_SENDTO, uri).withTaskFlag(activity == null))
                BrowserLaunchKind.VIEW ->
                    context.startActivity(Intent(Intent.ACTION_VIEW, uri).withTaskFlag(activity == null))
            }
        } catch (e: ActivityNotFoundException) {
            Log.warn(TAG, "No activity found to open URL: $target", e)
        }
    }

    private fun Intent.withTaskFlag(needed: Boolean): Intent =
        if (needed) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else this
}
