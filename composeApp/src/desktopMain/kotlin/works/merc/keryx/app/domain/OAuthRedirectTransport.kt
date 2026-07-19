package works.merc.keryx.app.domain

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.OAUTH_CUSTOM_URI_REDIRECT
import java.net.InetAddress
import java.net.InetSocketAddress

/**
 * Delivers the OAuth 2.0 browser redirect back to the running app. The two
 * strategies differ by provider requirement: Dropbox accepts an arbitrary custom
 * URI scheme routed by the OS ([CustomUriRedirectTransport]); Google's "Desktop
 * app" clients only accept a loopback address, so a short-lived local HTTP server
 * receives it ([LoopbackRedirectTransport]).
 */
interface OAuthRedirectTransport {
    /**
     * Establishes the redirect endpoint, invokes [launchBrowser] with the concrete
     * `redirect_uri` to use (so the caller can build and open the authorize URL),
     * then suspends until the matching callback (by [state]) arrives or
     * [timeoutMillis] elapses. Returns null on timeout.
     */
    suspend fun capture(
        state: String,
        timeoutMillis: Long,
        launchBrowser: suspend (redirectUri: String) -> Unit,
    ): OAuthCallbackParams?
}

/**
 * Custom-URI-scheme transport (Dropbox). The redirect_uri is a fixed
 * `keryx://…` URL that the OS routes to the running instance; `main.kt` parses the
 * delivered URI and emits it into [callbackFlow]. This transport just waits for
 * the entry whose state matches.
 */
class CustomUriRedirectTransport(
    private val callbackFlow: MutableSharedFlow<OAuthCallbackParams>,
    private val redirectUri: String = OAUTH_CUSTOM_URI_REDIRECT,
) : OAuthRedirectTransport {
    override suspend fun capture(
        state: String,
        timeoutMillis: Long,
        launchBrowser: suspend (redirectUri: String) -> Unit,
    ): OAuthCallbackParams? {
        launchBrowser(redirectUri)
        return withTimeoutOrNull(timeoutMillis) { callbackFlow.first { it.state == state } }
    }
}

/**
 * Loopback-server transport (Google Drive). Binds an ephemeral
 * `http://127.0.0.1:<port>` HTTP server, uses that as the redirect_uri, and
 * completes when the browser hits it with a callback carrying the expected state.
 * The server is torn down as soon as the callback arrives (or on timeout).
 *
 * [successMessageProvider] supplies the localized page body shown in the browser
 * after the redirect (fetched once, before serving, so the handler stays
 * non-suspending).
 */
class LoopbackRedirectTransport(
    private val successMessageProvider: suspend () -> String,
) : OAuthRedirectTransport {
    override suspend fun capture(
        state: String,
        timeoutMillis: Long,
        launchBrowser: suspend (redirectUri: String) -> Unit,
    ): OAuthCallbackParams? {
        val server = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        val redirectUri = "http://127.0.0.1:${server.address.port}"
        val deferred = CompletableDeferred<OAuthCallbackParams>()
        val successPage = successHtml(successMessageProvider())

        server.createContext("/") { exchange ->
            try {
                val params = parseOAuthUri("http://127.0.0.1${exchange.requestURI}")
                // Only our real callback (matching state, with a code or an error) completes the wait;
                // stray requests (e.g. favicon) get a 404 and are ignored.
                if (params.state == state && (params.code != null || params.error != null)) {
                    respondHtml(exchange, successPage)
                    deferred.complete(params)
                } else {
                    exchange.sendResponseHeaders(404, -1)
                }
            } catch (e: Throwable) {
                Log.warn(TAG, "Loopback callback handler failed", e)
                runCatching { exchange.sendResponseHeaders(500, -1) }
            } finally {
                exchange.close()
            }
        }
        server.start()

        return try {
            launchBrowser(redirectUri)
            withTimeoutOrNull(timeoutMillis) { deferred.await() }
        } finally {
            server.stop(0)
        }
    }

    private fun respondHtml(exchange: HttpExchange, html: String) {
        val bytes = html.encodeToByteArray()
        exchange.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }

    private fun successHtml(message: String): String =
        "<!doctype html><html><head><meta charset=\"utf-8\">" +
            "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\"></head>" +
            "<body style=\"font-family: -apple-system, system-ui, sans-serif; text-align: center; padding: 3rem;\">" +
            "<p>${message.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")}</p>" +
            "</body></html>"

    private companion object {
        const val TAG = "OAuthLoopback"
    }
}
