package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import works.merc.keryx.app.core.NetworkCondition

/**
 * The single decision on which *automatic* network work may run on the current network — shared by
 * every caller (the sync gate in [SyncRepository], the refresh gate in [RefreshCycleRunner], and
 * both UIs through them). Work a person explicitly asked for ([SyncTrigger.MANUAL]) is never subject
 * to it.
 *
 * | | Unrestricted | Metered | Data Saver / Low Data Mode |
 * | --- | --- | --- | --- |
 * | Automatic sync | runs | runs only if [syncOnMetered] | deferred |
 * | Automatic feed refresh | runs | runs | skipped |
 * | Folding deltas into a base (any sync) | runs | skipped | skipped |
 */
object NetworkUsagePolicy {
    /**
     * Whether an automatic cloud sync may run. A sync uploads a snapshot of the whole database, so
     * it is held back on a metered network unless the user opted in ([syncOnMetered]), and always
     * while the OS's data-saving mode is on.
     */
    fun automaticSyncAllowed(condition: NetworkCondition, syncOnMetered: Boolean): Boolean =
        !condition.dataSaver && (!condition.metered || syncOnMetered)

    /**
     * [automaticSyncAllowed] as a flow over the live network condition and the user's opt-in — the
     * one composition both production wiring and tests use, so the gate itself is what gets tested.
     */
    fun automaticSyncAllowedFlow(
        condition: Flow<NetworkCondition>,
        syncOnMetered: Flow<Boolean>,
    ): Flow<Boolean> = combine(condition, syncOnMetered) { c, onMetered ->
        automaticSyncAllowed(c, onMetered)
    }.distinctUntilChanged()

    /**
     * Whether the background job that carries a sync deferred for the network must wait for an
     * unmetered network. Without the opt-in ([syncOnMetered] false) only an unmetered network lets
     * the sync through; with it, any connection does. Data Saver needs no network type of its own:
     * the OS stops background traffic on a metered network while it is on, and a job that still runs
     * early is deferred again by the sync gate and retried.
     */
    fun deferredSyncNeedsUnmeteredNetwork(syncOnMetered: Boolean): Boolean = !syncOnMetered

    /**
     * Whether an automatic feed refresh may run. Feed fetches are conditional requests and usually
     * small, so a metered network alone does not stop them — only the OS's data-saving mode does.
     */
    fun automaticRefreshAllowed(condition: NetworkCondition): Boolean = !condition.dataSaver

    /**
     * Whether a sync may fold deltas into a new base ("Bases and Garbage Collection" in
     * sync-architecture.md). A fold downloads and uploads a whole base, which nothing is waiting for —
     * any later sync on another network can do it — so it is skipped on a metered network even for a
     * manual sync and even with [syncOnMetered] on.
     */
    fun compactionAllowed(condition: NetworkCondition): Boolean = !condition.metered && !condition.dataSaver

    /** [compactionAllowed] as a flow over the live network condition. */
    fun compactionAllowedFlow(condition: Flow<NetworkCondition>): Flow<Boolean> =
        condition.map(::compactionAllowed).distinctUntilChanged()
}
