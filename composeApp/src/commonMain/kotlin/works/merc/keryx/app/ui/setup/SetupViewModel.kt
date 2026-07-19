package works.merc.keryx.app.ui.setup

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import works.merc.keryx.app.core.CloudStorageAvailability
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository

enum class SetupPhase { IDLE, CONNECTING, ERROR }

class SetupViewModel(
    private val settingsRepository: SettingsRepository,
    private val cloudSession: CloudSession,
    private val syncRepository: SyncRepository,
) : ViewModel() {

    /** Cloud providers configured in this build, in display order. */
    val availableCloudTypes: List<CloudStorageType> = CloudStorageAvailability.available

    var phase by mutableStateOf(SetupPhase.IDLE)
        private set

    private var authorizationJob: Job? = null

    /** True only while actively waiting on the OAuth browser redirect — the window [cancelConnect] can interrupt. */
    var canCancelConnect by mutableStateOf(false)
        private set

    fun chooseLocalOnly(onDone: () -> Unit) {
        viewModelScope.launch {
            settingsRepository.saveLocalSettings(
                settingsRepository.getLocalSettings().copy(cloudStorageType = null),
            )
            // Setup completion = local_settings.json exists, so make it durable before we navigate
            // away (survives an immediate quit before the coalesced write would have flushed).
            settingsRepository.flush()
            onDone()
        }
    }

    fun connect(type: CloudStorageType, onDone: () -> Unit) {
        viewModelScope.launch {
            phase = SetupPhase.CONNECTING
            val flow = cloudSession.connectFlow(type)
            if (flow == null) {
                phase = SetupPhase.ERROR
                return@launch
            }
            // Run only the interruptible OAuth-authorization wait as a child job. Cancelling a
            // child does not propagate up to the parent (structured concurrency), so the success
            // tail (saveTokens -> saveLocalSettings -> flush -> sync) runs to completion once
            // authorization resolves — never leaving durable tokens/settings behind a cancelled UI.
            val waitJob = async { flow.connect() }
            authorizationJob = waitJob
            canCancelConnect = true
            val result = try {
                waitJob.await()
            } catch (e: CancellationException) {
                phase = SetupPhase.IDLE
                return@launch
            } finally {
                authorizationJob = null
                canCancelConnect = false
            }
            when (result) {
                is Result.Ok -> {
                    cloudSession.saveTokens(type, result.value)
                    settingsRepository.saveLocalSettings(
                        settingsRepository.getLocalSettings().copy(cloudStorageType = type.id),
                    )
                    settingsRepository.flush()
                    // Merge whatever already exists in the cloud (imports on first sync).
                    syncRepository.sync()
                    phase = SetupPhase.IDLE
                    onDone()
                }
                is Result.Err -> phase = SetupPhase.ERROR
            }
        }
    }

    fun cancelConnect() {
        authorizationJob?.cancel()
    }
}
