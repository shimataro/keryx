package works.merc.keryx.app.domain

import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class NotificationCenterTest {
    private fun notification(id: String, message: String = "msg") = AppNotification(
        id = id,
        level = AppNotificationLevel.WARNING,
        message = message,
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
}
