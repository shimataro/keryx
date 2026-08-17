package works.merc.keryx.app

import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.core.Log
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SingleInstanceCoordinatorTest {
    private lateinit var appDataDir: File
    private lateinit var first: SingleInstanceCoordinator
    private lateinit var second: SingleInstanceCoordinator

    @BeforeTest
    fun setUp() {
        appDataDir = createTempDirectory("single-instance-coordinator-test").toFile()
        first = SingleInstanceCoordinator(appDataDir)
        second = SingleInstanceCoordinator(appDataDir)
    }

    @AfterTest
    fun tearDown() {
        first.close()
        second.close()
    }

    @Test
    fun secondInstanceFailsToAcquireLockHeldByFirst() {
        assertTrue(first.tryAcquireLock())
        assertFalse(second.tryAcquireLock())
    }

    @Test
    fun signalRunningInstanceInvokesActivationCallbackOnListener() = runBlocking {
        assertTrue(first.tryAcquireLock())

        val activated = AtomicBoolean(false)
        first.startActivationListener { activated.set(true) }

        assertTrue(second.signalRunningInstance())

        val deadline = System.currentTimeMillis() + 5_000
        while (!activated.get() && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertTrue(activated.get())
    }

    @Test
    fun signalRunningInstanceFailsWithoutAListener() {
        assertFalse(second.signalRunningInstance())
    }

    @Test
    fun signalRunningInstanceLogsAWarningWhenNoPortFileExists() {
        val records = withCapturedLogRecords { assertFalse(second.signalRunningInstance()) }

        assertTrue(records.any { it.level == Level.WARNING && it.message.contains("[SingleInstance]") })
    }

    @Test
    fun signalRunningInstanceFailsWhenThePortFileContainsAnOutOfRangeValue() {
        assertTrue(first.tryAcquireLock())
        first.startActivationListener {}
        File(appDataDir, "keryx.port").writeText("99999")

        assertFalse(second.signalRunningInstance())
    }

    /** Same white-box capture pattern as `core.LogTest`: attach a handler to the JUL logger [Log] writes to. */
    private fun withCapturedLogRecords(block: () -> Unit): List<LogRecord> {
        val previousLogDir = System.getProperty("keryx.log.dir")
        System.setProperty("keryx.log.dir", System.getProperty("java.io.tmpdir"))
        val logger = Logger.getLogger(Log.LOGGER_NAME)
        val captured = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        handler.level = Level.ALL
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
            if (previousLogDir == null) {
                System.clearProperty("keryx.log.dir")
            } else {
                System.setProperty("keryx.log.dir", previousLogDir)
            }
        }
        return captured
    }

    @Test
    fun signalRunningInstanceForwardsUriString() = runBlocking {
        assertTrue(first.tryAcquireLock())

        var receivedUri: String? = "placeholder"
        first.startActivationListener { uri -> receivedUri = uri }

        val testUri = "keryx://oauth2/callback?code=abc&state=xyz"
        assertTrue(second.signalRunningInstance(testUri))

        val deadline = System.currentTimeMillis() + 5_000
        while (receivedUri == "placeholder" && System.currentTimeMillis() < deadline) {
            Thread.sleep(20)
        }
        assertEquals(testUri, receivedUri)
    }
}
