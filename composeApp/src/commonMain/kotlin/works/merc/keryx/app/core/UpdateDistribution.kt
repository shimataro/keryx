package works.merc.keryx.app.core

/**
 * Package names of installers that represent an app store with its own update mechanism.
 * `com.android.vending` is the modern Google Play Store; `com.google.android.feedback` is its
 * legacy predecessor, still occasionally reported by very old devices.
 */
internal val PLAY_STORE_INSTALLERS = setOf("com.android.vending", "com.google.android.feedback")

/**
 * Whether an in-app "check for update" (which links out to the GitHub release page) should be
 * offered, given the package that installed this app.
 *
 * This is a UX call, not a Google Play policy one: Play's Device and Network Abuse policy only
 * forbids an app modifying/replacing *itself* outside Play's own mechanism, or downloading
 * executable code from elsewhere — [works.merc.keryx.app.domain.UpdateChecker] does neither (it
 * only compares version strings and opens the release page in a browser). The reason to suppress
 * it under Play is that Play already auto-updates the app, so surfacing a second, GitHub-flavored
 * update path next to it would only confuse the user about which one to use. A sideloaded or
 * directly-downloaded install (or a distribution channel this app doesn't recognize) has no such
 * built-in mechanism, so the check stays offered — matching desktop, which always offers it.
 *
 * @param installerPackageName The package that installed this app, or `null` when unknown (e.g.
 *   adb-installed, or the platform couldn't report it) — treated the same as an unrecognized
 *   installer, since there's no evidence of a store update mechanism to defer to.
 */
fun isSelfUpdateCheckSupported(installerPackageName: String?): Boolean =
    installerPackageName !in PLAY_STORE_INSTALLERS
