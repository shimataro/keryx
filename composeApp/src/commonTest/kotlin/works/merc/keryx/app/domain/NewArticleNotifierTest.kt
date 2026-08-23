package works.merc.keryx.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.FeedTimeoutException
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A [NotificationMessages] fake returning a canned string keyed off the summed count. */
private class NewArticleNotifierTestNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: KeryxException): String = "syncFailed:${exception::class.simpleName}"
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
}

@OptIn(ExperimentalCoroutinesApi::class)
class NewArticleNotifierTest {
    /**
     * A background refresh reaches the OS tray only. It must NOT reach the in-app notification
     * center (the bell): new articles are already durably visible in the article list and the unread
     * badges, so a bell entry would be noise. That half is now enforced structurally — the notifier
     * has no [NotificationCenter] dependency to write to at all — so this asserts what remains.
     */
    @Test
    fun notifyEmitsTrayEventOnly() = runTest {
        val notifier = NewArticleNotifier()
        val tray = mutableListOf<String>()
        val job = launch { notifier.trayEvents.collect { tray.add(it) } }
        runCurrent()

        notifier.notify("3 new articles available")
        runCurrent()

        assertEquals(listOf("3 new articles available"), tray)

        job.cancel()
    }

    @Test
    fun notifyIfEnabledEmitsWhenSummedCountIsPositiveAndNotificationsEnabled() = runTest {
        val notifier = NewArticleNotifier()
        val tray = mutableListOf<String>()
        val job = launch { notifier.trayEvents.collect { tray.add(it) } }
        runCurrent()

        val results = mapOf(
            "f1" to Result.Ok(2),
            "f2" to Result.Ok(3),
        )
        notifier.notifyIfEnabled(results, notificationEnabled = true, messages = NewArticleNotifierTestNotificationMessages())
        runCurrent()

        assertEquals(listOf("new:5"), tray)

        job.cancel()
    }

    @Test
    fun notifyIfEnabledDoesNotEmitWhenSummedCountIsZero() = runTest {
        val notifier = NewArticleNotifier()
        val tray = mutableListOf<String>()
        val job = launch { notifier.trayEvents.collect { tray.add(it) } }
        runCurrent()

        // A mix of a zero-count success and a failure: both contribute nothing to the sum.
        val results = mapOf(
            "f1" to Result.Ok(0),
            "f2" to Result.Err(FeedTimeoutException()),
        )
        notifier.notifyIfEnabled(results, notificationEnabled = true, messages = NewArticleNotifierTestNotificationMessages())
        runCurrent()

        assertTrue(tray.isEmpty())

        job.cancel()
    }

    @Test
    fun notifyIfEnabledDoesNotEmitWhenNotificationsDisabled() = runTest {
        val notifier = NewArticleNotifier()
        val tray = mutableListOf<String>()
        val job = launch { notifier.trayEvents.collect { tray.add(it) } }
        runCurrent()

        val results = mapOf("f1" to Result.Ok(4))
        notifier.notifyIfEnabled(results, notificationEnabled = false, messages = NewArticleNotifierTestNotificationMessages())
        runCurrent()

        assertTrue(tray.isEmpty())

        job.cancel()
    }

    /**
     * [OsNotificationSink] is how a `WorkManager`-run background refresh on Android reaches the OS
     * — see the class's own KDoc for why it can't rely on collecting [NewArticleNotifier.trayEvents]
     * the way desktop's `main.kt` does. This asserts it's actually called, independent of
     * [trayEvents][NewArticleNotifier.trayEvents] still working too (the previous tests never pass
     * a sink, so they also cover the default no-op not throwing).
     */
    @Test
    fun notifyAlsoPostsThroughTheSink() = runTest {
        val posted = mutableListOf<String>()
        val notifier = NewArticleNotifier(sink = { message -> posted.add(message) })

        notifier.notify("3 new articles available")

        assertEquals(listOf("3 new articles available"), posted)
    }

    @Test
    fun notifyIfEnabledDoesNotPostThroughTheSinkWhenNotificationsDisabled() = runTest {
        val posted = mutableListOf<String>()
        val notifier = NewArticleNotifier(sink = { message -> posted.add(message) })

        val results = mapOf("f1" to Result.Ok(4))
        notifier.notifyIfEnabled(results, notificationEnabled = false, messages = NewArticleNotifierTestNotificationMessages())

        assertTrue(posted.isEmpty())
    }
}
