package works.merc.keryx.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.Log
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
 * is connected ([CloudSession.isConnected]). Every run's refresh is reported through
 * [reportRefreshOutcome] — the notification-center warning when no feed could be reached
 * ([isUnreachable]), and the [RefreshOutcome] the manual entry points return for a UI to show.
 *
 * An [SyncTrigger.AUTOMATIC] cycle skips its refresh while the OS's data-saving mode is on
 * ([automaticRefreshAllowed]); its sync stage is then subject to [SyncRepository]'s own network
 * gate. A [SyncTrigger.MANUAL] cycle always refreshes. A running automatic cycle can be joined by
 * a user action that finds it in flight ([joinableAutomaticCycle]), so that action still reports a
 * result.
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
    private val notificationCenter: NotificationCenter,
    private val clock: Clock,
    /**
     * Whether the app is in front, which holds back an automatic cycle's new-article notification
     * (see [refreshAndNotify]). The default (never in front) is for tests that do not exercise it.
     */
    private val appForeground: AppForeground = AppForeground(),
    /**
     * Whether an automatic cycle may fetch feeds on the current network
     * ([NetworkUsagePolicy.automaticRefreshAllowed]). The default (always allowed) is for tests that
     * do not exercise the gate.
     */
    private val automaticRefreshAllowed: suspend () -> Boolean = { true },
) {
    /**
     * The outcome of the [SyncTrigger.AUTOMATIC] cycle now running, or `null` when none is — see
     * [joinableAutomaticCycle]. Set only once a cycle has claimed the [ActivityCenter], and cleared
     * (by that same cycle alone, so an overlapping one's is never cleared by another) just before it
     * is completed.
     */
    private val runningAutomaticCycle = MutableStateFlow<CompletableDeferred<RefreshOutcome?>?>(null)

    /**
     * The running [SyncTrigger.AUTOMATIC] cycle's outcome, for a user action that found it running
     * ([CycleOutcome.Busy]) and joins it instead of starting nothing visible: the deferred completes
     * once that whole cycle, sync included, has ended, with its [RefreshOutcome] (`null` when it
     * refreshed no feed, or when it failed before reporting one). `null` when no automatic cycle is
     * running — including when what is in flight is a [SyncTrigger.MANUAL] cycle, which its own
     * caller already reports, so joining it would count it twice. See `external-spec.md` §9
     * "Refresh feedback".
     */
    fun joinableAutomaticCycle(): Deferred<RefreshOutcome?>? = runningAutomaticCycle.value

    /** Which of a cycle's two stages runs first. */
    enum class CycleOrder { REFRESH_THEN_SYNC, SYNC_THEN_REFRESH }

    /** What [runIfIdle] did. */
    sealed interface CycleOutcome {
        /** Something was already in flight ([ActivitySnapshot.idle] was false); nothing was started. */
        data object Busy : CycleOutcome

        /** A feed / folder / tag selection covered no subscribed feed; nothing was fetched or synced. */
        data object NothingToRefresh : CycleOutcome

        /**
         * The cycle ran. [syncResult] is the sync's result, or `null` when no provider is connected;
         * [refresh] is how its refresh went, or `null` when it refreshed no feed.
         */
        data class Ran(val syncResult: Result<Unit>?, val refresh: RefreshOutcome? = null) : CycleOutcome
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
    ): Result<Unit>? = reportingRefresh(trigger) { onRefreshed, claimed ->
        activityCenter.trackRefreshCycle {
            claimed()
            runStages(order, trigger, step, onRefreshed) { feedRepository.feedsCoveredBy(filter) }
        }
    }.first

    /**
     * The manual entry point: runs a [CycleOrder.REFRESH_THEN_SYNC] cycle for [filter] only if
     * nothing at all is in flight, checked and claimed atomically
     * ([ActivityCenter.tryTrackRefreshCycle]), so two concurrent callers never both start one.
     *
     * The feeds [filter] covers are resolved once, up front: a [ArticleFilter.Feed] /
     * [ArticleFilter.Folder] / [ArticleFilter.Tag] selection covering no subscribed feed returns
     * [CycleOutcome.NothingToRefresh] without starting a cycle or syncing. [ArticleFilter.All] /
     * [ArticleFilter.Starred] always run, so a sync still happens with no feeds subscribed.
     *
     * [onStarted] is called once the cycle has claimed the idle center, before any of it runs — never
     * for [CycleOutcome.Busy] or [CycleOutcome.NothingToRefresh] — on the caller's context.
     */
    suspend fun runIfIdle(filter: ArticleFilter, trigger: SyncTrigger, onStarted: () -> Unit = {}): CycleOutcome {
        val targets = feedRepository.feedsCoveredBy(filter)
        val coversEveryFeed = filter == ArticleFilter.All || filter == ArticleFilter.Starred
        if (!coversEveryFeed && targets.isEmpty()) return CycleOutcome.NothingToRefresh
        // The outcome itself is the block's result, so a `null` sync result (not connected) stays
        // distinguishable from tryTrackRefreshCycle's own `null` ("busy").
        val (ran, refresh) = reportingRefresh(trigger) { onRefreshed, claimed ->
            activityCenter.tryTrackRefreshCycle {
                claimed()
                onStarted()
                CycleOutcome.Ran(
                    runStages(CycleOrder.REFRESH_THEN_SYNC, trigger, { _, block -> block() }, onRefreshed) { targets },
                )
            }
        }
        return ran?.copy(refresh = refresh) ?: CycleOutcome.Busy
    }

    /**
     * Refreshes the feed [feedId] names alone — no new-article notification and no sync, unlike a
     * cycle — and reports it like one ([reportRefreshOutcome]), returning how it went. The feed's row
     * is re-read first ([FeedRepository.refreshFeedById]); a feed unsubscribed in the meantime is
     * not fetched, and nothing is reported (`null`).
     */
    suspend fun refreshFeed(feedId: String): RefreshOutcome? {
        val result = activityCenter.trackFeedRefresh { feedRepository.refreshFeedById(feedId) } ?: return null
        return reportRefreshOutcome(mapOf(feedId to result), notificationCenter, clock)
    }

    /**
     * Runs [block], handing it the callback that records its refresh's per-feed results, and reports
     * those results ([reportRefreshOutcome]) once [block] has ended — even when it throws, so a
     * failing later stage cannot lose them. Returns [block]'s value with the reported outcome.
     *
     * [block] calls its second callback once it has claimed its cycle in the [ActivityCenter]; for a
     * [SyncTrigger.AUTOMATIC] [trigger] that publishes the cycle as [joinableAutomaticCycle], and the
     * reported outcome completes it once [block] has ended (`null` if [block] or the report threw), so
     * a joiner never waits forever. A block that never claims (busy) publishes nothing.
     */
    private suspend fun <T> reportingRefresh(
        trigger: SyncTrigger,
        block: suspend (onRefreshed: (Map<String, Result<Int>>) -> Unit, claimed: () -> Unit) -> T,
    ): Pair<T, RefreshOutcome?> {
        val joinable = if (trigger == SyncTrigger.AUTOMATIC) CompletableDeferred<RefreshOutcome?>() else null
        var refreshed: Map<String, Result<Int>>? = null
        var outcome: RefreshOutcome? = null
        val value = try {
            block({ refreshed = it }, { if (joinable != null) runningAutomaticCycle.value = joinable })
        } finally {
            try {
                outcome = refreshed?.let { reportRefreshOutcome(it, notificationCenter, clock) }
            } finally {
                if (joinable != null) {
                    runningAutomaticCycle.compareAndSet(joinable, null)
                    joinable.complete(outcome)
                }
            }
        }
        return value to outcome
    }

    private suspend fun runStages(
        order: CycleOrder,
        trigger: SyncTrigger,
        step: suspend (String, suspend () -> Unit) -> Unit,
        onRefreshed: (Map<String, Result<Int>>) -> Unit,
        targets: () -> List<Feeds>,
    ): Result<Unit>? {
        var syncResult: Result<Unit>? = null
        val refresh: suspend () -> Unit = {
            if (trigger == SyncTrigger.AUTOMATIC && !automaticRefreshAllowed()) {
                Log.info(TAG, "Automatic feed refresh skipped: the OS is saving data")
            } else {
                step("feedRefresh") { refreshAndNotify(targets(), trigger, onRefreshed) }
            }
        }
        val sync: suspend () -> Unit = {
            step("sync") { if (cloudSession.isConnected()) syncResult = syncRepository.sync(trigger) }
        }
        when (order) {
            CycleOrder.REFRESH_THEN_SYNC -> { refresh(); sync() }
            CycleOrder.SYNC_THEN_REFRESH -> {
                sync(); refresh()
                // Nothing syncs after this refresh, and a refresh schedules no sync of its own, so
                // what it just wrote (new articles, feed URL changes, error counts) would wait for
                // the next periodic cycle. One short-window sync picks it up.
                syncRepository.scheduleSync()
            }
        }
        return syncResult
    }

    /**
     * Hands the results to [onRefreshed] before notifying, so a failing notification cannot lose them.
     *
     * The new-article OS notification is posted for a [SyncTrigger.MANUAL] cycle always, and for a
     * [SyncTrigger.AUTOMATIC] one only while the app is not in front ([AppForeground]): a user looking
     * at the app already sees the new articles land (the unread counts and the new-articles pill), so
     * a notification about a refresh they did not ask for would only interrupt them. See
     * `external-spec.md` §9 "Refresh feedback".
     */
    private suspend fun refreshAndNotify(
        targets: List<Feeds>,
        trigger: SyncTrigger,
        onRefreshed: (Map<String, Result<Int>>) -> Unit,
    ) {
        val results = activityCenter.trackFeedRefresh { feedRepository.refreshFeeds(targets) }
        onRefreshed(results)
        if (trigger == SyncTrigger.AUTOMATIC && appForeground.inForeground.value) {
            Log.info(TAG, "New-article notification held back: the app is in front")
            return
        }
        newArticleNotifier.notifyIfEnabled(
            results, settingsRepository.getLocalSettings().notificationEnabled, notificationMessages,
        )
    }

    private companion object {
        const val TAG = "RefreshCycleRunner"
    }
}
