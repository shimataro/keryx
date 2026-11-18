package works.merc.keryx.app.data.cloud

import works.merc.keryx.app.core.CLOUD_ERROR_BODY_PREVIEW_LENGTH
import works.merc.keryx.app.core.Log
import java.util.logging.Handler
import java.util.logging.Level
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** [tokenRequestFailureLogger]: tag, provider label, status and the bounded body preview. */
class TokenRequestFailureLoggerTest {

    private fun withCapturedLogRecords(block: () -> Unit): List<LogRecord> {
        System.setProperty("keryx.log.dir", System.getProperty("java.io.tmpdir"))
        val logger = Logger.getLogger(Log.LOGGER_NAME)
        val captured = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
        }
        return captured
    }

    @Test
    fun `logs a warning under the tag with the provider label, status and body`() {
        val records = withCapturedLogRecords {
            tokenRequestFailureLogger("DropboxAuth", "Dropbox")(400, """{"error":"invalid_grant"}""")
        }

        val record = records.single { it.message.contains("[DropboxAuth]") }
        assertEquals(Level.WARNING, record.level)
        assertEquals("""[DropboxAuth] Dropbox token request failed (HTTP 400): {"error":"invalid_grant"}""", record.message)
    }

    @Test
    fun `truncates the body preview to the configured length`() {
        val body = "a".repeat(CLOUD_ERROR_BODY_PREVIEW_LENGTH) + "TAIL"

        val records = withCapturedLogRecords { tokenRequestFailureLogger("T", "OneDrive")(500, body) }

        val message = records.single { it.message.contains("[T]") }.message
        assertTrue(message.endsWith("a".repeat(CLOUD_ERROR_BODY_PREVIEW_LENGTH)))
        assertFalse(message.contains("TAIL"))
    }

    @Test
    fun `a body exactly at the limit is kept whole and an empty body is allowed`() {
        val exact = "b".repeat(CLOUD_ERROR_BODY_PREVIEW_LENGTH)

        val records = withCapturedLogRecords {
            tokenRequestFailureLogger("T", "Google Drive")(401, exact)
            tokenRequestFailureLogger("T", "Google Drive")(503, "")
        }

        assertTrue(records.any { it.message.endsWith("(HTTP 401): $exact") })
        assertTrue(records.any { it.message.endsWith("(HTTP 503): ") })
    }
}
