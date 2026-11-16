package works.merc.keryx.app.domain

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.CloudCredentials

/**
 * Runs the interactive connect flow for one cloud provider, returning the credentials it issued.
 * For the OAuth providers that is a browser + OAuth 2.0 authorization-code-with-PKCE flow whose
 * redirect transport is provider- and platform-specific — Dropbox uses a custom URI scheme; Google
 * requires a loopback redirect — so this is an interface with a per-provider implementation.
 *
 * No current implementation reads the [CloudConnectRequest] it is handed: neither the OAuth flows
 * nor Android's Play services flow need anything from the user before they start.
 */
interface CloudConnectFlow {
    /**
     * Runs the cloud provider's interactive connection flow.
     *
     * @param request What the user supplied before the flow started; [CloudConnectRequest.NONE] when
     *   nothing was asked for.
     * @return The issued credentials wrapped in a result.
     */
    suspend fun connect(request: CloudConnectRequest = CloudConnectRequest.NONE): Result<CloudCredentials>
}

/**
 * Input the user supplies before a [CloudConnectFlow] starts. Empty for now: no current provider
 * needs any. A provider reached at a user-chosen server will carry that server's URL here.
 */
class CloudConnectRequest {
    companion object {
        /** The request for a flow that asks the user for nothing before it starts. */
        val NONE: CloudConnectRequest = CloudConnectRequest()
    }
}

/**
 * Runs the cloud connection flow and reports its active job and cancellation availability.
 *
 * @param flow The cloud connection flow to run.
 * @param request What the user supplied before the flow started, handed to [CloudConnectFlow.connect].
 * @param onJobChange Called with the active job, or `null` after completion or cancellation.
 * @param onCanCancelChange Called with `true` while cancellation is available and `false` afterward.
 * @return The credentials result, or `null` if the connection is cancelled.
 */
suspend fun CoroutineScope.awaitCancellableConnect(
    flow: CloudConnectFlow,
    request: CloudConnectRequest = CloudConnectRequest.NONE,
    onJobChange: (Job?) -> Unit,
    onCanCancelChange: (Boolean) -> Unit,
): Result<CloudCredentials>? {
    val waitJob = async { flow.connect(request) }
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
