package works.merc.keryx.app.platform

/**
 * How a platform [BrowserOpener] hands a URL to the OS. Only Android distinguishes all three today
 * (`BrowserOpener.android.kt`); the decision lives here, in commonMain, so it can be unit-tested
 * without an Android runtime.
 */
enum class BrowserLaunchKind {
    /** An http(s) page: shown in the default browser's in-app tab (Android Custom Tabs). */
    IN_APP_BROWSER_TAB,

    /** A `mailto:` link: handed to a mail composer (Android `ACTION_SENDTO`). */
    MAIL_COMPOSE,

    /** Any other link: handed to whatever app claims it (Android `ACTION_VIEW`), as before. */
    VIEW,
}

/**
 * Picks the [BrowserLaunchKind] for [url] from its scheme alone, case-insensitively. Only an
 * absolute `http://` / `https://` URL gets an in-app browser tab, since a Custom Tab can render
 * nothing else; `mailto:` keeps its mail-composer launch; everything else (including a scheme-less
 * or relative link) keeps the plain view launch. This does not decide *whether* a link may be
 * opened at all — callers gate untrusted links with `canOpenInBrowser` first.
 */
fun browserLaunchKind(url: String): BrowserLaunchKind {
    val trimmed = url.trim()
    return when {
        trimmed.startsWith("http://", ignoreCase = true) ||
            trimmed.startsWith("https://", ignoreCase = true) -> BrowserLaunchKind.IN_APP_BROWSER_TAB
        trimmed.startsWith("mailto:", ignoreCase = true) -> BrowserLaunchKind.MAIL_COMPOSE
        else -> BrowserLaunchKind.VIEW
    }
}
