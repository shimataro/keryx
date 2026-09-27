package works.merc.keryx.app.presentation.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.domain.NotificationCenter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationAlertsTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun alert(id: String, level: AppNotificationLevel, text: NotificationText = NotificationText.FeedGone(id)) =
        AppNotification(id = id, level = level, text = text, timestampMillis = 0L)

    @Test
    fun alertToSurfaceReportsTheNewestWarningOrErrorAndIgnoresInfo() = runTest {
        val center = NotificationCenter()
        val alerts = NotificationAlerts(center)
        advanceUntilIdle()
        assertNull(alerts.alertToSurface.value)

        // INFO is a new-version notice or a finished OPML import — the bell's badge, not a Snackbar.
        center.add(alert("i", AppNotificationLevel.INFO))
        advanceUntilIdle()
        assertNull(alerts.alertToSurface.value)

        center.add(alert("w", AppNotificationLevel.WARNING))
        center.add(alert("e", AppNotificationLevel.ERROR))
        advanceUntilIdle()
        assertEquals("e", alerts.alertToSurface.value?.id)
    }

    @Test
    fun markAlertsSurfacedConsumesEveryPendingAlertAtOnce() = runTest {
        // Only the newest of a batch is announced (one Snackbar at a time), so marking one at a
        // time would walk backwards through the queue and end on the oldest.
        val center = NotificationCenter()
        val alerts = NotificationAlerts(center)
        center.add(alert("w", AppNotificationLevel.WARNING))
        center.add(alert("e", AppNotificationLevel.ERROR))
        advanceUntilIdle()

        alerts.markAlertsSurfaced()
        advanceUntilIdle()

        assertNull(alerts.alertToSurface.value)
    }

    @Test
    fun aRecurringAlertIsNotResurfacedWhileADistinctOneStillIs() = runTest {
        // SyncRepository coalesces its errors, minting a fresh id per attempt — keying on the id
        // would announce the same failure again every background sync.
        val center = NotificationCenter()
        val alerts = NotificationAlerts(center)
        center.addCoalescing(alert("first", AppNotificationLevel.ERROR, text = NotificationText.SyncFailed(ErrorKind.GENERIC)))
        advanceUntilIdle()
        alerts.markAlertsSurfaced()
        advanceUntilIdle()

        center.addCoalescing(alert("second", AppNotificationLevel.ERROR, text = NotificationText.SyncFailed(ErrorKind.GENERIC)))
        advanceUntilIdle()
        assertNull(alerts.alertToSurface.value)

        center.add(alert("other", AppNotificationLevel.ERROR, text = NotificationText.FeedGone("feed")))
        advanceUntilIdle()
        assertEquals("other", alerts.alertToSurface.value?.id)
    }
}
