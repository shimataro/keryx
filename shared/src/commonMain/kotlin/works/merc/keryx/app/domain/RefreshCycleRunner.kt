package works.merc.keryx.app.domain

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.local.db.Feeds

/**
 * The one implementation of the "refresh feeds → new-article notification → cloud sync" cycle,
 * shared by every caller that runs it: the manual refresh / pull-to-refresh in `HomeViewModel`,
 * desktop's `backgroundUpdateLoop`, Android's `FeedRefreshWorker`, and [runStartupMaintenance]
 * (which syncs first and refreshes second, see [CycleOrder.SYNC_THEN_REFRESH]).
 *
 * Every run is wrapped in one [ActivityCenter] refresh cycle, and its refresh in
 * [ActivityCenter.trackFeedRefresh], so the gap between the refresh and the sync still counts as
 * busy for anything consulting [ActivitySnapshot.idle]. The sync only runs while a cloud provider
 * is connected ([CloudSession.isConnected]).
 *
 * The runner does no dispatcher switching of its own: it runs in the caller's context, so a caller
 * on the UI thread must move it off (the refresh, the sync, and [CloudSession.isConnected] all do
 * blocking IO).
 */
class RefreshCycleRunner(
    private val activityCenter: ActivityCenter,
    private val feedRepository: FeedRepository,
    private val syncRepository: SyncRepository,
    private val cloudSession: CloudSession,
    private val newArticleNotifier: NewArticleNotifier,
    private val settingsRepository: SettingsRepository,
    private val notificationMessages: NotificationMessages,
) {
    /** Which of a cycle's two stages runs first. */
    enum class CycleOrder { REFRESH_THEN_SYNC, SYNC_THEN_REFRESH }

    /** What [runIfIdle] did. */
    sealed interface CycleOutcome {
        /** Something was already in flight ([ActivitySnapshot.idle] was false); nothing was started. */
        data object Busy : CycleOutcome

        /** A feed / folder / tag selection covered no subscribed feed; nothing was fetched or synced. */
        data object NothingToRefresh : CycleOutcome

        /** The cycle ran. [syncResult] is the sync's result, or `null` when no provider is connected. */
        data class Ran(val syncResult: Result<Unit>?) : CycleOutcome
    }

    /**
     * Runs one cycle unconditionally (even if other work is in flight — the background and startup
     * callers have their own scheduling): refreshes the feeds [filter] covers
     * ([FeedRepository.feedsCoveredBy]), posts the new-article notification when enabled, and syncs
     * with [trigger] if a provider is connected — in the given [order].
     *
     * [step] wraps each stage (named `"feedRefresh"` and `"sync"`), so a caller can isolate each
     * stage's failure the way [runMaintenanceStep] does; the default runs the stage as-is.
     *
     * @return The sync's result, or `null` when no provider was connected (or the sync stage threw
     *   and [step] swallowed it).
     */
    suspend fun run(
        filter: ArticleFilter = ArticleFilter.All,
        order: CycleOrder = CycleOrder.REFRESH_THEN_SYNC,
        trigger: SyncTrigger,
        step: suspend (name: String, block: suspend () -> Unit) -> Unit = { _, block -> block() },
    ): Result<Unit>? = activityCenter.trackRefreshCycle {
        runStages(order, trigger, step) { feedRepository.feedsCoveredBy(filter) }
    }

    /**
     * The manual entry point: runs a [CycleOrder.REFRESH_THEN_SYNC] cycle for [filter] only if
     * nothing at all is in flight, checked and claimed atomically
     * ([ActivityCenter.tryTrackRefreshCycle]), so two concurrent callers never both start one.
     *
     * The feeds [filter] covers are resolved once, up front: a [ArticleFilter.Feed] /
     * [ArticleFilter.Folder] / [ArticleFilter.Tag] selection covering no subscribed feed returns
     * [CycleOutcome.NothingToRefresh] without starting a cycle or syncing. [ArticleFilter.All] /
     * [ArticleFilter.Starred] always run, so a sync still happens with no feeds subscribed.
     */
    suspend fun runIfIdle(filter: ArticleFilter, trigger: SyncTrigger): CycleOutcome {
        val targets = feedRepository.feedsCoveredBy(filter)
        val coversEveryFeed = filter == ArticleFilter.All || filter == ArticleFilter.Starred
        if (!coversEveryFeed && targets.isEmpty()) return CycleOutcome.NothingToRefresh
        // The outcome itself is the block's result, so a `null` sync result (not connected) stays
        // distinguishable from tryTrackRefreshCycle's own `null` ("busy").
        return activityCenter.tryTrackRefreshCycle<CycleOutcome> {
            CycleOutcome.Ran(runStages(CycleOrder.REFRESH_THEN_SYNC, trigger, { _, block -> block() }) { targets })
        } ?: CycleOutcome.Busy
    }

    private suspend fun runStages(
        order: CycleOrder,
        trigger: SyncTrigger,
        step: suspend (String, suspend () -> Unit) -> Unit,
        targets: () -> List<Feeds>,
    ): Result<Unit>? {
        var syncResult: Result<Unit>? = null
        val refresh: suspend () -> Unit = { step("feedRefresh") { refreshAndNotify(targets()) } }
        val sync: suspend () -> Unit = {
            step("sync") { if (cloudSession.isConnected()) syncResult = syncRepository.sync(trigger) }
        }
        when (order) {
            CycleOrder.REFRESH_THEN_SYNC -> { refresh(); sync() }
            CycleOrder.SYNC_THEN_REFRESH -> { sync(); refresh() }
        }
        return syncResult
    }

    private suspend fun refreshAndNotify(targets: List<Feeds>) {
        val results = activityCenter.trackFeedRefresh { feedRepository.refreshFeeds(targets) }
        newArticleNotifier.notifyIfEnabled(
            results, settingsRepository.getLocalSettings().notificationEnabled, notificationMessages,
        )
    }
}
