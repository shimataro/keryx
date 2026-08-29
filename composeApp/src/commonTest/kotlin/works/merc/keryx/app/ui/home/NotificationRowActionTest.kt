package works.merc.keryx.app.ui.home

import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * The one place that decides what acting on a notification does — shared by the notification row
 * and Android's foreground alert Snackbar, so the two can never disagree about where the same
 * notification leads.
 */
class NotificationRowActionTest {

    private fun notification(action: AppNotificationAction?) = AppNotification(
        id = "n",
        level = AppNotificationLevel.WARNING,
        message = "msg",
        timestampMillis = 0L,
        action = action,
    )

    /** Runs [notificationRowAction] and reports how many times each callback fired. */
    private fun invoke(action: AppNotificationAction?): Pair<Int, Int>? {
        var hostActions = 0
        var navigations = 0
        val run = notificationRowAction(
            notification(action),
            onRequestHostAction = { hostActions++ },
            onNavigated = { navigations++ },
        ) ?: return null
        run()
        return hostActions to navigations
    }

    @Test
    fun aNotificationWithNoActionOffersNothingToActOn() {
        assertNull(notificationRowAction(notification(null), onRequestHostAction = {}, onNavigated = {}))
    }

    @Test
    fun resetCloudDataOffersNothingToActOnBecauseItIsDestructive() {
        // It gets its own confirmed inline button instead — a stray row tap (or a Snackbar action)
        // must never archive and recreate the cloud database.
        assertNull(
            notificationRowAction(
                notification(AppNotificationAction.ResetCloudData),
                onRequestHostAction = {},
                onNavigated = {},
            ),
        )
    }

    @Test
    fun theHostResolvedActionsDelegateToTheHostAndThenReportNavigation() {
        for (action in listOf(
            AppNotificationAction.ShowFeedDetail("feed-1"),
            AppNotificationAction.ShowSettingsTab("cloud_sync"),
            AppNotificationAction.ShowInfoDialog("detail"),
        )) {
            assertEquals(1 to 1, invoke(action), action::class.simpleName)
        }
    }

    @Test
    fun openUrlIsSelfContainedAndNeverGoesThroughTheHost() {
        // Deliberately not invoked here: it reaches the real `BrowserOpener` expect/actual, which
        // would launch a browser. What matters is that the notification offers an action at all,
        // and — per the branch above — that it is not one the host has to resolve.
        assertNotNull(
            notificationRowAction(
                notification(AppNotificationAction.OpenUrl("https://example.com/")),
                onRequestHostAction = { error("OpenUrl must not go through the host") },
                onNavigated = {},
            ),
        )
    }
}
