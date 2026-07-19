package works.merc.keryx.app.domain

import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.OAuthTokens

/**
 * Runs the interactive connect flow for one cloud provider (browser + OAuth 2.0
 * authorization-code-with-PKCE), returning the issued tokens. The redirect
 * transport is provider- and platform-specific — Dropbox uses a custom URI
 * scheme; Google requires a loopback redirect — so this is an interface with a
 * per-provider implementation.
 */
interface CloudConnectFlow {
    suspend fun connect(): Result<OAuthTokens>
}
