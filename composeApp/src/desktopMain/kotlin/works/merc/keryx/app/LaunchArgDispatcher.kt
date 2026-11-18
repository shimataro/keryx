package works.merc.keryx.app

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import org.koin.core.Koin
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.domain.parseOAuthUri

private const val LOG_TAG = "Main"

/**
 * Routes a launch argument — a `keryx://` OAuth callback URI or an opened `.opml` file path — to
 * where it is handled, whichever route delivered it (the primary instance's own argv, a second
 * launch forwarded through [SingleInstanceCoordinator], or a macOS Apple Event).
 *
 * Construct it only once Koin is ready: the shared OAuth callback flow is resolved here.
 * [dispatchOpmlFile] resolves its own CoroutineScope/repository/notification dependencies via
 * `koin.get<>()` on each call rather than capturing `main()`'s appScope.
 */
internal class LaunchArgDispatcher(private val koin: Koin) {
    private val callbackFlow = koin.get<MutableSharedFlow<OAuthCallbackParams>>()

    /** Emits a received `keryx://` callback URI into the shared OAuth callback flow. */
    fun dispatchOAuthCallback(uri: String) {
        // Do not log the URI itself — it carries the OAuth authorization code.
        Log.info(LOG_TAG, "OAuth callback URI received")
        runCatching { callbackFlow.tryEmit(parseOAuthUri(uri)) }
    }

    /** Imports an opened `.opml` file on the app scope (see [handleOpenedOpmlFile]). */
    fun dispatchOpmlFile(path: String) {
        koin.get<CoroutineScope>().launch { handleOpenedOpmlFile(koin, path) }
    }

    /** Classifies [arg] and dispatches it; an argument that is neither kind is ignored. */
    fun dispatchLaunchArg(arg: String) {
        when (val launchArg = classifyLaunchArg(arg)) {
            is LaunchArg.OAuthCallback -> dispatchOAuthCallback(launchArg.uri)
            is LaunchArg.OpmlFile -> dispatchOpmlFile(launchArg.path)
            null -> Unit
        }
    }
}
