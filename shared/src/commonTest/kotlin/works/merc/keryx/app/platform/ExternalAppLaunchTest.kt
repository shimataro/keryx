package works.merc.keryx.app.platform

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ExternalAppLaunchTest {
    @AfterTest
    fun reset() = ExternalAppLaunch.clear()

    @Test
    fun consumeReportsAMarkOnceAndResetsIt() {
        assertFalse(ExternalAppLaunch.consumeLaunched())

        ExternalAppLaunch.markLaunched()

        assertTrue(ExternalAppLaunch.consumeLaunched())
        assertFalse(ExternalAppLaunch.consumeLaunched())
    }

    @Test
    fun clearDropsAMark() {
        ExternalAppLaunch.markLaunched()

        ExternalAppLaunch.clear()

        assertFalse(ExternalAppLaunch.consumeLaunched())
    }
}
