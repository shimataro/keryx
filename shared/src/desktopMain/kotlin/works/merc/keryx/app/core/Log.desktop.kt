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
 *
 * The app's own [FileHandler]/[ConsoleHandler] pair is installed on the **JUL root logger**
 * (`Logger.getLogger("")`), not on [LOGGER_NAME] directly. Every other JVM library that logs via
 * slf4j (dbus-java in particular, through `slf4j-jdk14`) gets its own JUL logger under its own
 * class/package name with no handlers of its own, and — by JUL's default `useParentHandlers`
 * behavior — propagates up to the root logger's handlers. Installing on root therefore captures
 * both the app's own logging and every third-party library's, with the same format and
 * destination, through one shared sink instead of two divergent ones. [LOGGER_NAME] itself only
 * sets its `level`; it carries no handlers of its own and relies on that same propagation.
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
        val formatter = object : Formatter() {
            override fun format(record: LogRecord): String {
                val base = "${Instant.ofEpochMilli(record.millis)} ${record.level} ${record.message}\n"
                val thrown = record.thrown ?: return base
                val sw = StringWriter()
                thrown.printStackTrace(PrintWriter(sw))
                return base + sw
            }
        }

        val handlers = buildList {
            runCatching {
                val logsDir = File(logDir(), "logs").apply { mkdirs() }
                add(
                    FileHandler(File(logsDir, "keryx.%g.log").path, LOG_FILE_MAX_BYTES, LOG_FILE_ROTATION_COUNT, true).apply {
                        this.formatter = formatter
                        level = Level.ALL
                    },
                )
            }
            add(ConsoleHandler().apply { this.formatter = formatter; level = Level.ALL })
        }

        // Replace JUL's own default root ConsoleHandler (SimpleFormatter, installed by the
        // platform's default logging.properties) so a third-party slf4j-jdk14 caller doesn't
        // print twice, once in its format and once in ours.
        val root = Logger.getLogger("")
        root.handlers.toList().forEach(root::removeHandler)
        handlers.forEach(root::addHandler)

        return Logger.getLogger(LOGGER_NAME).apply { level = Level.ALL }
    }

    private fun logDir(): String =
        System.getProperty("keryx.log.dir")?.takeIf { it.isNotBlank() } ?: AppDirs.appDataDir()
}
