package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class BrowserLoginPageLauncherTest {

    @Test
    fun closeReturnsToTheAppEveryTime() {
        var returns = 0
        val launcher = BrowserLoginPageLauncher(returnToApp = { returns++ })
        launcher.close()
        launcher.close()
        assertEquals(2, returns)
    }
}
