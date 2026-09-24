package works.merc.keryx.app.ui.home

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.RefreshCycleRunner
import works.merc.keryx.app.domain.RefreshCycleRunner.CycleOutcome
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncTrigger

/**
 * The refresh / sync actions of [HomeViewModel] — toolbar and menu "refresh all", pull-to-refresh,
 * a single feed's refresh, and "sync now" — kept out of the ViewModel itself, which only delegates
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
 * @param repinSelected Re-trims the ViewModel's pinned read articles down to the current selection,
 *   before a refresh starts and again once it finishes (see `HomeViewModel.pinnedReadArticlesKeepingSelected`).
 */
internal class HomeRefreshController(
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher,
    private val runner: RefreshCycleRunner,
    private val feedRepository: FeedRepository,
    private val syncRepository: SyncRepository,
    private val activityCenter: ActivityCenter,
    private val currentFilter: () -> ArticleFilter,
    private val repinSelected: () -> Unit,
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

    /** Refreshes one feed. */
    fun refreshFeed(feed: Feeds) {
        scope.launch {
            withContext(dispatcher) { activityCenter.trackFeedRefresh { feedRepository.refreshFeed(feed) } }
        }
    }

    /**
     * Refreshes every feed, notifies about new articles when enabled, then syncs if connected —
     * unless anything is already in flight, in which case nothing is started.
     */
    fun refreshAll() {
        scope.launch { runCycle(ArticleFilter.All) }
    }

    /**
     * Pull-to-refresh on the article list: refreshes only the feeds the current selection covers
     * ([FeedRepository.feedsCoveredBy]), then syncs, exactly like [refreshAll] otherwise. A
     * selection that covers no subscribed feed finishes at once without fetching or syncing. When a
     * refresh or sync is already in flight, no new one is started; the pull instead waits for it,
     * keeping its filter in [pullRefreshingFilters] until everything in flight has finished. A
     * second pull on a selection whose pull is still pending is ignored; a pull on another
     * selection is tracked independently.
     */
    fun pullToRefresh() {
        val filter = currentFilter()
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

    /** Synchronizes local data with the cloud. */
    fun sync() {
        repinSelected()
        // IO off the UI thread: a sync writes the downloaded cloud DB to disk, runs the ATTACH
        // merge, VACUUM INTOs a snapshot and reads it all back.
        scope.launch {
            withContext(dispatcher) { syncRepository.sync() }
            repinSelected()
        }
    }

    /**
     * One [RefreshCycleRunner.runIfIdle] call for [filter], with the pinned-article re-trim around
     * it. Only the IO moves to [dispatcher]; the caller's coroutine stays on the ViewModel's
     * Main-confined scope, so the re-trims stay confined there too.
     */
    private suspend fun runCycle(filter: ArticleFilter): CycleOutcome {
        repinSelected()
        val outcome = withContext(dispatcher) { runner.runIfIdle(filter, SyncTrigger.MANUAL) }
        // Re-trim using the selection as it stands now: it may have changed while the cycle ran,
        // and the stale pre-refresh selection must not outlive it.
        if (outcome is CycleOutcome.Ran) repinSelected()
        return outcome
    }
}
