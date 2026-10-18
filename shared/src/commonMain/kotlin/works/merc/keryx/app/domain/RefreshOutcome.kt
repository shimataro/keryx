package works.merc.keryx.app.domain

import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FeedFetchException
import works.merc.keryx.app.core.FeedTimeoutException
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.errorOrNull
import works.merc.keryx.app.core.valueOrNull

/**
 * How a finished feed refresh went, as data for a UI to word: how many new articles it fetched, and
 * whether it could reach none of the feeds it covered ([isUnreachable]). Returned by
 * [RefreshCycleRunner]'s manual entry points ([RefreshCycleRunner.runIfIdle],
 * [RefreshCycleRunner.refreshFeed]).
 */
data class RefreshOutcome(val newArticleCount: Int, val unreachable: Boolean) {
    /** Two refreshes as one: their new articles added up, unreachable only if neither reached a feed. */
    operator fun plus(other: RefreshOutcome): RefreshOutcome =
        RefreshOutcome(newArticleCount + other.newArticleCount, unreachable && other.unreachable)
}

/** The new-article total of a refresh's per-feed [results]; a failed feed counts as none. */
fun newArticleCount(results: Map<String, Result<Int>>): Int = results.values.sumOf { it.valueOrNull ?: 0 }

/**
 * True when every feed in [results] failed without reaching its server — a timeout, or a request that
 * got no response at all ([FeedFetchException.noResponse]): most likely the device is offline. A feed
 * that answered with an error (an HTTP status, 404/410, an unreadable body) is not counted as
 * unreachable, since checking the connection would not help; the feed list flags it instead, and a
 * 410 has a notification of its own.
 */
fun isUnreachable(results: Map<String, Result<Int>>): Boolean =
    results.isNotEmpty() && results.values.all { result ->
        when (val e = result.errorOrNull) {
            is FeedTimeoutException -> true
            is FeedFetchException -> e.noResponse
            else -> false
        }
    }

/**
 * Reports a finished refresh: an unreachable one ([isUnreachable]) becomes a (coalesced)
 * notification-center warning whose next action explains what to do, and every refresh that covered
 * at least one feed becomes the returned [RefreshOutcome] (`null` for one that covered none). The one
 * place both are decided, so every refresh route (the cycle runner's refresh-then-sync, a single
 * feed's refresh) reports alike.
 */
internal suspend fun reportRefreshOutcome(
    results: Map<String, Result<Int>>,
    notificationCenter: NotificationCenter,
    clock: Clock,
): RefreshOutcome? {
    if (results.isEmpty()) return null
    val unreachable = isUnreachable(results)
    if (unreachable) {
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
    return RefreshOutcome(newArticleCount(results), unreachable)
}
