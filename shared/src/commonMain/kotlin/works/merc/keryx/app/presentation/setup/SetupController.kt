package works.merc.keryx.app.presentation.setup

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.CloudStorageAvailability
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.domain.CloudConnectionService
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.awaitCancellableConnect

enum class SetupPhase { IDLE, CONNECTING, ERROR }

/**
 * The setup/onboarding screen's shared state holder: choosing local-only vs. a cloud provider, and
 * running the interactive connect flow through to the initial sync. Shared with the SwiftUI app
 * (via `KeryxSdk.setupController`) so both UIs follow the same "flush settings before completing
 * setup" and "sync before opening Home" ordering — see `docs/app-architecture.md`'s "Apple Native
 * Apps (SwiftUI)".
 */
class SetupController(
    private val settingsRepository: SettingsRepository,
    private val cloudSession: CloudSession,
    private val syncRepository: SyncRepository,
    private val cloudConnectionService: CloudConnectionService,
    // Token store / sync touch the OS Keychain (macOS shells out to `security`, which may
    // block and show an authorization dialog), so keep them off the Main/EDT dispatcher —
    // same rationale as CloudSyncController's own dispatcher.
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    /** Cloud providers configured in this build, in display order. */
    val availableCloudTypes: List<CloudStorageType> = CloudStorageAvailability.available

    private val _phase = MutableStateFlow(SetupPhase.IDLE)
    val phase = _phase.asStateFlow()

    private var authorizationJob: Job? = null

    /** True only while actively waiting on the OAuth browser redirect — the window [cancelConnect] can interrupt. */
    private val _canCancelConnect = MutableStateFlow(false)
    val canCancelConnect = _canCancelConnect.asStateFlow()

    /**
     * Selects local-only storage and completes setup after persisting the setting.
     *
     * @param onDone Invoked after the updated setting has been flushed to durable storage.
     */
    fun chooseLocalOnly(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.mutateLocalSettings { it.copy(cloudStorageType = null) }
            // Setup completion = local_settings.json exists, so make it durable before we navigate
            // away (survives an immediate quit before the coalesced write would have flushed).
            settingsRepository.flush()
            onDone()
        }
    }

    /**
     * Connects to the selected cloud storage provider and synchronizes its data.
     *
     * @param type The cloud storage provider to connect to.
     * @param onDone Called after the connection succeeds and synchronization completes.
     */
    fun connect(type: CloudStorageType, onDone: () -> Unit) {
        viewModelScope.launch {
            _phase.value = SetupPhase.CONNECTING
            val flow = cloudSession.connectFlow(type)
            if (flow == null) {
                _phase.value = SetupPhase.ERROR
                return@launch
            }
            val result = awaitCancellableConnect(
                flow,
                onJobChange = { authorizationJob = it },
                onCanCancelChange = { _canCancelConnect.value = it },
            ) ?: run {
                _phase.value = SetupPhase.IDLE
                return@launch
            }
            when (result) {
                is Result.Ok -> {
                    // Saves the tokens and flushes the provider selection to disk before the sync
                    // below starts — see CloudConnectionService.completeConnect.
                    withContext(dispatcher) { cloudConnectionService.completeConnect(type, result.value) }
                    // Merge whatever already exists in the cloud (imports on first sync).
                    withContext(dispatcher) { syncRepository.sync() }
                    _phase.value = SetupPhase.IDLE
                    onDone()
                }
                is Result.Err -> _phase.value = SetupPhase.ERROR
            }
        }
    }

    fun cancelConnect() {
        authorizationJob?.cancel()
    }
}
