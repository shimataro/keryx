package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Session-only "background activity" tracker for the feed refresh (new-article check) and cloud
 * sync. Both the manual path ([HomeViewModel] via [FeedRepository.refreshAll]/[FeedRepository.refreshFeed]
 * and [SyncRepository.sync]) and the desktop background loop (`main.kt`, which has no ViewModel) run
 * their work inside [trackFeedRefresh] / [trackSync]; the UI observes [activity] to show a spinner.
 * Sibling of [NotificationCenter]. Not persisted.
 *
 * A counter (rather than a plain boolean) keeps each indicator lit until *all* concurrent operations
 * finish — e.g. a background refresh overlapping a manual one, or a debounced sync overlapping a
 * manual sync. [MutableStateFlow.update] is CAS-atomic, so the counters never race.
 *
 * [ActivitySnapshot.refreshCycleCount] is a third counter for the refresh-then-sync *sequences*
 * (the manual refresh in [HomeViewModel], desktop's background loop, Android's `FeedRefreshWorker`,
 * and the startup maintenance run, which syncs first and refreshes second): each wraps the whole
 * sequence in [trackRefreshCycle]. The two per-operation counters alone leave a gap between the
 * refresh finishing and the sync starting (new-article notification, connection check) where both
 * [ActivitySnapshot.feedRefreshing] and [ActivitySnapshot.syncing] are false, so anything asking
 * "is work in flight?" must also consult [ActivitySnapshot.refreshCycleRunning] — or simply
 * [ActivitySnapshot.idle] — to treat the whole sequence as busy end to end.
 *
 * All three counters live in one [MutableStateFlow], exposed as-is through [activity] rather than
 * as derived `stateIn` copies. A derived copy only catches up once its sharing coroutine gets
 * dispatched, so a read right after a `track*` call started could still see the old value (letting
 * two refreshes past a double-start guard); reading the backing flow itself never lags, and
 * needs no coroutine scope of its own.
 */
class ActivityCenter {
    private val _activity = MutableStateFlow(ActivitySnapshot())

    /**
     * All three counters as one value. Backed directly by a [MutableStateFlow] (no derived
     * `stateIn` copy), so `activity.value` reflects a [trackFeedRefresh] / [trackSync] /
     * [trackRefreshCycle] the instant it starts or ends, from any thread, and a single read sees
     * all three counters consistently (e.g. [ActivitySnapshot.idle]).
     */
    val activity: StateFlow<ActivitySnapshot> = _activity.asStateFlow()

    suspend fun <T> trackFeedRefresh(block: suspend () -> T): T =
        track(
            { it.copy(feedRefreshCount = it.feedRefreshCount + 1) },
            { it.copy(feedRefreshCount = it.feedRefreshCount - 1) },
            block,
        )

    suspend fun <T> trackSync(block: suspend () -> T): T =
        track({ it.copy(syncCount = it.syncCount + 1) }, { it.copy(syncCount = it.syncCount - 1) }, block)

    suspend fun <T> trackRefreshCycle(block: suspend () -> T): T =
        track(
            { it.copy(refreshCycleCount = it.refreshCycleCount + 1) },
            { it.copy(refreshCycleCount = it.refreshCycleCount - 1) },
            block,
        )

    /**
     * Runs [block] inside a refresh cycle only if nothing at all is in flight, returning its result,
     * or returns `null` without running it when the center is not [ActivitySnapshot.idle].
     *
     * The idle check and the cycle-count increment are one [MutableStateFlow.compareAndSet], so two
     * concurrent callers can never both observe "idle" and both start: exactly one wins, the other
     * gets `null`. A separate "check [activity], then call [trackRefreshCycle]" pair has a window
     * between the two where another caller can start too.
     */
    suspend fun <T> tryTrackRefreshCycle(block: suspend () -> T): T? {
        while (true) {
            val current = _activity.value
            if (!current.idle) return null
            if (_activity.compareAndSet(current, current.copy(refreshCycleCount = current.refreshCycleCount + 1))) {
                break
            }
        }
        try {
            return block()
        } finally {
            _activity.update { it.copy(refreshCycleCount = it.refreshCycleCount - 1) }
        }
    }

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
     * True while the toolbar's refresh control should show its spinner: a feed refresh is running,
     * or a refresh-then-sync cycle is in flight but not currently syncing (the gap between its
     * refresh and its sync, or a sync-first cycle before its refresh starts). The sync control
     * shows [syncing] on its own, so the two never spin together for the same phase.
     */
    val refreshIndicatorShown: Boolean get() = feedRefreshing || (refreshCycleRunning && !syncing)

    /**
     * True when nothing at all is in flight — no refresh, no sync, and no refresh-then-sync cycle
     * (which also covers the gap between a cycle's refresh and its sync). The gate for the refresh-all
     * / sync actions (toolbar buttons and app-menu items alike): each is blocked while the other
     * operation is in flight, since running both at once isn't supported.
     */
    val idle: Boolean get() = !feedRefreshing && !syncing && !refreshCycleRunning
}
