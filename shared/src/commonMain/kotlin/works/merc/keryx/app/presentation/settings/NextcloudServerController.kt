package works.merc.keryx.app.presentation.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.errorKind
import works.merc.keryx.app.data.cloud.nextcloud.normalizeNextcloudUrl
import works.merc.keryx.app.domain.CloudConnectRequest

/**
 * Everything the Nextcloud server dialog shows. [checking] is true while the address is being
 * checked; [error] says why the last check rejected it.
 */
data class NextcloudServerState(
    val url: String = "",
    val checking: Boolean = false,
    val error: ErrorKind? = null,
) {
    /** Whether the confirm action is available right now. */
    val confirmEnabled: Boolean get() = !checking && url.isNotBlank()
}

/**
 * The Nextcloud server dialog's state machine, shared by both UIs and by every route that connects
 * Nextcloud (first-run setup, the Cloud Sync settings tab's connect, switch and reconnect): type the
 * server's address → check it (normalize, then probe `status.php`) → hand back the
 * [CloudConnectRequest] the connect flow takes. One instance lives as long as one dialog does.
 *
 * @param probe Checks a normalized base URL for an installed Nextcloud server
 *   (`NextcloudServerProbe.probe`).
 * @param initialUrl The address the input starts with — the server last connected to, on a
 *   reconnect (`CloudSyncController.reconnectServerUrl`).
 */
class NextcloudServerController(
    private val probe: suspend (String) -> Result<Unit>,
    initialUrl: String = "",
) {
    private val _state = MutableStateFlow(NextcloudServerState(url = initialUrl))
    val state: StateFlow<NextcloudServerState> = _state.asStateFlow()

    /** Edits the address, clearing the previous check's error; a check in flight keeps running. */
    fun setUrl(url: String) = _state.update { NextcloudServerState(url = url, checking = it.checking) }

    /**
     * Checks the typed address. A no-op (null) while a check is already running or the input is
     * blank; at most one concurrent call ever starts a check, as in `AddFeedController.submit`.
     *
     * @return The request to connect with once the address passed, or null when it did not (the
     *   reason is then in [NextcloudServerState.error]) or was edited while it was being checked.
     */
    suspend fun submit(): CloudConnectRequest? {
        val s = _state.value
        if (!s.confirmEnabled) return null
        val started = s.copy(checking = true, error = null)
        if (!_state.compareAndSet(s, started)) return null

        val result: Result<String> = try {
            when (val normalized = normalizeNextcloudUrl(s.url)) {
                is Result.Ok -> when (val probed = probe(normalized.value)) {
                    is Result.Ok -> normalized
                    is Result.Err -> probed
                }
                is Result.Err -> normalized
            }
        } catch (e: CancellationException) {
            // Cancelled mid-check (the dialog closed, the caller's task was cancelled): end the
            // check so the confirm action comes back. Only one check ever runs at a time (see the
            // compareAndSet above), so `checking` is this call's even if the address was edited.
            _state.update { it.copy(checking = false) }
            throw e
        }
        var request: CloudConnectRequest? = null
        _state.update { current ->
            // update's lambda can re-run when the state moved under it, so decide afresh each time.
            request = null
            // Edited while being checked: the newer input wins, and this stale result is dropped.
            if (current.url != s.url) return@update current.copy(checking = false)
            when (result) {
                is Result.Ok -> {
                    request = CloudConnectRequest(serverUrl = result.value)
                    current.copy(checking = false)
                }
                is Result.Err -> current.copy(checking = false, error = result.exception.errorKind)
            }
        }
        return request
    }
}
