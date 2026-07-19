package works.merc.keryx.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Clock
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NewArticleNotifierTest {
    @Test
    fun notifyBackgroundEmitsTrayAndRecordsInfoInCenter() = runTest {
        val center = NotificationCenter()
        val notifier = NewArticleNotifier(center, Clock { 1234L })
        val tray = mutableListOf<String>()
        val job = launch { notifier.trayEvents.collect { tray.add(it) } }
        runCurrent()

        notifier.notifyBackground("3 new articles available")
        runCurrent()

        // Background refresh is silent, so it reaches both the OS tray and the in-app bell.
        assertEquals(listOf("3 new articles available"), tray)

        val items = center.items.value
        assertEquals(1, items.size)
        assertEquals(AppNotificationLevel.INFO, items[0].level)
        assertEquals("3 new articles available", items[0].message)
        assertEquals(1234L, items[0].timestampMillis)

        job.cancel()
    }
}
