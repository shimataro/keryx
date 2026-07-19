package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DateTimeParserTest {
    @Test
    fun parsesRfc822PubDate() {
        // RSS pubDate (RFC 822 / 1123)
        val millis = DateTimeParser.parseToEpochMillis("Wed, 02 Oct 2002 08:00:00 GMT")
        assertEquals(1033545600000L, millis)
    }

    @Test
    fun parsesIso8601WithOffset() {
        val millis = DateTimeParser.parseToEpochMillis("2003-12-13T18:30:02Z")
        assertEquals(1071340202000L, millis)
    }

    @Test
    fun parsesIso8601WithNonUtcOffset() {
        val z = DateTimeParser.parseToEpochMillis("2003-12-13T18:30:02+01:00")
        assertEquals(1071336602000L, z)
    }

    @Test
    fun parsesBareLocalDateTimeAsUtc() {
        assertTrue(DateTimeParser.parseToEpochMillis("2020-01-01T00:00:00") != null)
    }

    @Test
    fun returnsNullForBlankOrGarbage() {
        assertNull(DateTimeParser.parseToEpochMillis(null))
        assertNull(DateTimeParser.parseToEpochMillis(""))
        assertNull(DateTimeParser.parseToEpochMillis("not a date"))
    }
}
