package works.merc.keryx.app.tray

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TrayActionPolicyTest {
    @Test
    fun `hides on a visible focused window with no recent notification`() {
        assertTrue(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowFocused = true,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    @Test
    fun `activates instead of hiding when a notification landed just inside the recency window`() {
        assertFalse(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowFocused = true,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 99_000L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    @Test
    fun `hides again once the notification is exactly at the recency boundary`() {
        assertTrue(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowFocused = true,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 95_000L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    @Test
    fun `hides when never notified even though the timestamp default is zero`() {
        assertTrue(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowFocused = true,
                nowMillis = 1_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    @Test
    fun `never hides a window that is not visible, regardless of notification recency`() {
        assertFalse(
            shouldHideOnTrayAction(
                windowVisible = false,
                windowFocused = true,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            ),
        )
    }

    @Test
    fun `never hides a visible window that is not focused, regardless of notification recency`() {
        assertFalse(
            shouldHideOnTrayAction(
                windowVisible = true,
                windowFocused = false,
                nowMillis = 100_000L,
                lastNotificationSentAtMillis = 0L,
                recencyWindowMs = 5_000L,
            ),
        )
    }
}
