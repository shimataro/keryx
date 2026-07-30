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
    suspend fun connect(): Result<OAuthTokens>
}

/**
 * Runs [flow]'s interruptible OAuth-authorization wait as a cancellable child job of this scope,
 * reporting it via [onJobChange] (so a `cancelConnect()` elsewhere can interrupt it) and
 * [onCanCancelChange] (so the UI can offer a cancel action only while the wait is outstanding).
 * Cancelling a child never propagates to the parent (structured concurrency), so callers can run
 * their success tail after a genuine [Result.Ok] without it being interrupted by that cancellation.
 * Returns `null` when the wait was cancelled — callers should treat that as a no-op, same as before
 * this was shared between the setup and settings cloud-connect screens' view models.
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
