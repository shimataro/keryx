package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals

/** Pins the bucket boundaries the notification center's relative timestamps are chosen from. */
class RelativeTimeTest {

    @Test
    fun underAMinuteIsNow() {
        assertEquals(RelativeTime.Now, relativeTimeOf(0L))
        assertEquals(RelativeTime.Now, relativeTimeOf(59_999L))
    }

    @Test
    fun aTimestampAheadOfTheClockIsNow() {
        assertEquals(RelativeTime.Now, relativeTimeOf(-5_000L))
    }

    @Test
    fun minutesFromOneMinuteUpToAnHour() {
        assertEquals(RelativeTime.Minutes(1), relativeTimeOf(60_000L))
        assertEquals(RelativeTime.Minutes(59), relativeTimeOf(3_599_999L))
    }

    @Test
    fun hoursFromOneHourUpToADay() {
        assertEquals(RelativeTime.Hours(1), relativeTimeOf(3_600_000L))
        assertEquals(RelativeTime.Hours(23), relativeTimeOf(86_399_999L))
    }

    @Test
    fun daysFromOneDayUpToAWeek() {
        assertEquals(RelativeTime.Days(1), relativeTimeOf(86_400_000L))
        assertEquals(RelativeTime.Days(6), relativeTimeOf(604_799_999L))
    }

    @Test
    fun aWeekOrOlderIsAbsolute() {
        assertEquals(RelativeTime.Absolute, relativeTimeOf(604_800_000L))
        assertEquals(RelativeTime.Absolute, relativeTimeOf(365L * 86_400_000L))
    }
}
