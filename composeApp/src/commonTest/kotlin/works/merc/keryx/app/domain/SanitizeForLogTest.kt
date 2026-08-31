package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SanitizeForLogTest {

    @Test
    fun sanitizeForLogReplacesCrLfWithASpace() {
        assertEquals("a b c", sanitizeForLog("a\r\nb\nc", maxLength = 100))
    }

    @Test
    fun sanitizeForLogTruncatesToMaxLength() {
        assertEquals("abcde", sanitizeForLog("abcdefghij", maxLength = 5))
    }

    @Test
    fun sanitizeForLogLeavesAnOrdinaryStringUnchanged() {
        assertEquals("access_denied", sanitizeForLog("access_denied", maxLength = 100))
    }
}
