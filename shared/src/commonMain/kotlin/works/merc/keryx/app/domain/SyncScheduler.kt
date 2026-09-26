package works.merc.keryx.app.domain

/**
 * Requests a (debounced) cloud sync after a local change. Implemented by
 * [SyncRepository]; injected into the other repositories so they don't depend
 * on the whole sync machinery.
 */
fun interface SyncScheduler {
    fun scheduleSync()
}
