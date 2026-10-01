package works.merc.keryx.app.presentation.settings

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * A [ManualSync] for tests of a screen that delegates to it (Home): [canSyncNow] is settable, every
 * [syncNow] call is counted, and an allowed one emits a [ManualSyncEdge.Started] /
 * [ManualSyncEdge.Finished] pair at once — an instant sync, like [CloudSyncController]'s own when
 * nothing is connected to transfer. [emit] lets a test raise edges no [syncNow] call caused, as a
 * sync started from another screen would.
 */
class FakeManualSync(canSyncNow: Boolean = true) : ManualSync {
    override val canSyncNow = MutableStateFlow(canSyncNow)

    private val _runs = MutableSharedFlow<ManualSyncEdge>(extraBufferCapacity = 16)
    override val runs: SharedFlow<ManualSyncEdge> = _runs

    /** How many times [syncNow] was called, allowed or not. */
    var syncNowCalls: Int = 0
        private set

    override fun syncNow() {
        syncNowCalls++
        if (!canSyncNow.value) return
        emit(ManualSyncEdge.Started)
        emit(ManualSyncEdge.Finished)
    }

    /** Emits [edge] as if a sync started elsewhere had reached it. */
    fun emit(edge: ManualSyncEdge) {
        check(_runs.tryEmit(edge)) { "FakeManualSync buffer full" }
    }
}
