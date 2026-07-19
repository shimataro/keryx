package works.merc.keryx.app.ui.home

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.domain.NotificationCenter
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
