package works.merc.keryx.app.ui.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import works.merc.keryx.app.domain.CloudConnectRequest

/**
 * Runs [NextcloudServerDialog]'s server check and hands its result on only while the dialog is still
 * open.
 *
 * Closing the dialog calls [close] at the moment of the close action itself: the dialog's
 * `rememberCoroutineScope` is only cancelled once the next recomposition takes the dialog out of the
 * composition, and a check that passed in between would otherwise still start the sign-in the user
 * just cancelled. The SwiftUI sheet has its own counterpart (`NextcloudServerCheckGate.swift`), since
 * each UI owns the lifetime of its own task.
 *
 * Main thread only: [start], [close] and the checks' resumption all run on the composition's
 * dispatcher, so [closed] needs no synchronization.
 */
internal class NextcloudServerCheckGate(private val scope: CoroutineScope) {
    /** Parent of every check started here, so [close] can cancel them all at once. */
    private val checks: Job = SupervisorJob(scope.coroutineContext[Job])
    private var closed = false

    /**
     * Starts [check] and calls [onPassed] with its result unless the gate was closed in the meantime or
     * the check produced nothing. A check already running is left alone: a second submit while one
     * runs is refused by the controller itself (it returns null), and must not cancel the first.
     * Does nothing once the gate is closed.
     */
    fun start(check: suspend () -> CloudConnectRequest?, onPassed: (CloudConnectRequest) -> Unit) {
        if (closed) return
        scope.launch(checks) {
            val request = check() ?: return@launch
            if (closed || !isActive) return@launch
            onPassed(request)
        }
    }

    /** Drops every running check and every result still to come. Idempotent. */
    fun close() {
        closed = true
        checks.cancel()
    }
}
