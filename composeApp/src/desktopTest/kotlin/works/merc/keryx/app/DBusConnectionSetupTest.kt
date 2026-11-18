package works.merc.keryx.app

import works.merc.keryx.app.core.Log
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** [openDBusConnectionWithTimeout]: on time, failing, and late-completing `open` lambdas. */
class DBusConnectionSetupTest {

    private fun <T> captureLogs(block: () -> T): Pair<T, List<LogRecord>> {
        val logger = Logger.getLogger(Log.LOGGER_NAME)
        val captured = CopyOnWriteArrayList<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            return block() to captured
        } finally {
            logger.removeHandler(handler)
        }
    }

    @Test
    fun `returns the opened value when it completes in time`() {
        val lateClosed = CopyOnWriteArrayList<String>()

        val result = openDBusConnectionWithTimeout<String>("Tag", "bus", 5.seconds, "fallback", { lateClosed += it }) { "conn" }

        assertEquals("conn", result)
        assertTrue(lateClosed.isEmpty())
    }

    @Test
    fun `returns null when open yields null`() {
        assertNull(openDBusConnectionWithTimeout<String>("Tag", "bus", 5.seconds, "fallback", {}) { null })
    }

    @Test
    fun `returns null and logs a warning when open throws`() {
        val (result, logs) = captureLogs {
            openDBusConnectionWithTimeout<String>("SetupTag", "status icon", 5.seconds, "fallback", {}) {
                error("no bus")
            }
        }

        assertNull(result)
        assertTrue(
            logs.any { it.message.contains("[SetupTag]") && it.message.contains("Could not set up the status icon") },
            "expected a setup warning, got ${logs.map { it.message }}",
        )
    }

    @Test
    fun `on timeout returns null, logs, and hands the late connection to onLateClose`() {
        val release = CountDownLatch(1)
        val lateClosed = CopyOnWriteArrayList<String>()

        val (result, logs) = captureLogs {
            openDBusConnectionWithTimeout<String>("SetupTag", "menu", 50.milliseconds, "using the fallback", { lateClosed += it }) {
                release.await()
                "late-conn"
            }
        }

        assertNull(result)
        assertTrue(lateClosed.isEmpty(), "must not close before the connection arrives")
        assertTrue(
            logs.any { it.message.contains("did not answer within") && it.message.contains("using the fallback") },
            "expected a timeout warning, got ${logs.map { it.message }}",
        )

        release.countDown()
        awaitConditionBlocking { lateClosed.isNotEmpty() }
        assertEquals(listOf("late-conn"), lateClosed.toList())
    }

    @Test
    fun `a late null connection is not passed to onLateClose`() {
        val release = CountDownLatch(1)
        val done = CountDownLatch(1)
        val lateClosed = CopyOnWriteArrayList<String>()

        val result = openDBusConnectionWithTimeout<String>("Tag", "menu", 50.milliseconds, "fallback", { lateClosed += it }) {
            try {
                release.await()
                null
            } finally {
                done.countDown()
            }
        }
        release.countDown()
        done.await()

        assertNull(result)
        assertTrue(lateClosed.isEmpty())
    }
}
