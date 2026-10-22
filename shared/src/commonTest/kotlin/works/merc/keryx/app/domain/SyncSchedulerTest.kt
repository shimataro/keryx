package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class SyncSchedulerTest {
    @Test
    fun scheduleDeferredSyncDefaultsToScheduleSync() {
        var count = 0
        val scheduler = SyncScheduler { count++ }

        scheduler.scheduleDeferredSync()

        assertEquals(1, count)
    }
}
