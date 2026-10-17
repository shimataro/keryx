package works.merc.keryx.app.domain

import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.isErr
import works.merc.keryx.app.core.valueOrNull

/**
 * How a finished feed refresh went, as data for a UI to word: how many new articles it fetched, and
 * whether every feed it covered failed. Emitted through [ActivityCenter.refreshOutcomes].
 */
data class RefreshOutcome(val newArticleCount: Int, val allFailed: Boolean)

/** The new-article total of a refresh's per-feed [results]; a failed feed counts as none. */
fun newArticleCount(results: Map<String, Result<Int>>): Int = results.values.sumOf { it.valueOrNull ?: 0 }

/**
 * Reports a finished refresh: an all-failed refresh becomes a (coalesced) notification-center
 * warning whose next action explains what to do, and every refresh that covered at least one feed
 * becomes a [RefreshOutcome] on [ActivityCenter.refreshOutcomes]. The one place both are decided, so
 * every refresh route (the cycle runner's refresh-then-sync, a single feed's refresh) reports alike.
 *
 * Call it only once the refresh's activity tracking has ended: a UI showing the outcome reads
 * [ActivityCenter.activity] alongside it, and must already see the refresh finished.
 */
internal suspend fun reportRefreshOutcome(
    results: Map<String, Result<Int>>,
    activityCenter: ActivityCenter,
    notificationCenter: NotificationCenter,
    clock: Clock,
) {
    if (results.isEmpty()) return
    val allFailed = results.values.all { it.isErr }
    if (allFailed) {
        notificationCenter.addCoalescing(
            AppNotification(
                id = IdGenerator.newId(),
                level = AppNotificationLevel.WARNING,
                text = NotificationText.RefreshFailed,
                timestampMillis = clock.nowMillis(),
                action = AppNotificationAction.ShowInfoDialog(InfoDialogText.REFRESH_FAILED),
            ),
        )
    }
    activityCenter.emitRefreshOutcome(RefreshOutcome(newArticleCount(results), allFailed))
}
