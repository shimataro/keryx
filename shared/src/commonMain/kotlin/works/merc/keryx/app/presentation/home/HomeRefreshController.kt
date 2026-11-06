package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ActivitySnapshot
import works.merc.keryx.app.domain.RefreshCycleRunner
import works.merc.keryx.app.domain.RefreshOutcome
import works.merc.keryx.app.domain.RefreshCycleRunner.CycleOutcome
import works.merc.keryx.app.domain.SyncTrigger

/**
 * The refresh actions of [HomeViewModel] — toolbar and menu "refresh all", pull-to-refresh, and a
 * single feed's refresh — kept out of the ViewModel itself, which only delegates
 * to this. A plain class the ViewModel creates and owns, not a Koin binding: it runs on the
 * ViewModel's own [scope] and dies with it.
 *
 * Every refresh-then-sync goes through [RefreshCycleRunner.runIfIdle], which checks for and claims
 * an idle [ActivityCenter] in one atomic step, so there is exactly one busy check per action and no
 * window between checking and starting.
 *
 * @param scope The ViewModel's scope (Main-confined), where every state write here happens.
 * @param dispatcher Where the blocking work (feed resolution, fetch, sync) runs.
 * @param currentFilter The article list's current selection, read when a pull starts.
 * @param retrimPins Re-trims the ViewModel's read-state pins to its unread pins plus the selection,
 *   before a cycle starts and again once its refresh stage has ended — not after the sync that
 *   follows (see `HomeViewModel.retrimPinnedReadStates`).
 */
internal class HomeRefreshController(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val runner: RefreshCycleRunner,
    private val activityCenter: ActivityCenter,
    private val currentFilter: () -> ArticleFilter,
    private val retrimPins: () -> Unit,
) {
    private val _pullRefreshingFilters = MutableStateFlow<Set<ArticleFilter>>(emptySet())

    /**
     * The selections whose pull-to-refresh ([pullToRefresh]) is still refreshing — until the refresh
     * stage of the cycle it started or joined has ended, not through the sync that follows it (the
     * sync control shows that), or while it is waiting on another refresh already in flight to
     * finish. Keyed by the filter that was showing when the pull started, so the
     * indicator belongs to that list alone: switching to another selection mid-pull shows that
     * list as not refreshing, and switching back shows the pull again.
     *
     * Deliberately not derived from the [ActivityCenter] state: that would show the indicator on
     * every background refresh the user never asked for.
     */
    val pullRefreshingFilters: StateFlow<Set<ArticleFilter>> = _pullRefreshingFilters.asStateFlow()

    private val _manualRefreshStatus = MutableStateFlow<ManualRefreshStatus>(ManualRefreshStatus.Idle)

    /**
     * What the refreshes started here are doing — see [ManualRefreshStatus]. Every start and end is
     * written on [scope].
     */
    val manualRefreshStatus: StateFlow<ManualRefreshStatus> = _manualRefreshStatus.asStateFlow()

    /**
     * The [ManualRefreshStatus.Finished.generation] source. A plain var: every end is counted from a
     * `finally` running on [scope], which is Main-confined ([refreshFeed], [trackRefresh]).
     */
    private var endCount = 0L

    /** Refreshes one feed, by id — see [RefreshCycleRunner.refreshFeed]. */
    fun refreshFeed(feedId: String) {
        scope.launch {
            _manualRefreshStatus.update { it.started() }
            var outcome: RefreshOutcome? = null
            try {
                outcome = withContext(dispatcher) { runner.refreshFeed(feedId) }
            } finally {
                // Numbered outside update(), so a retried update stamps the same generation.
                val generation = ++endCount
                _manualRefreshStatus.update { it.ended(outcome, generation) }
            }
        }
    }

    /**
     * Refreshes every feed, notifies about new articles when enabled, then syncs if connected —
     * unless anything is already in flight, in which case nothing is started (when that is an
     * automatic cycle, its result is reported instead — see [runCycle]).
     */
    fun refreshAll() {
        scope.launch { runCycle(ArticleFilter.All) }
    }

    /**
     * Pull-to-refresh on the article list: refreshes only the feeds the current selection covers
     * ([FeedRepository.feedsCoveredBy]), then syncs, exactly like [refreshAll] otherwise. The pull
     * keeps its filter in [pullRefreshingFilters] until the refresh stage of its cycle has ended; the
     * cycle's sync carries on after that. A selection that covers no subscribed feed finishes at once
     * without fetching or syncing. When something is already in flight, no new cycle is started:
     * an automatic cycle is joined, and the pull ends with that cycle's refresh stage (reporting its
     * result — see [runCycle]); otherwise ([CycleOutcome.Busy]) the pull ends once no refresh is
     * shown in progress ([ActivitySnapshot.refreshIndicatorShown]) — at once when a sync alone is
     * running. A second pull on a selection whose pull is still pending is ignored; a pull on another
     * selection is tracked independently.
     */
    fun pullToRefresh() = pullToRefresh(currentFilter())

    /**
     * [pullToRefresh] for the feeds [filter] covers rather than the current selection — the iOS
     * sidebar's pull, which is not tied to the article list's selection and refreshes every feed
     * ([ArticleFilter.All]). The pull is tracked under [filter] in [pullRefreshingFilters].
     */
    fun pullToRefresh(filter: ArticleFilter) {
        var added = false
        _pullRefreshingFilters.update { current ->
            added = filter !in current
            current + filter
        }
        if (!added) return
        scope.launch {
            // Released once: when a started cycle's refresh stage ends (it then goes on to sync), or
            // otherwise once runCycle and the wait below have returned.
            var released = false
            fun release() {
                if (released) return
                released = true
                _pullRefreshingFilters.update { it - filter }
            }
            try {
                val outcome = runCycle(filter) { refreshEnded ->
                    scope.launch {
                        try {
                            refreshEnded.join()
                        } finally {
                            release()
                        }
                    }
                }
                // What is in flight is a sync alone (released at once), or another manual cycle,
                // whose refresh the pull waits out. A joined cycle's refresh stage has already ended
                // by the time runCycle returns.
                if (outcome == CycleOutcome.Busy) activityCenter.activity.first { !it.refreshIndicatorShown }
            } finally {
                release()
            }
        }
    }

    /**
     * One [RefreshCycleRunner.runIfIdle] call for [filter], with the pinned-article re-trim around
     * its refresh stage: once before the call, and once more, for a cycle that started, as soon as
     * its refresh stage has ended. Only the IO moves to [dispatcher]; both re-trims run on the
     * ViewModel's Main-confined [scope]. Returns once the whole cycle, sync included, has ended.
     *
     * The second re-trim does not wait for the sync: the refresh looks finished to the user by then
     * (the pull indicator and the status have ended), so an article read while the sync is still
     * running keeps its pin like any other read in Unread mode. A pin whose article the sync's merge
     * tombstones is dropped by the ViewModel's reconcile, which the merge's `articles` change signal
     * triggers.
     *
     * [manualRefreshStatus] counts the cycle from its start until its refresh stage has ended — not
     * through the sync that follows, whose progress the sync control shows — then reports its
     * outcome ([trackRefresh]). [onRefreshStarted] is handed that same refresh-stage end, on [scope],
     * for a cycle that started (a pull's indicator ends with it).
     *
     * When the call finds an automatic cycle in flight, it joins it in the same step
     * ([CycleOutcome.Joined]) and tracks that cycle's refresh stage the same way — returning once it
     * has ended (at once if the cycle is already syncing) — and the cycle posts
     * its new-article notification as a manual one would, so the user's action still gets a result
     * (`external-spec.md` §9 "Refresh feedback"). Anything else in flight ([CycleOutcome.Busy]: a sync
     * alone, or another manual refresh already counted) leaves the status alone.
     */
    private suspend fun runCycle(
        filter: ArticleFilter,
        onRefreshStarted: (Deferred<RefreshOutcome?>) -> Unit = {},
    ): CycleOutcome {
        retrimPins()
        val outcome = withContext(dispatcher) {
            runner.runIfIdle(filter, SyncTrigger.MANUAL) { refreshEnded ->
                // Called on dispatcher: hand off to scope, so the status writes and endCount stay
                // Main-confined. refreshEnded completes exactly once whatever happens to the cycle
                // (failure, cancellation), so the started() always gets its ended().
                scope.launch {
                    onRefreshStarted(refreshEnded)
                    trackRefresh(refreshEnded)
                    // Re-trim using the selection as it stands now: it may have changed while the
                    // refresh ran, and the stale pre-refresh selection must not outlive it. Right
                    // after the status has ended, with no suspension in between, so whoever sees the
                    // Finished status sees the re-trim done too.
                    retrimPins()
                }
            }
        }
        if (outcome is CycleOutcome.Joined) {
            trackRefresh(outcome.cycle)
            // Already tracked by an earlier action (it returned at once): still wait it out.
            outcome.cycle.join()
        }
        return outcome
    }

    /**
     * The refresh stages [trackRefresh] is currently counting. Main-confined like [endCount].
     */
    private val trackedRefreshes = mutableSetOf<Deferred<RefreshOutcome?>>()

    /**
     * Counts the cycle behind [refreshEnded] (started here, or an automatic one joined) in
     * [manualRefreshStatus] until its refresh stage has ended, then reports its outcome. One already
     * tracked (a pull, then Refresh All, during the same automatic cycle) is counted once, so its
     * new-article count is not doubled. Runs on [scope], like every other start and end, so
     * [endCount] stays Main-confined; awaiting does no IO, so nothing moves to [dispatcher].
     */
    private suspend fun trackRefresh(refreshEnded: Deferred<RefreshOutcome?>) {
        if (!trackedRefreshes.add(refreshEnded)) return
        _manualRefreshStatus.update { it.started() }
        var refresh: RefreshOutcome? = null
        try {
            refresh = refreshEnded.await()
        } finally {
            trackedRefreshes.remove(refreshEnded)
            val generation = ++endCount
            _manualRefreshStatus.update { it.ended(refresh, generation) }
        }
    }
}
