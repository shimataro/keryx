package works.merc.keryx.app.domain

import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.core.NotificationText

class NotificationCenterTest {
    private fun notification(id: String, text: NotificationText = NotificationText.AppTranslocated) = AppNotification(
        id = id,
        level = AppNotificationLevel.WARNING,
        text = text,
        timestampMillis = 0L,
    )

    @Test
    fun addAppendsNewestFirst() {
        val center = NotificationCenter()

        center.add(notification("n1"))
        center.add(notification("n2"))

        assertEquals(listOf("n2", "n1"), center.items.value.map { it.id })
    }

    @Test
    fun dismissRemovesOnlyMatchingNotification() {
        val center = NotificationCenter()
        center.add(notification("n1"))
        center.add(notification("n2"))
        center.add(notification("n3"))

        center.dismiss("n2")

        assertEquals(listOf("n3", "n1"), center.items.value.map { it.id })
    }

    @Test
    fun dismissWithUnknownIdIsNoOp() {
        val center = NotificationCenter()
        center.add(notification("n1"))

        center.dismiss("missing")

        assertEquals(listOf("n1"), center.items.value.map { it.id })
    }

    @Test
    fun dismissAllClearsEverything() {
        val center = NotificationCenter()
        center.add(notification("n1"))
        center.add(notification("n2"))

        center.dismissAll()

        assertTrue(center.items.value.isEmpty())
    }

    @Test
    fun multipleAddsPreserveNewestFirstOrder() {
        val center = NotificationCenter()

        listOf("a", "b", "c", "d").forEach { center.add(notification(it)) }

        assertEquals(listOf("d", "c", "b", "a"), center.items.value.map { it.id })
    }

    @Test
    fun addCoalescingReplacesDuplicateAndMovesToTopWithLatestTimestamp() {
        val center = NotificationCenter()
        center.add(notification("other", text = NotificationText.FeedGone("other")))
        center.addCoalescing(
            AppNotification("s1", AppNotificationLevel.ERROR, text = NotificationText.SyncFailed(ErrorKind.GENERIC), timestampMillis = 10L),
        )
        center.addCoalescing(
            AppNotification("s2", AppNotificationLevel.ERROR, text = NotificationText.SyncFailed(ErrorKind.GENERIC), timestampMillis = 20L),
        )

        // The duplicate sync error collapses to a single entry (the newest), moved to the top;
        // the unrelated notification remains.
        assertEquals(listOf("s2", "other"), center.items.value.map { it.id })
        assertEquals(20L, center.items.value.first().timestampMillis)
    }

    @Test
    fun addCoalescingKeepsNotificationsThatDifferInLevelMessageOrAction() {
        val center = NotificationCenter()
        center.addCoalescing(
            AppNotification("a", AppNotificationLevel.ERROR, text = NotificationText.SyncFailed(ErrorKind.CLOUD_AUTH), timestampMillis = 0L),
        )
        // Different text → not coalesced.
        center.addCoalescing(
            AppNotification("b", AppNotificationLevel.ERROR, text = NotificationText.SyncFailed(ErrorKind.CLOUD_STORAGE), timestampMillis = 0L),
        )
        // Same text but different level → not coalesced.
        center.addCoalescing(
            AppNotification("c", AppNotificationLevel.WARNING, text = NotificationText.SyncFailed(ErrorKind.CLOUD_AUTH), timestampMillis = 0L),
        )
        // Same level+text but a distinct action → not coalesced.
        center.addCoalescing(
            AppNotification(
                "d", AppNotificationLevel.ERROR, text = NotificationText.SyncFailed(ErrorKind.CLOUD_AUTH), timestampMillis = 0L,
                action = AppNotificationAction.ResetCloudData,
            ),
        )

        assertEquals(listOf("d", "c", "b", "a"), center.items.value.map { it.id })
    }
}
