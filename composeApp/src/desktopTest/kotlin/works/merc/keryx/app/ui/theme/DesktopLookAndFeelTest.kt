package works.merc.keryx.app.ui.theme

import com.formdev.flatlaf.FlatDarkLaf
import com.formdev.flatlaf.FlatLaf
import com.formdev.flatlaf.FlatLightLaf
import javax.swing.LookAndFeel
import javax.swing.UIManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * `FlatLaf.setup()` reports failure by returning `false`, never by throwing — a malformed value in
 * the extra defaults would therefore leave the app silently on Swing's cross-platform default
 * rather than surfacing anything. These defaults are the only non-standard thing this app hands
 * FlatLaf, and they are merged inside `UIManager.setLookAndFeel()`, so verifying that setup
 * actually succeeds with them is worth doing on every platform rather than only on Linux.
 */
class DesktopLookAndFeelTest {

    private var previousLookAndFeel: LookAndFeel? = null

    // The look and feel is global JVM state, and this source set also runs Compose UI tests that
    // render for real. Put back whatever was installed so nothing leaks between tests.
    @BeforeTest
    fun captureLookAndFeel() {
        previousLookAndFeel = UIManager.getLookAndFeel()
    }

    @AfterTest
    fun restoreLookAndFeel() {
        FlatLaf.setGlobalExtraDefaults(null)
        runCatching { UIManager.setLookAndFeel(previousLookAndFeel) }
    }

    @Test
    fun darkSetupSucceedsWithKeryxDefaults() {
        FlatLaf.setGlobalExtraDefaults(keryxFlatLafDefaults(dark = true))

        assertTrue(FlatDarkLaf.setup(), "FlatDarkLaf.setup() returned false with the Keryx defaults")
        assertTrue(UIManager.getLookAndFeel() is FlatDarkLaf, "FlatDarkLaf was not the installed look and feel")
    }

    @Test
    fun lightSetupSucceedsWithKeryxDefaults() {
        FlatLaf.setGlobalExtraDefaults(keryxFlatLafDefaults(dark = false))

        assertTrue(FlatLightLaf.setup(), "FlatLightLaf.setup() returned false with the Keryx defaults")
        assertTrue(UIManager.getLookAndFeel() is FlatLightLaf, "FlatLightLaf was not the installed look and feel")
    }

    /**
     * The case the startup repair exists for: the theme resolved at startup and the first value
     * the window reports agree, so a plain "did it change?" check would skip the only pass that
     * makes the components render with FlatLaf at all.
     */
    @Test
    fun firstApplicationAfterStartupGoesThroughEvenWhenTheThemeMatches() {
        assertTrue(shouldApplyLookAndFeel(installedDark = true, appliedSinceStartup = false, dark = true))
        assertTrue(shouldApplyLookAndFeel(installedDark = false, appliedSinceStartup = false, dark = false))
    }

    @Test
    fun firstApplicationGoesThroughWhenNothingWasInstalledAtStartup() {
        assertTrue(shouldApplyLookAndFeel(installedDark = null, appliedSinceStartup = false, dark = true))
    }

    @Test
    fun laterApplicationsAreSkippedWhenTheThemeIsUnchanged() {
        assertFalse(shouldApplyLookAndFeel(installedDark = true, appliedSinceStartup = true, dark = true))
        assertFalse(shouldApplyLookAndFeel(installedDark = false, appliedSinceStartup = true, dark = false))
    }

    @Test
    fun laterApplicationsGoThroughWhenTheThemeChanges() {
        assertTrue(shouldApplyLookAndFeel(installedDark = false, appliedSinceStartup = true, dark = true))
        assertTrue(shouldApplyLookAndFeel(installedDark = true, appliedSinceStartup = true, dark = false))
    }

    @Test
    fun defaultsAreExpressedInTheHexFormFlatLafParses() {
        for (dark in listOf(true, false)) {
            val defaults = keryxFlatLafDefaults(dark)
            for ((key, value) in defaults) {
                assertTrue(
                    Regex("^#[0-9a-f]{6}$").matches(value),
                    "$key is \"$value\" for dark=$dark, which is not the #rrggbb form FlatLaf parses",
                )
            }
        }
    }
}
