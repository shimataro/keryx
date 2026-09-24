package works.merc.keryx.app.domain

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * Session-only "background activity" tracker for the feed refresh (new-article check) and cloud
 * sync. Both the manual path ([HomeViewModel] via [FeedRepository.refreshAll]/[FeedRepository.refreshFeed]
 * and [SyncRepository.sync]) and the desktop background loop (`main.kt`, which has no ViewModel) run
 * their work inside [trackFeedRefresh] / [trackSync]; the UI observes [activity] to show a spinner. Sibling of [NotificationCenter]. Not persisted.
 *
 * A counter (rather than a plain boolean) keeps each indicator lit until *all* concurrent operations
 * finish — e.g. a background refresh overlapping a manual one, or a debounced sync overlapping a
 * manual sync. [MutableStateFlow.update] is CAS-atomic, so the counters never race.
 *
 * [refreshCycleRunning] is a third counter for the refresh-then-sync *sequences* (the manual
 * refresh in [HomeViewModel], desktop's background loop, Android's `FeedRefreshWorker`, and the
 * startup maintenance run, which syncs first and refreshes second): each wraps the whole sequence
 * in [trackRefreshCycle]. The two per-operation counters alone leave a gap between the refresh
 * finishing and the sync starting (new-article notification, connection check) where both
 * [feedRefreshing] and [syncing] are false, so anything asking "is work in flight?" must also
 * consult [refreshCycleRunning] to treat the whole sequence as busy end to end.
 *
 * [scope] is injectable so tests can supply `runTest`'s `backgroundScope` (see [SyncRepository] for
 * the same pattern). The default is an app-lifetime scope, matching this class's Koin `single`.
 */
class ActivityCenter(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    private val _activity = MutableStateFlow(ActivitySnapshot())

    /**
     * All three counters as one value. Backed directly by a [MutableStateFlow] (no derived
     * `stateIn` copy), so `activity.value` reflects a [trackFeedRefresh] / [trackSync] /
     * [trackRefreshCycle] the instant it starts or ends, from any thread, and a single read sees
     * all three counters consistently (e.g. [ActivitySnapshot.idle]).
     */
    val activity: StateFlow<ActivitySnapshot> = _activity.asStateFlow()

    /** Lagging `stateIn` copy of [ActivitySnapshot.feedRefreshing]; read [activity] instead. */
    val feedRefreshing: StateFlow<Boolean> =
        _activity.map { it.feedRefreshing }.stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun <T> trackFeedRefresh(block: suspend () -> T): T =
        track(
            { it.copy(feedRefreshCount = it.feedRefreshCount + 1) },
            { it.copy(feedRefreshCount = it.feedRefreshCount - 1) },
            block,
        )

    /** Lagging `stateIn` copy of [ActivitySnapshot.syncing]; read [activity] instead. */
    val syncing: StateFlow<Boolean> =
        _activity.map { it.syncing }.stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun <T> trackSync(block: suspend () -> T): T =
        track({ it.copy(syncCount = it.syncCount + 1) }, { it.copy(syncCount = it.syncCount - 1) }, block)

    /** Lagging `stateIn` copy of [ActivitySnapshot.refreshCycleRunning]; read [activity] instead. */
    val refreshCycleRunning: StateFlow<Boolean> =
        _activity.map { it.refreshCycleRunning }.stateIn(scope, SharingStarted.Eagerly, false)

    suspend fun <T> trackRefreshCycle(block: suspend () -> T): T =
        track(
            { it.copy(refreshCycleCount = it.refreshCycleCount + 1) },
            { it.copy(refreshCycleCount = it.refreshCycleCount - 1) },
            block,
        )

    private suspend fun <T> track(
        enter: (ActivitySnapshot) -> ActivitySnapshot,
        exit: (ActivitySnapshot) -> ActivitySnapshot,
        block: suspend () -> T,
    ): T {
        _activity.update(enter)
        try {
            return block()
        } finally {
            _activity.update(exit)
        }
    }
}

/**
 * One consistent reading of [ActivityCenter]'s counters: how many feed refreshes, syncs, and
 * refresh-then-sync cycles are in flight right now.
 */
data class ActivitySnapshot(
    val feedRefreshCount: Int = 0,
    val syncCount: Int = 0,
    val refreshCycleCount: Int = 0,
) {
    val feedRefreshing: Boolean get() = feedRefreshCount > 0
    val syncing: Boolean get() = syncCount > 0

    /** True while any refresh-then-sync sequence wrapped in [ActivityCenter.trackRefreshCycle] is in flight. */
    val refreshCycleRunning: Boolean get() = refreshCycleCount > 0

    /**
     * True when nothing at all is in flight — no refresh, no sync, and no refresh-then-sync cycle
     * (which also covers the gap between a cycle's refresh and its sync). The gate for starting a
     * new refresh/sync from the UI, since running both at once isn't supported.
     */
    val idle: Boolean get() = !feedRefreshing && !syncing && !refreshCycleRunning
}
