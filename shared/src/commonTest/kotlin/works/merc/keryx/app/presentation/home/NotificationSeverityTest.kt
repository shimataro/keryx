package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.NotificationText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** [highestNotificationLevel] picks the color stage of the notification center's bell for both UIs. */
class NotificationSeverityTest {

    private fun notification(id: String, level: AppNotificationLevel) =
        AppNotification(id, level, NotificationText.AppTranslocated, timestampMillis = 0L)

    @Test
    fun noNotificationsHaveNoLevel() {
        assertNull(highestNotificationLevel(emptyList()))
    }

    @Test
    fun onlyInfoNotificationsAreInfo() {
        assertEquals(
            AppNotificationLevel.INFO,
            highestNotificationLevel(listOf(notification("a", AppNotificationLevel.INFO), notification("b", AppNotificationLevel.INFO))),
        )
    }

    @Test
    fun aWarningOutranksInfo() {
        assertEquals(
            AppNotificationLevel.WARNING,
            highestNotificationLevel(listOf(notification("a", AppNotificationLevel.INFO), notification("b", AppNotificationLevel.WARNING))),
        )
    }

    @Test
    fun anErrorOutranksEverythingWhereverItIs() {
        assertEquals(
            AppNotificationLevel.ERROR,
            highestNotificationLevel(
                listOf(
                    notification("b", AppNotificationLevel.WARNING),
                    notification("a", AppNotificationLevel.ERROR),
                    notification("c", AppNotificationLevel.INFO),
                ),
            ),
        )
    }
}
