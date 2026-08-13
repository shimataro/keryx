package works.merc.keryx.app.core

import works.merc.keryx.app.platform.AppDirs
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.time.Instant
import java.util.logging.ConsoleHandler
import java.util.logging.FileHandler
import java.util.logging.Formatter
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger

/**
 * Desktop [Log] backed by `java.util.logging`. A rotating [FileHandler] gives us
 * thread safety, timestamps, and size-capped rotation for free (no extra deps),
 * writing to `<appDataDir>/logs/keryx.<n>.log`. Also mirrors to stderr.
 *
 * The log directory can be overridden with the `keryx.log.dir` system property
 * (used by tests to avoid writing into the real user data directory).
 */
actual object Log {
    /** JUL logger name; also the white-box hook tests attach a capturing handler to. */
    const val LOGGER_NAME: String = "works.merc.keryx"

    /** Max bytes per rotated log file, and how many rotated files [FileHandler] keeps. */
    private const val LOG_FILE_MAX_BYTES = 1_000_000
    private const val LOG_FILE_ROTATION_COUNT = 3

    private val logger: Logger by lazy { createLogger() }

    actual fun debug(tag: String, message: String) = log(Level.FINE, tag, message, null)
    actual fun info(tag: String, message: String) = log(Level.INFO, tag, message, null)
    actual fun warn(tag: String, message: String, throwable: Throwable?) =
        log(Level.WARNING, tag, message, throwable)

    actual fun error(tag: String, message: String, throwable: Throwable?) =
        log(Level.SEVERE, tag, message, throwable)

    private fun log(level: Level, tag: String, message: String, throwable: Throwable?) {
        logger.log(LogRecord(level, "[$tag] $message").apply { throwable?.let { thrown = it } })
    }

    private fun createLogger(): Logger {
        val logger = Logger.getLogger(LOGGER_NAME)
        logger.useParentHandlers = false
        logger.level = Level.ALL

        val formatter = object : Formatter() {
            override fun format(record: LogRecord): String {
                val base = "${Instant.ofEpochMilli(record.millis)} ${record.level} ${record.message}\n"
                val thrown = record.thrown ?: return base
                val sw = StringWriter()
                thrown.printStackTrace(PrintWriter(sw))
                return base + sw
            }
        }

        runCatching {
            val logsDir = File(logDir(), "logs").apply { mkdirs() }
            FileHandler(File(logsDir, "keryx.%g.log").path, LOG_FILE_MAX_BYTES, LOG_FILE_ROTATION_COUNT, true).apply {
                this.formatter = formatter
                level = Level.ALL
                logger.addHandler(this)
            }
        }

        logger.addHandler(
            ConsoleHandler().apply {
                this.formatter = formatter
                level = Level.ALL
            },
        )
        return logger
    }

    private fun logDir(): String =
        System.getProperty("keryx.log.dir")?.takeIf { it.isNotBlank() } ?: AppDirs.appDataDir()
}
