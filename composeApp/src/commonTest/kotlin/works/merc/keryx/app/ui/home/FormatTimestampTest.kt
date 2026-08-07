package works.merc.keryx.app.ui.home

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins `formatTimestamp`'s exact output. The article list formats once per row, so the body is
 * hand-rolled rather than `padStart`-based; these cases are what guarantee that stayed
 * byte-identical. The other `formatTimestamp` assertions in the suite derive their expected value
 * from the function itself, so they cannot catch a formatting change — this one can.
 */
class FormatTimestampTest {

    private val utc = TimeZone.UTC

    private fun at(year: Int, month: Int, day: Int, hour: Int, minute: Int): Long =
        LocalDateTime(year, month, day, hour, minute).toInstant(utc).toEpochMilliseconds()

    @Test
    fun formatsWithTwoDigitPaddingOnEveryField() {
        assertEquals("2024-01-02 03:04", formatTimestamp(at(2024, 1, 2, 3, 4), utc))
    }

    @Test
    fun leavesAlreadyTwoDigitFieldsAlone() {
        assertEquals("2024-11-23 14:35", formatTimestamp(at(2024, 11, 23, 14, 35), utc))
    }

    @Test
    fun formatsMidnightAndTheLastMinuteOfTheDay() {
        assertEquals("2024-03-10 00:00", formatTimestamp(at(2024, 3, 10, 0, 0), utc))
        assertEquals("2024-03-10 23:59", formatTimestamp(at(2024, 3, 10, 23, 59), utc))
    }

    @Test
    fun formatsAcrossAYearBoundary() {
        assertEquals("2023-12-31 23:59", formatTimestamp(at(2023, 12, 31, 23, 59), utc))
        assertEquals("2024-01-01 00:00", formatTimestamp(at(2024, 1, 1, 0, 0), utc))
    }

    /** The year is padded to `yyyy` like every other field, so a year below 1000 keeps the width. */
    @Test
    fun padsYearsBelowOneThousandToFourDigits() {
        assertEquals("0001-01-02 03:04", formatTimestamp(at(1, 1, 2, 3, 4), utc))
        assertEquals("0099-01-02 03:04", formatTimestamp(at(99, 1, 2, 3, 4), utc))
        assertEquals("0999-12-31 23:59", formatTimestamp(at(999, 12, 31, 23, 59), utc))
        assertEquals("1000-01-01 00:00", formatTimestamp(at(1000, 1, 1, 0, 0), utc))
    }

    @Test
    fun formatsAnEpochTimestamp() {
        assertEquals("1970-01-01 00:00", formatTimestamp(0L, utc))
    }

    @Test
    fun returnsEmptyForNull() {
        assertEquals("", formatTimestamp(null, utc))
        assertEquals("", formatTimestamp(null))
    }

    @Test
    fun theZoneOverloadAndTheDefaultOverloadAgreeOnTheSystemZone() {
        val millis = at(2024, 6, 15, 12, 30)
        assertEquals(
            formatTimestamp(millis, TimeZone.currentSystemDefault()),
            formatTimestamp(millis),
        )
    }
}
