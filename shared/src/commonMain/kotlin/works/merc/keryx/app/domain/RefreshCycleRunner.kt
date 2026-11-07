package works.merc.keryx.app.domain

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.update
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
 * ([isUnreachable]), and the [RefreshOutcome] the manual entry points return for a UI to show —
 * as soon as its refresh stage has ended, before any sync that follows, so a UI showing a
 * refresh's progress ([runIfIdle]'s `onStarted`, [joinAutomaticCycle]) can show its result without
 * waiting for the sync (`external-spec.md` §9 "Refresh feedback").
 *
 * An [SyncTrigger.AUTOMATIC] cycle skips its refresh while the OS's data-saving mode is on
 * ([automaticRefreshAllowed]); its sync stage is then subject to [SyncRepository]'s own network
 * gate. A [SyncTrigger.MANUAL] cycle always refreshes. A running automatic cycle can be joined by
 * a user action that finds it in flight ([joinAutomaticCycle]), so that action still reports a
 * result and still gets its new-article notification.
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
     * unless a user action joins it (see [refreshAndNotify]). The default (never in front) is for tests that do not exercise it.
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
     * The [SyncTrigger.AUTOMATIC] cycles now running, oldest first — see [joinAutomaticCycle]. Each
     * is added before a [run] claims the [ActivityCenter] (a [runIfIdle] once it has claimed it), and
     * removed after it has released it — by that same cycle alone, once it has ended (its sync
     * included; its [Cycle.refreshEnded] has completed earlier, at its refresh stage's end). So
     * when automatic cycles overlap, one that ends first leaves the others joinable; while a [run]
     * holds the center, it is always joinable, and a [runIfIdle]'s is not for the instant between its
     * claim and its publish.
     */
    private val runningAutomaticCycles = MutableStateFlow<List<Cycle>>(emptyList())

    /**
     * One cycle, manual or automatic. [refreshEnded] completes, exactly once, as soon as its refresh
     * stage has ended and been reported ([endRefresh]) — before the sync that may follow — with its
     * [RefreshOutcome] (`null` when it refreshed no feed, or failed before reporting one). For a
     * [SyncTrigger.AUTOMATIC] cycle, [notify] is whether its new-article notification is still to be
     * decided; a manual cycle never consults it.
     *
     * [refreshed] and [reported] are touched only by the cycle's own coroutine, sequentially (its
     * stages, then [reportingRefresh]'s `finally`), so they need no synchronization.
     */
    private class Cycle {
        val refreshEnded = CompletableDeferred<RefreshOutcome?>()
        val notify = MutableStateFlow<NotifyState>(NotifyState.Pending(userJoined = false))
        var refreshed: Map<String, Result<Int>>? = null
        var reported = false
    }

    /**
     * Where an automatic cycle's new-article notification stands. Advanced only with
     * [MutableStateFlow.getAndUpdate], so the cycle's own notification step and a joining user action
     * can never both post it, nor both skip it.
     */
    private sealed interface NotifyState {
        /** The refresh has not finished; [userJoined] is whether a user action has joined meanwhile. */
        data class Pending(val userJoined: Boolean) : NotifyState

        /** Held back while the app was in front, until a user action joins and posts it. */
        class HeldBack(val results: Map<String, Result<Int>>) : NotifyState

        /** Posted, or decided without needing a post. */
        data object Settled : NotifyState
    }

    /**
     * Joins the most recently started running [SyncTrigger.AUTOMATIC] cycle, for a [SyncTrigger.MANUAL] [runIfIdle] that
     * found it running ([CycleOutcome.Joined]) instead of starting nothing visible. Returns its outcome: the deferred
     * completes once that cycle's refresh stage has ended — not waiting for a sync that follows it, and
     * already complete when the cycle is past its refresh (syncing) — with its [RefreshOutcome] (`null`
     * when it refreshed no feed, or when it failed before reporting one). `null` when no automatic
     * cycle is running — including when what is in flight is a [SyncTrigger.MANUAL] cycle, which its
     * own caller already reports, so joining it would count it twice.
     *
     * Joining also makes the cycle the user's own for its new-article notification, which an
     * automatic cycle holds back while the app is in front: one joined before its refresh finishes
     * posts it as a manual one would, and one joined after it was held back posts it now. Joining
     * the same cycle again posts nothing more. See `external-spec.md` §9 "Refresh feedback".
     *
     * A cycle whose refresh already ended unreachable has long since posted its notification-center
     * warning, which the user may have dismissed while it synced. The joining action shows that
     * failure as its own result, so the warning — the way to its explanation — is posted again here,
     * coalesced with whatever is still there. A cycle still refreshing posts it when it ends.
     */
    internal suspend fun joinAutomaticCycle(): Deferred<RefreshOutcome?>? {
        val cycle = runningAutomaticCycles.value.lastOrNull() ?: return null
        val before = cycle.notify.getAndUpdate {
            when (it) {
                is NotifyState.Pending -> NotifyState.Pending(userJoined = true)
                is NotifyState.HeldBack -> NotifyState.Settled
                NotifyState.Settled -> it
            }
        }
        if (before is NotifyState.HeldBack) postNewArticles(before.results)
        val ended = cycle.refreshEnded
        // Already complete here, so this never suspends; it is only read once the refresh has ended.
        if (ended.isCompleted && ended.await()?.unreachable == true) reportRefreshFailed(notificationCenter, clock)
        return ended
    }

    /**
     * The running [SyncTrigger.AUTOMATIC] cycle's outcome without joining it, for tests observing
     * what [joinAutomaticCycle] would join.
     */
    internal fun joinableAutomaticCycle(): Deferred<RefreshOutcome?>? = runningAutomaticCycles.value.lastOrNull()?.refreshEnded

    /** Which of a cycle's two stages runs first. */
    enum class CycleOrder { REFRESH_THEN_SYNC, SYNC_THEN_REFRESH }

    /** What [runIfIdle] did. */
    sealed interface CycleOutcome {
        /** Something was already in flight ([ActivitySnapshot.idle] was false); nothing was started. */
        data object Busy : CycleOutcome

        /**
         * A [SyncTrigger.MANUAL] call found an automatic cycle in flight and joined it
         * ([joinAutomaticCycle]) instead of starting one; [cycle] completes with that cycle's refresh
         * outcome once its refresh stage has ended (see [joinAutomaticCycle]).
         */
        data class Joined(val cycle: Deferred<RefreshOutcome?>) : CycleOutcome

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
    ): Result<Unit>? = reportingRefresh(trigger) { claimed, cycle ->
        // Published before the claim, so a busy check that sees this cycle can always join it.
        claimed()
        activityCenter.trackRefreshCycle {
            runStages(order, trigger, cycle, step) { feedRepository.feedsCoveredBy(filter) }
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
     * for [CycleOutcome.Busy], [CycleOutcome.Joined] or [CycleOutcome.NothingToRefresh] — on the
     * caller's context. It is handed the cycle's refresh-stage end: a deferred that completes, exactly
     * once and even when the cycle fails or is cancelled, as soon as the refresh stage has ended and
     * been reported, before the sync that follows, with the same [RefreshOutcome] the returned
     * [CycleOutcome.Ran.refresh] carries. A UI uses it to end a refresh's progress without waiting
     * for the sync.
     *
     * A [SyncTrigger.MANUAL] call that finds the center busy joins the automatic cycle running there
     * in the same step ([CycleOutcome.Joined]), so no hand-off can let that cycle end unseen in
     * between. Finding none to join, it claims again if the center has gone idle meanwhile (what was
     * in flight ended, so starting is no longer starting alongside it), and otherwise returns
     * [CycleOutcome.Busy] (a sync alone, or another manual cycle). An automatic call never joins.
     */
    suspend fun runIfIdle(
        filter: ArticleFilter,
        trigger: SyncTrigger,
        onStarted: (refreshEnded: Deferred<RefreshOutcome?>) -> Unit = {},
    ): CycleOutcome {
        val targets = feedRepository.feedsCoveredBy(filter)
        val coversEveryFeed = filter == ArticleFilter.All || filter == ArticleFilter.Starred
        if (!coversEveryFeed && targets.isEmpty()) return CycleOutcome.NothingToRefresh
        while (true) {
            // The outcome itself is the block's result, so a `null` sync result (not connected) stays
            // distinguishable from tryTrackRefreshCycle's own `null` ("busy").
            val (ran, refresh) = reportingRefresh(trigger) { claimed, cycle ->
                activityCenter.tryTrackRefreshCycle {
                    claimed()
                    onStarted(cycle.refreshEnded)
                    CycleOutcome.Ran(
                        runStages(CycleOrder.REFRESH_THEN_SYNC, trigger, cycle, { _, block -> block() }) {
                            targets
                        },
                    )
                }
            }
            if (ran != null) return ran.copy(refresh = refresh)
            if (trigger != SyncTrigger.MANUAL) return CycleOutcome.Busy
            joinAutomaticCycle()?.let { return CycleOutcome.Joined(it) }
            if (!activityCenter.activity.value.idle) return CycleOutcome.Busy
        }
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
     * Runs [block] with a fresh [Cycle], whose refresh stage [runStages] reports ([endRefresh]) as it
     * ends. Once [block] has ended — even when it throws or is cancelled, or skipped or never reached
     * its refresh — reports it here instead if that has not happened yet, so a failing stage cannot
     * lose its results and [Cycle.refreshEnded] always completes exactly once (a joiner or `onStarted`
     * caller never waits forever). Returns [block]'s value with the reported outcome.
     *
     * [block] calls its first callback to publish its cycle — once it has claimed it in the
     * [ActivityCenter], or just before an unconditional claim; for a [SyncTrigger.AUTOMATIC]
     * [trigger] that publishes the cycle for [joinAutomaticCycle] until [block] has ended. A block
     * that never claims (busy) and so never calls it publishes nothing.
     */
    private suspend fun <T> reportingRefresh(
        trigger: SyncTrigger,
        block: suspend (claimed: () -> Unit, cycle: Cycle) -> T,
    ): Pair<T, RefreshOutcome?> {
        val cycle = Cycle()
        val automatic = trigger == SyncTrigger.AUTOMATIC
        val value = try {
            block({ if (automatic) runningAutomaticCycles.update { it + cycle } }, cycle)
        } finally {
            try {
                endRefresh(cycle)
            } finally {
                if (automatic) runningAutomaticCycles.update { it - cycle }
            }
        }
        // endRefresh has completed it by now, so this never suspends.
        return value to cycle.refreshEnded.await()
    }

    /**
     * Reports [cycle]'s refresh ([reportRefreshOutcome]) and completes [Cycle.refreshEnded] with the
     * outcome — once: a second call does nothing. Completes it with `null` if the report throws.
     */
    private suspend fun endRefresh(cycle: Cycle) {
        if (cycle.reported) return
        cycle.reported = true
        var outcome: RefreshOutcome? = null
        try {
            outcome = cycle.refreshed?.let { reportRefreshOutcome(it, notificationCenter, clock) }
        } finally {
            cycle.refreshEnded.complete(outcome)
        }
    }

    private suspend fun runStages(
        order: CycleOrder,
        trigger: SyncTrigger,
        cycle: Cycle,
        step: suspend (String, suspend () -> Unit) -> Unit,
        targets: () -> List<Feeds>,
    ): Result<Unit>? {
        var syncResult: Result<Unit>? = null
        val refresh: suspend () -> Unit = {
            if (trigger == SyncTrigger.AUTOMATIC && !automaticRefreshAllowed()) {
                Log.info(TAG, "Automatic feed refresh skipped: the OS is saving data")
            } else {
                step("feedRefresh") { refreshAndNotify(targets(), trigger, cycle) }
            }
            // The refresh stage is over (a failure [step] swallowed included): report it now rather
            // than after the sync, so whoever shows its progress can end it. A failure [step] let
            // through is reported by reportingRefresh's finally instead.
            endRefresh(cycle)
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
     * Records the results in [cycle] before notifying, so a failing notification cannot lose them.
     *
     * The new-article OS notification is posted for a [SyncTrigger.MANUAL] cycle always, and for a
     * [SyncTrigger.AUTOMATIC] one only while the app is not in front ([AppForeground]) or once a user
     * action has joined it ([joinAutomaticCycle]): a user looking at the app already sees the new
     * articles land (the unread counts and the new-articles pill), so a notification about a refresh
     * they did not ask for would only interrupt them. One held back is left in [cycle] for a later
     * joiner to post. See `external-spec.md` §9 "Refresh feedback".
     */
    private suspend fun refreshAndNotify(targets: List<Feeds>, trigger: SyncTrigger, cycle: Cycle) {
        val results = activityCenter.trackFeedRefresh { feedRepository.refreshFeeds(targets) }
        cycle.refreshed = results
        val joined = NotifyState.Pending(userJoined = true)
        if (trigger == SyncTrigger.AUTOMATIC && appForeground.inForeground.value) {
            val before = cycle.notify.getAndUpdate { if (it == joined) NotifyState.Settled else NotifyState.HeldBack(results) }
            if (before != joined) {
                Log.info(TAG, "New-article notification held back: the app is in front")
                return
            }
        } else {
            cycle.notify.value = NotifyState.Settled
        }
        postNewArticles(results)
    }

    private suspend fun postNewArticles(results: Map<String, Result<Int>>) {
        newArticleNotifier.notifyIfEnabled(
            results, settingsRepository.getLocalSettings().notificationEnabled, notificationMessages,
        )
    }

    private companion object {
        const val TAG = "RefreshCycleRunner"
    }
}
