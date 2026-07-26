package works.merc.keryx.app.ui.theme

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class KeryxThemeTest {
    @Test
    fun resolveDarkThemeForcesLightRegardlessOfSystem() {
        assertFalse(resolveDarkTheme("light", systemDark = true))
        assertFalse(resolveDarkTheme("light", systemDark = false))
    }

    @Test
    fun resolveDarkThemeForcesDarkRegardlessOfSystem() {
        assertTrue(resolveDarkTheme("dark", systemDark = false))
        assertTrue(resolveDarkTheme("dark", systemDark = true))
    }

    @Test
    fun resolveDarkThemeFollowsSystemForSystemMode() {
        assertTrue(resolveDarkTheme("system", systemDark = true))
        assertFalse(resolveDarkTheme("system", systemDark = false))
    }

    @Test
    fun resolveDarkThemeFollowsSystemForUnknownMode() {
        // Any non-"light"/"dark" value falls through to the system signal.
        assertTrue(resolveDarkTheme("", systemDark = true))
        assertFalse(resolveDarkTheme("", systemDark = false))
    }

    @Test
    fun keryxSurfaceColorDiffersBetweenDarkAndLight() {
        assertNotEquals(keryxSurfaceColor(dark = true), keryxSurfaceColor(dark = false))
    }

    @Test
    fun keryxSurfaceColorIsStableForSameFlag() {
        assertEquals(keryxSurfaceColor(dark = true), keryxSurfaceColor(dark = true))
        assertEquals(keryxSurfaceColor(dark = false), keryxSurfaceColor(dark = false))
    }
}
