package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundRefreshScheduleTest {

    @Test
    fun zeroAndNegativeValuesAreDisabled() {
        assertEquals(BackgroundRefreshSchedule.Disabled, backgroundRefreshSchedule(0))
        assertEquals(BackgroundRefreshSchedule.Disabled, backgroundRefreshSchedule(-30))
    }

    @Test
    fun valuesAtOrAboveTheMinimumArePeriodicAtThatInterval() {
        assertEquals(BackgroundRefreshSchedule.Periodic(15), backgroundRefreshSchedule(15))
        assertEquals(BackgroundRefreshSchedule.Periodic(30), backgroundRefreshSchedule(30))
        assertEquals(BackgroundRefreshSchedule.Periodic(60), backgroundRefreshSchedule(60))
        assertEquals(BackgroundRefreshSchedule.Periodic(180), backgroundRefreshSchedule(180))
    }

    @Test
    fun positiveValuesBelowTheMinimumAreCoercedUpToIt() {
        assertEquals(BackgroundRefreshSchedule.Periodic(15), backgroundRefreshSchedule(5))
        assertEquals(BackgroundRefreshSchedule.Periodic(15), backgroundRefreshSchedule(1))
    }

    @Test
    fun minimumMinutesIsConfigurable() {
        assertEquals(BackgroundRefreshSchedule.Periodic(10), backgroundRefreshSchedule(5, minimumMinutes = 10))
        assertEquals(BackgroundRefreshSchedule.Periodic(20), backgroundRefreshSchedule(20, minimumMinutes = 10))
    }
}
