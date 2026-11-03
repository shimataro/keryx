package works.merc.keryx.app.domain

import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.FeedFetchException
import works.merc.keryx.app.core.FeedParseException
import works.merc.keryx.app.core.FeedTimeoutException
import works.merc.keryx.app.core.InfoDialogText
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RefreshOutcomeTest {
    private val timeout: Result<Int> = Result.Err(FeedTimeoutException())
    private val noResponse: Result<Int> = Result.Err(FeedFetchException("no response", noResponse = true))

    @Test
    fun isUnreachableIsFalseForEmptyResults() {
        assertFalse(isUnreachable(emptyMap()))
    }

    @Test
    fun isUnreachableIsFalseWhenAnyFeedSucceeded() {
        assertFalse(isUnreachable(mapOf("a" to Result.Ok(0), "b" to timeout)))
    }

    @Test
    fun isUnreachableIsTrueWhenAllFeedsTimedOut() {
        assertTrue(isUnreachable(mapOf("a" to timeout, "b" to timeout)))
    }

    @Test
    fun isUnreachableForFetchFailureDependsOnNoResponse() {
        assertTrue(isUnreachable(mapOf("a" to noResponse)))
        val withStatus: Result<Int> = Result.Err(FeedFetchException("server error", statusCode = 500))
        assertFalse(isUnreachable(mapOf("a" to withStatus)))
    }

    @Test
    fun isUnreachableIsFalseForOtherExceptionTypes() {
        assertFalse(isUnreachable(mapOf("a" to Result.Err(FeedParseException("bad")))))
        assertFalse(isUnreachable(mapOf("a" to Result.Err(CloudAuthException("auth")))))
    }

    @Test
    fun isUnreachableIsFalseForMixOfUnreachableAndOtherErrors() {
        assertFalse(isUnreachable(mapOf("a" to timeout, "b" to Result.Err(FeedParseException("bad")))))
    }

    @Test
    fun newArticleCountSumsOkValuesAndCountsErrAsZero() {
        assertEquals(0, newArticleCount(emptyMap()))
        assertEquals(5, newArticleCount(mapOf("a" to Result.Ok(2), "b" to timeout, "c" to Result.Ok(3))))
    }

    @Test
    fun plusAddsCountsAndIsUnreachableOnlyIfBoth() {
        val sum = RefreshOutcome(2, true) + RefreshOutcome(3, true)
        assertEquals(RefreshOutcome(5, true), sum)
        assertEquals(RefreshOutcome(5, false), RefreshOutcome(2, true) + RefreshOutcome(3, false))
        assertEquals(RefreshOutcome(5, false), RefreshOutcome(2, false) + RefreshOutcome(3, true))
        assertEquals(RefreshOutcome(5, false), RefreshOutcome(2, false) + RefreshOutcome(3, false))
    }

    @Test
    fun reportRefreshOutcomeReturnsNullAndNotifiesNothingForEmptyResults() = runTest {
        val center = NotificationCenter()

        val outcome = reportRefreshOutcome(emptyMap(), center, Clock { 1000L })

        assertNull(outcome)
        assertTrue(center.items.value.isEmpty())
    }

    @Test
    fun reportRefreshOutcomePostsCoalescedWarningWhenUnreachable() = runTest {
        val center = NotificationCenter()
        val clock = Clock { 1000L }

        val outcome = reportRefreshOutcome(mapOf("a" to timeout), center, clock)

        assertEquals(RefreshOutcome(0, true), outcome)
        val n = center.items.value.single()
        assertEquals(AppNotificationLevel.WARNING, n.level)
        assertEquals(NotificationText.RefreshFailed, n.text)
        assertEquals(AppNotificationAction.ShowInfoDialog(InfoDialogText.REFRESH_FAILED), n.action)
        assertEquals(1000L, n.timestampMillis)

        reportRefreshOutcome(mapOf("b" to noResponse), center, Clock { 2000L })
        val repeated = center.items.value.single()
        assertEquals(2000L, repeated.timestampMillis)
        assertNotEquals(n.id, repeated.id)
    }

    @Test
    fun reportRefreshOutcomeReturnsOutcomeWithoutNotificationWhenReachable() = runTest {
        val center = NotificationCenter()

        val outcome = reportRefreshOutcome(mapOf("a" to Result.Ok(4), "b" to timeout), center, Clock { 1000L })

        assertNotNull(outcome)
        assertEquals(RefreshOutcome(4, false), outcome)
        assertTrue(center.items.value.isEmpty())
    }
}
