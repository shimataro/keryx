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
 *   before a refresh starts and again once it finishes (see `HomeViewModel.retrimPinnedReadStates`).
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
     * The selections whose pull-to-refresh ([pullToRefresh]) is still running — through the refresh
     * itself *and* the sync that follows it, or while it is waiting on an already in-flight
     * refresh/sync to finish. Keyed by the filter that was showing when the pull started, so the
     * indicator belongs to that list alone: switching to another selection mid-pull shows that
     * list as not refreshing, and switching back shows the pull again.
     *
     * Deliberately not derived from the [ActivityCenter] state: that would show the indicator on
     * every background refresh the user never asked for.
     */
    val pullRefreshingFilters: StateFlow<Set<ArticleFilter>> = _pullRefreshingFilters.asStateFlow()

    private val _manualRefreshStatus = MutableStateFlow<ManualRefreshStatus>(ManualRefreshStatus.Idle)

    /**
     * What the refreshes started here are doing — see [ManualRefreshStatus]. Updated with
     * [MutableStateFlow.update], since a cycle starts on [dispatcher] rather than on [scope].
     */
    val manualRefreshStatus: StateFlow<ManualRefreshStatus> = _manualRefreshStatus.asStateFlow()

    /**
     * The [ManualRefreshStatus.Finished.generation] source. A plain var: every end is counted from a
     * `finally` running on [scope], which is Main-confined.
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
     * ([FeedRepository.feedsCoveredBy]), then syncs, exactly like [refreshAll] otherwise. A
     * selection that covers no subscribed feed finishes at once without fetching or syncing. When a
     * refresh or sync is already in flight, no new one is started; the pull instead waits for it,
     * keeping its filter in [pullRefreshingFilters] until everything in flight has finished (and,
     * when that is an automatic cycle, reports its result — see [runCycle]). A
     * second pull on a selection whose pull is still pending is ignored; a pull on another
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
            try {
                if (runCycle(filter) == CycleOutcome.Busy) activityCenter.activity.first { it.idle }
            } finally {
                _pullRefreshingFilters.update { it - filter }
            }
        }
    }

    /**
     * One [RefreshCycleRunner.runIfIdle] call for [filter], with the pinned-article re-trim around
     * it. Only the IO moves to [dispatcher]; the caller's coroutine stays on the ViewModel's
     * Main-confined scope, so the re-trims stay confined there too.
     *
     * When the call finds work in flight ([CycleOutcome.Busy]) and that work is an automatic cycle
     * ([RefreshCycleRunner.joinAutomaticCycle]), the action joins it: [manualRefreshStatus]
     * counts it as one more refresh until that cycle has ended, then reports its outcome, and the
     * cycle posts its new-article notification as a manual one would, so the user's action still gets
     * a result (`external-spec.md` §9 "Refresh feedback"). Anything else in
     * flight — a sync alone, or another manual refresh already counted — leaves the status alone.
     */
    private suspend fun runCycle(filter: ArticleFilter): CycleOutcome {
        retrimPins()
        // A cycle that never starts (busy, or nothing to refresh) leaves the status alone.
        var started = false
        var outcome: CycleOutcome? = null
        try {
            outcome = withContext(dispatcher) {
                runner.runIfIdle(filter, SyncTrigger.MANUAL) {
                    started = true
                    _manualRefreshStatus.update { it.started() }
                }
            }
        } finally {
            if (started) {
                val generation = ++endCount
                _manualRefreshStatus.update { it.ended((outcome as? CycleOutcome.Ran)?.refresh, generation) }
            }
        }
        if (outcome == CycleOutcome.Busy) runner.joinAutomaticCycle()?.let { joinAutomaticCycle(it) }
        // Re-trim using the selection as it stands now: it may have changed while the cycle ran,
        // and the stale pre-refresh selection must not outlive it.
        if (outcome is CycleOutcome.Ran) retrimPins()
        return outcome
    }

    /**
     * The automatic cycles [joinAutomaticCycle] is currently counting. Main-confined like [endCount].
     */
    private val joinedCycles = mutableSetOf<Deferred<RefreshOutcome?>>()

    /**
     * Counts the automatic cycle behind [cycle] in [manualRefreshStatus] until it has ended, then
     * reports its outcome. A cycle already joined (a pull, then Refresh All, during the same automatic
     * cycle) is counted once, so its new-article count is not doubled. Runs on [scope], like every
     * other start and end, so [endCount] stays Main-confined; awaiting does no IO, so nothing moves
     * to [dispatcher].
     */
    private suspend fun joinAutomaticCycle(cycle: Deferred<RefreshOutcome?>) {
        if (!joinedCycles.add(cycle)) return
        _manualRefreshStatus.update { it.started() }
        var refresh: RefreshOutcome? = null
        try {
            refresh = cycle.await()
        } finally {
            joinedCycles.remove(cycle)
            val generation = ++endCount
            _manualRefreshStatus.update { it.ended(refresh, generation) }
        }
    }
}
