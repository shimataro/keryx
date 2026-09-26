package works.merc.keryx.app.domain

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.FeedTimeoutException
import works.merc.keryx.app.core.Result
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

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
        notifier.notifyIfEnabled(results, notificationEnabled = true, messages = FakeNotificationMessages())
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
        notifier.notifyIfEnabled(results, notificationEnabled = true, messages = FakeNotificationMessages())
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
        notifier.notifyIfEnabled(results, notificationEnabled = false, messages = FakeNotificationMessages())
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
        val notifier = NewArticleNotifier(sink = { message, _ -> posted.add(message) })

        notifier.notify("3 new articles available")

        assertEquals(listOf("3 new articles available"), posted)
    }

    @Test
    fun notifyIfEnabledDoesNotPostThroughTheSinkWhenNotificationsDisabled() = runTest {
        val posted = mutableListOf<String>()
        val notifier = NewArticleNotifier(sink = { message, _ -> posted.add(message) })

        val results = mapOf("f1" to Result.Ok(4))
        notifier.notifyIfEnabled(results, notificationEnabled = false, messages = FakeNotificationMessages())

        assertTrue(posted.isEmpty())
    }

    /**
     * The sink's `count` is what Android's `AndroidNotificationSink` forwards to
     * `NotificationCompat.Builder.setNumber` — see `OsNotificationSink.post`'s own KDoc. This must
     * be the summed new-article count, not e.g. the number of feeds that contributed to it.
     */
    @Test
    fun notifyIfEnabledPostsThroughTheSinkWithTheSummedCount() = runTest {
        val postedCounts = mutableListOf<Int>()
        val notifier = NewArticleNotifier(sink = { _, count -> postedCounts.add(count) })

        val results = mapOf("f1" to Result.Ok(2), "f2" to Result.Ok(3))
        notifier.notifyIfEnabled(results, notificationEnabled = true, messages = FakeNotificationMessages())

        assertEquals(listOf(5), postedCounts)
    }

    /** A direct [NewArticleNotifier.notify] call with no count of its own posts `0` — see its KDoc. */
    @Test
    fun notifyWithNoCountArgumentPostsZeroThroughTheSink() = runTest {
        val postedCounts = mutableListOf<Int>()
        val notifier = NewArticleNotifier(sink = { _, count -> postedCounts.add(count) })

        notifier.notify("3 new articles available")

        assertEquals(listOf(0), postedCounts)
    }
}
