package works.merc.keryx.app.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel
import kotlinx.coroutines.test.advanceUntilIdle
import works.merc.keryx.app.domain.NotificationCenter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class NotificationCenterViewModelTest {

    @BeforeTest
    fun setUp() {
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun notification(id: String) =
        AppNotification(id = id, level = AppNotificationLevel.WARNING, message = "msg:$id", timestampMillis = 0L)

    @Test
    fun itemsMirrorsUnderlyingNotificationCenter() = runTest {
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)

        assertTrue(vm.items.value.isEmpty())

        center.add(notification("a"))
        center.add(notification("b"))

        assertEquals(listOf("b", "a"), vm.items.value.map { it.id })
    }

    @Test
    fun dismissDelegatesToNotificationCenter() = runTest {
        val center = NotificationCenter()
        center.add(notification("a"))
        center.add(notification("b"))
        val vm = NotificationCenterViewModel(center)

        vm.dismiss("a")

        assertEquals(listOf("b"), vm.items.value.map { it.id })
        assertEquals(listOf("b"), center.items.value.map { it.id })
    }

    @Test
    fun dismissAllDelegatesToNotificationCenter() = runTest {
        val center = NotificationCenter()
        center.add(notification("a"))
        center.add(notification("b"))
        val vm = NotificationCenterViewModel(center)

        vm.dismissAll()

        assertTrue(vm.items.value.isEmpty())
        assertTrue(center.items.value.isEmpty())
    }

    // --- alertToSurface / markAlertsSurfaced (Android's foreground alert Snackbar) ---

    private fun alert(id: String, level: AppNotificationLevel, message: String = "msg:$id") =
        AppNotification(id = id, level = level, message = message, timestampMillis = 0L)

    @Test
    fun alertToSurfaceReportsTheNewestWarningOrErrorAndIgnoresInfo() = runTest {
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        advanceUntilIdle()
        assertNull(vm.alertToSurface.value)

        // INFO is a new-version notice or a finished OPML import — the bell's badge, not a Snackbar.
        center.add(alert("i", AppNotificationLevel.INFO))
        advanceUntilIdle()
        assertNull(vm.alertToSurface.value)

        center.add(alert("w", AppNotificationLevel.WARNING))
        center.add(alert("e", AppNotificationLevel.ERROR))
        advanceUntilIdle()
        assertEquals("e", vm.alertToSurface.value?.id)
    }

    @Test
    fun markAlertsSurfacedConsumesEveryPendingAlertAtOnce() = runTest {
        // Only the newest of a batch is announced (one Snackbar at a time), so marking one at a
        // time would walk backwards through the queue and end on the oldest.
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        center.add(alert("w", AppNotificationLevel.WARNING))
        center.add(alert("e", AppNotificationLevel.ERROR))
        advanceUntilIdle()

        vm.markAlertsSurfaced()
        advanceUntilIdle()

        assertNull(vm.alertToSurface.value)
    }

    @Test
    fun aRecurringAlertIsNotResurfacedWhileADistinctOneStillIs() = runTest {
        // SyncRepository coalesces its errors, minting a fresh id per attempt — keying on the id
        // would announce the same failure again every background sync.
        val center = NotificationCenter()
        val vm = NotificationCenterViewModel(center)
        center.addCoalescing(alert("first", AppNotificationLevel.ERROR, message = "sync failed"))
        advanceUntilIdle()
        vm.markAlertsSurfaced()
        advanceUntilIdle()

        center.addCoalescing(alert("second", AppNotificationLevel.ERROR, message = "sync failed"))
        advanceUntilIdle()
        assertNull(vm.alertToSurface.value)

        center.add(alert("other", AppNotificationLevel.ERROR, message = "feed gone"))
        advanceUntilIdle()
        assertEquals("other", vm.alertToSurface.value?.id)
    }
}
