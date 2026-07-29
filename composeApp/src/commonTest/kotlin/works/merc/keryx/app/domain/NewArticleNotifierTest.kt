package works.merc.keryx.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class NewArticleNotifierTest {
    /**
     * A background refresh reaches the OS tray only. It must NOT reach the in-app notification
     * center (the bell): new articles are already durably visible in the article list and the unread
     * badges, so a bell entry would be noise. That half is now enforced structurally — the notifier
     * has no [NotificationCenter] dependency to write to at all — so this asserts what remains.
     */
    @Test
    fun notifyBackgroundEmitsTrayEventOnly() = runTest {
        val notifier = NewArticleNotifier()
        val tray = mutableListOf<String>()
        val job = launch { notifier.trayEvents.collect { tray.add(it) } }
        runCurrent()

        notifier.notifyBackground("3 new articles available")
        runCurrent()

        assertEquals(listOf("3 new articles available"), tray)

        job.cancel()
    }
}
