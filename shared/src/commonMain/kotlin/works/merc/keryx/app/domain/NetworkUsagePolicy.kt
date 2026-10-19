package works.merc.keryx.app.domain

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
     * Whether an automatic feed refresh may run. Feed fetches are conditional requests and usually
     * small, so a metered network alone does not stop them — only the OS's data-saving mode does.
     */
    fun automaticRefreshAllowed(condition: NetworkCondition): Boolean = !condition.dataSaver
}
