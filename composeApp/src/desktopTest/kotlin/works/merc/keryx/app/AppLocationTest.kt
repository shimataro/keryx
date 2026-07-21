package works.merc.keryx.app

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AppLocationTest {
    @Test
    fun detectsTranslocatedPath() {
        assertTrue(
            isTranslocatedPath(
                "/private/var/folders/xy/abc/T/AppTranslocation/D1E2/d/Keryx.app/Contents/MacOS/Keryx",
            ),
        )
    }

    @Test
    fun normalApplicationsPathIsNotTranslocated() {
        assertFalse(isTranslocatedPath("/Applications/Keryx.app/Contents/MacOS/Keryx"))
    }

    @Test
    fun nullPathIsNotTranslocated() {
        assertFalse(isTranslocatedPath(null))
    }
}
