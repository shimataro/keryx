package works.merc.keryx.app.domain

import works.merc.keryx.app.platform.BrowserOpener

/**
 * Opens the OAuth authorize URL for the user to interact with. [OAuthConnectFlow] calls this once
 * it has built the URL, right before it starts waiting for the redirect callback.
 *
 * The default ([DefaultAuthorizationLauncher]) opens [authorizeUrl] in the system's default
 * browser, which is how desktop and Android connect flows work. The Apple app instead hands both
 * arguments to Swift, which opens the URL in an `ASWebAuthenticationSession` — that API needs
 * [redirectUri] as well, to know which custom-URI-scheme callback to listen for (the scheme is
 * embedded in the URI, e.g. `keryx://oauth2/callback` or a Google reversed-client-id scheme; see
 * [schemeOf]).
 */
fun interface AuthorizationLauncher {
    fun launch(authorizeUrl: String, redirectUri: String)
}

/** Opens [authorizeUrl] in the platform's default browser via [BrowserOpener]; ignores [redirectUri]. */
object DefaultAuthorizationLauncher : AuthorizationLauncher {
    override fun launch(authorizeUrl: String, redirectUri: String) {
        BrowserOpener.open(authorizeUrl)
    }
}

/** The scheme portion of [uri] (everything before the first `:`), or `null` if there is none. */
fun schemeOf(uri: String): String? {
    val end = uri.indexOf(':')
    if (end <= 0) return null
    return uri.substring(0, end)
}
