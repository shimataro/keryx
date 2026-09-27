package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class BackgroundRefreshScheduleTest {

    // WorkManager's own PeriodicWorkRequest.MIN_PERIODIC_INTERVAL_MILLIS floor, mirrored here as a
    // plain literal so these tests don't need an androidx.work dependency — see
    // backgroundRefreshSchedule's own KDoc for why the function itself takes no default anymore.
    private val workManagerMinimum = 15L

    @Test
    fun zeroAndNegativeValuesAreDisabled() {
        assertEquals(BackgroundRefreshSchedule.Disabled, backgroundRefreshSchedule(0, workManagerMinimum))
        assertEquals(BackgroundRefreshSchedule.Disabled, backgroundRefreshSchedule(-30, workManagerMinimum))
    }

    @Test
    fun valuesAtOrAboveTheMinimumArePeriodicAtThatInterval() {
        assertEquals(BackgroundRefreshSchedule.Periodic(15), backgroundRefreshSchedule(15, workManagerMinimum))
        assertEquals(BackgroundRefreshSchedule.Periodic(30), backgroundRefreshSchedule(30, workManagerMinimum))
        assertEquals(BackgroundRefreshSchedule.Periodic(60), backgroundRefreshSchedule(60, workManagerMinimum))
        assertEquals(BackgroundRefreshSchedule.Periodic(180), backgroundRefreshSchedule(180, workManagerMinimum))
    }

    @Test
    fun positiveValuesBelowTheMinimumAreCoercedUpToIt() {
        assertEquals(BackgroundRefreshSchedule.Periodic(15), backgroundRefreshSchedule(5, workManagerMinimum))
        assertEquals(BackgroundRefreshSchedule.Periodic(15), backgroundRefreshSchedule(1, workManagerMinimum))
    }

    @Test
    fun minimumMinutesIsConfigurable() {
        assertEquals(BackgroundRefreshSchedule.Periodic(10), backgroundRefreshSchedule(5, minimumMinutes = 10))
        assertEquals(BackgroundRefreshSchedule.Periodic(20), backgroundRefreshSchedule(20, minimumMinutes = 10))
    }
}
