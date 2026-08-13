package works.merc.keryx.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
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
    /**
     * Runs the cloud provider's interactive OAuth connection flow.
     *
     * @return The issued OAuth tokens wrapped in a result.
     */
    suspend fun connect(): Result<OAuthTokens>
}

/**
 * Runs the cloud connection flow and reports its active job and cancellation availability.
 *
 * @param flow The cloud connection flow to run.
 * @param onJobChange Called with the active job, or `null` after completion or cancellation.
 * @param onCanCancelChange Called with `true` while cancellation is available and `false` afterward.
 * @return The OAuth tokens result, or `null` if the connection is cancelled.
 */
internal suspend fun CoroutineScope.awaitCancellableConnect(
    flow: CloudConnectFlow,
    onJobChange: (Job?) -> Unit,
    onCanCancelChange: (Boolean) -> Unit,
): Result<OAuthTokens>? {
    val waitJob = async { flow.connect() }
    onJobChange(waitJob)
    onCanCancelChange(true)
    return try {
        waitJob.await()
    } catch (_: CancellationException) {
        null
    } finally {
        onJobChange(null)
        onCanCancelChange(false)
    }
}
