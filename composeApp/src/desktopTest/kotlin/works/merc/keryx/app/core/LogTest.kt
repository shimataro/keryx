package works.merc.keryx.app.core

import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * White-box test: attach a capturing handler to the JUL logger [Log] writes to,
 * then assert level mapping, `[tag]` formatting, and throwable propagation.
 * Keeps the real file sink out of the way by pointing `keryx.log.dir` at the
 * JVM temp dir.
 */
class LogTest {

    private fun withCapturedRecords(block: () -> Unit): List<LogRecord> {
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
        }
        return captured
    }

    @Test
    fun errorLogsSevereWithTagAndThrowable() {
        val boom = IllegalStateException("kaboom")
        val records = withCapturedRecords { Log.error("Sync", "merge blew up", boom) }

        val record = records.firstOrNull { it.message.contains("merge blew up") }
        assertNotNull(record, "expected an error record")
        assertEquals(Level.SEVERE, record.level)
        assertTrue(record.message.contains("[Sync]"), "tag should be bracketed: ${record.message}")
        assertEquals(boom, record.thrown)
    }

    @Test
    fun levelsMapToJulLevels() {
        val records = withCapturedRecords {
            Log.debug("T", "d")
            Log.info("T", "i")
            Log.warn("T", "w")
            Log.error("T", "e")
        }

        assertEquals(Level.FINE, records.first { it.message.endsWith("d") }.level)
        assertEquals(Level.INFO, records.first { it.message.endsWith("i") }.level)
        assertEquals(Level.WARNING, records.first { it.message.endsWith("w") }.level)
        assertEquals(Level.SEVERE, records.first { it.message.endsWith("e") }.level)
    }

    /**
     * A third-party `slf4j-jdk14` caller (e.g. dbus-java's `TransportBuilder`) logs through its
     * own JUL logger name, not [Log.LOGGER_NAME]. It reaches the app's log sink only via JUL's
     * default parent-handler propagation up to the root logger, which is where
     * [Log.debug]/[Log.info]/etc.'s own handlers actually live (see `Log.desktop.kt`'s
     * `createLogger`). This attaches a capturing handler directly to the root logger instead of
     * [Log.LOGGER_NAME] to prove that propagation path, rather than assuming it.
     */
    @Test
    fun thirdPartyLoggerPropagatesThroughRoot() {
        System.setProperty("keryx.log.dir", System.getProperty("java.io.tmpdir"))
        Log.info("Warm", "up") // forces Log's lazy root-logger setup before the test attaches its own handler

        val root = Logger.getLogger("")
        val captured = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        handler.level = Level.ALL
        root.addHandler(handler)
        try {
            Logger.getLogger("org.freedesktop.dbus.connections.transports.TransportBuilder")
                .info("Using transport dbus-java-transport-native-unixsocket to connect to unix:path=/run/user/1000/bus")
        } finally {
            root.removeHandler(handler)
        }

        val record = captured.firstOrNull { it.message.contains("Using transport") }
        assertNotNull(record, "expected the third-party logger's record to reach the root handler")
        assertEquals(Level.INFO, record.level)
    }
}
