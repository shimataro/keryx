package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

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
    fun parsesIso8601WithoutSeconds() {
        // W3CDTF allows the seconds to be omitted; Instant.parse requires them.
        assertEquals(1577836800000L, DateTimeParser.parseToEpochMillis("2020-01-01T00:00Z"))
        assertEquals(1577804400000L, DateTimeParser.parseToEpochMillis("2020-01-01T00:00+09:00"))
    }

    @Test
    fun parsesIso8601WithBasicOffset() {
        // ISO-8601 basic format writes the offset without the colon.
        assertEquals(1577804400000L, DateTimeParser.parseToEpochMillis("2020-01-01T00:00:00+0900"))
        assertEquals(1577804400000L, DateTimeParser.parseToEpochMillis("2020-01-01T00:00+0900"))
    }

    @Test
    fun parsesBareLocalDateTimeAsUtc() {
        assertEquals(1577836800000L, DateTimeParser.parseToEpochMillis("2020-01-01T00:00:00"))
        assertEquals(1577836800000L, DateTimeParser.parseToEpochMillis("2020-01-01T00:00"))
    }

    @Test
    fun parsesDateOnlyAsMidnightUtc() {
        assertEquals(1577836800000L, DateTimeParser.parseToEpochMillis("2020-01-01"))
    }

    @Test
    fun parsesRfc822WithoutSeconds() {
        assertEquals(1033545600000L, DateTimeParser.parseToEpochMillis("Wed, 02 Oct 2002 08:00 GMT"))
    }

    @Test
    fun parsesRfc822NorthAmericanZone() {
        // RFC 822 section 5.1 zone abbreviations, which RFC_1123 alone does not accept.
        assertEquals(1033563600000L, DateTimeParser.parseToEpochMillis("Wed, 02 Oct 2002 08:00:00 EST"))
        assertEquals(1033570800000L, DateTimeParser.parseToEpochMillis("Wed, 02 Oct 2002 08:00:00 PDT"))
        // Zone abbreviations are matched case-insensitively.
        assertEquals(1033563600000L, DateTimeParser.parseToEpochMillis("Wed, 02 Oct 2002 08:00:00 est"))
    }

    @Test
    fun returnsNullForUnsupportedPartialDates() {
        // The YYYY-MM / YYYY W3CDTF forms are deliberately not supported.
        assertNull(DateTimeParser.parseToEpochMillis("2020-01"))
        assertNull(DateTimeParser.parseToEpochMillis("2020"))
    }

    @Test
    fun returnsNullForBlankOrGarbage() {
        assertNull(DateTimeParser.parseToEpochMillis(null))
        assertNull(DateTimeParser.parseToEpochMillis(""))
        assertNull(DateTimeParser.parseToEpochMillis("not a date"))
    }
}
