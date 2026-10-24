package works.merc.keryx.app.domain

/**
 * Requests a (debounced) cloud sync after a local change. Implemented by
 * [SyncRepository]; injected into the other repositories so they don't depend
 * on the whole sync machinery.
 */
fun interface SyncScheduler {
    /** Requests a sync shortly after the last change (a structural edit: feeds, folders, tags, settings). */
    fun scheduleSync()

    /**
     * Requests a sync after a much longer quiet period, for reading-state changes (read / unread /
     * star) that arrive one by one while reading. The change is never lost by waiting: any sync that
     * runs first — a refresh cycle's, a manual one, a [scheduleSync] window, or a flush when the app
     * leaves the foreground — uploads it along with everything else.
     */
    fun scheduleDeferredSync() = scheduleSync()
}
