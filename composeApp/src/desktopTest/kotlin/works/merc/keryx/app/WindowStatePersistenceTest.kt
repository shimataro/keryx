package works.merc.keryx.app

import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import works.merc.keryx.app.core.WINDOW_DEFAULT_HEIGHT
import works.merc.keryx.app.core.WINDOW_DEFAULT_WIDTH
import works.merc.keryx.app.core.WINDOW_MIN_HEIGHT
import works.merc.keryx.app.core.WINDOW_MIN_WIDTH
import works.merc.keryx.app.data.local.LocalSettings
import java.awt.Rectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** A plain, non-Compose-state-backed [WindowState] fake, so [observedWindowState] can be tested
 * without requiring a Compose snapshot/runtime context. */
private class FakeWindowState(
    override var placement: WindowPlacement = WindowPlacement.Floating,
    override var isMinimized: Boolean = false,
    override var position: WindowPosition = WindowPosition.PlatformDefault,
    override var size: DpSize = DpSize(800.dp, 600.dp),
) : WindowState

private val PRIMARY_SCREEN = Rectangle(0, 0, 1920, 1080)
private val SECONDARY_SCREEN = Rectangle(1920, 0, 1920, 1080)

class WindowStatePersistenceTest {
    @Test
    fun observedWindowStateSnapshotsAllFields() {
        val fake = FakeWindowState(
            placement = WindowPlacement.Maximized,
            isMinimized = true,
            position = WindowPosition.Absolute(10.dp, 20.dp),
            size = DpSize(900.dp, 700.dp),
        )

        val observed = observedWindowState(fake)

        assertEquals(WindowPlacement.Maximized, observed.placement)
        assertEquals(true, observed.isMinimized)
        assertEquals(WindowPosition.Absolute(10.dp, 20.dp), observed.position)
        assertEquals(DpSize(900.dp, 700.dp), observed.size)
    }

    @Test
    fun persistingFloatingObservationSavesSizePositionAndPlacement() {
        val observed = ObservedWindowState(
            placement = WindowPlacement.Floating,
            isMinimized = false,
            size = DpSize(1000.dp, 700.dp),
            position = WindowPosition.Absolute(50.dp, 60.dp),
        )

        val result = persistedWindowState(LocalSettings(), observed)

        assertEquals("floating", result.windowPlacement)
        assertEquals(1000.0, result.windowWidth)
        assertEquals(700.0, result.windowHeight)
        assertEquals(50.0, result.windowX)
        assertEquals(60.0, result.windowY)
    }

    @Test
    fun persistingMaximizedObservationKeepsPriorFloatingBounds() {
        val current = LocalSettings(windowWidth = 900.0, windowHeight = 650.0, windowX = 10.0, windowY = 20.0)
        val observed = ObservedWindowState(
            placement = WindowPlacement.Maximized,
            isMinimized = false,
            size = DpSize(1920.dp, 1080.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
        )

        val result = persistedWindowState(current, observed)

        assertEquals("maximized", result.windowPlacement)
        assertEquals(900.0, result.windowWidth)
        assertEquals(650.0, result.windowHeight)
        assertEquals(10.0, result.windowX)
        assertEquals(20.0, result.windowY)
    }

    @Test
    fun persistingFullscreenObservationKeepsPriorFloatingBounds() {
        val current = LocalSettings(windowWidth = 900.0, windowHeight = 650.0, windowX = 10.0, windowY = 20.0)
        val observed = ObservedWindowState(
            placement = WindowPlacement.Fullscreen,
            isMinimized = false,
            size = DpSize(1920.dp, 1080.dp),
            position = WindowPosition.Absolute(0.dp, 0.dp),
        )

        val result = persistedWindowState(current, observed)

        assertEquals("fullscreen", result.windowPlacement)
        assertEquals(900.0, result.windowWidth)
        assertEquals(650.0, result.windowHeight)
    }

    @Test
    fun persistingMinimizedObservationChangesNothing() {
        val current = LocalSettings(windowWidth = 900.0, windowHeight = 650.0, windowPlacement = "maximized")
        val observed = ObservedWindowState(
            placement = WindowPlacement.Floating,
            isMinimized = true,
            size = DpSize(1000.dp, 700.dp),
            position = WindowPosition.Absolute(50.dp, 60.dp),
        )

        val result = persistedWindowState(current, observed)

        assertEquals(current, result)
    }

    @Test
    fun persistingNonAbsolutePositionDoesNotChangeXY() {
        val current = LocalSettings(windowX = 10.0, windowY = 20.0)
        val observed = ObservedWindowState(
            placement = WindowPlacement.Floating,
            isMinimized = false,
            size = DpSize(1000.dp, 700.dp),
            position = WindowPosition.PlatformDefault,
        )

        val result = persistedWindowState(current, observed)

        assertEquals(10.0, result.windowX)
        assertEquals(20.0, result.windowY)
    }

    @Test
    fun restoredWindowStateWithNoSavedValuesUsesDefaultsAndCenters() {
        val restored = restoredWindowState(LocalSettings(), listOf(PRIMARY_SCREEN))

        assertEquals(WindowPlacement.Floating, restored.placement)
        assertEquals(DpSize(WINDOW_DEFAULT_WIDTH.dp, WINDOW_DEFAULT_HEIGHT.dp), restored.size)
        assertEquals(WindowPosition.Aligned(androidx.compose.ui.Alignment.Center), restored.position)
    }

    @Test
    fun restoredWindowStateClampsSizeBelowMinimum() {
        val saved = LocalSettings(windowWidth = 100.0, windowHeight = 100.0)

        val restored = restoredWindowState(saved, listOf(PRIMARY_SCREEN))

        assertEquals(WINDOW_MIN_WIDTH.dp, restored.size.width)
        assertEquals(WINDOW_MIN_HEIGHT.dp, restored.size.height)
    }

    @Test
    fun restoredWindowStateUsesSavedPositionWhenOnScreen() {
        val saved = LocalSettings(windowWidth = 1000.0, windowHeight = 700.0, windowX = 100.0, windowY = 100.0)

        val restored = restoredWindowState(saved, listOf(PRIMARY_SCREEN))

        assertEquals(WindowPosition.Absolute(100.dp, 100.dp), restored.position)
    }

    @Test
    fun restoredWindowStateCentersWhenSavedPositionIsOffAnyScreen() {
        val saved = LocalSettings(windowWidth = 1000.0, windowHeight = 700.0, windowX = 5000.0, windowY = 5000.0)

        val restored = restoredWindowState(saved, listOf(PRIMARY_SCREEN, SECONDARY_SCREEN))

        assertEquals(WindowPosition.Aligned(androidx.compose.ui.Alignment.Center), restored.position)
    }

    @Test
    fun restoredWindowStateForMaximizedStillReturnsRealFloatingSize() {
        // The whole point of restoring maximized/fullscreen through real floating bounds rather
        // than Dp.Unspecified: Compose Desktop applies size/position before the window is first
        // shown regardless of placement, so this is what the OS remembers as the un-maximize target.
        val saved = LocalSettings(
            windowPlacement = "maximized",
            windowWidth = 900.0,
            windowHeight = 650.0,
            windowX = 30.0,
            windowY = 40.0,
        )

        val restored = restoredWindowState(saved, listOf(PRIMARY_SCREEN))

        assertEquals(WindowPlacement.Maximized, restored.placement)
        assertEquals(DpSize(900.dp, 650.dp), restored.size)
        assertEquals(WindowPosition.Absolute(30.dp, 40.dp), restored.position)
    }

    @Test
    fun restoredWindowStateForFullscreenAlsoReturnsRealFloatingSize() {
        val saved = LocalSettings(windowPlacement = "fullscreen", windowWidth = 900.0, windowHeight = 650.0)

        val restored = restoredWindowState(saved, listOf(PRIMARY_SCREEN))

        assertEquals(WindowPlacement.Fullscreen, restored.placement)
        assertEquals(DpSize(900.dp, 650.dp), restored.size)
    }

    @Test
    fun restoredWindowStateTreatsUnknownPlacementAsFloating() {
        val saved = LocalSettings(windowPlacement = "bogus")

        val restored = restoredWindowState(saved, listOf(PRIMARY_SCREEN))

        assertEquals(WindowPlacement.Floating, restored.placement)
    }

    @Test
    fun isVisibleOnAnyScreenTrueWhenTitleBarIntersectsAScreen() {
        assertEquals(true, isVisibleOnAnyScreen(x = 100.0, y = 100.0, width = 800.0, height = 600.0, screens = listOf(PRIMARY_SCREEN)))
    }

    @Test
    fun isVisibleOnAnyScreenFalseWhenFarOffAllScreens() {
        assertEquals(false, isVisibleOnAnyScreen(x = 10000.0, y = 10000.0, width = 800.0, height = 600.0, screens = listOf(PRIMARY_SCREEN, SECONDARY_SCREEN)))
    }

    @Test
    fun isVisibleOnAnyScreenTrueOnSecondaryScreenWithNegativeOriginNeighbor() {
        // A monitor arrangement where the primary screen sits to the *right* (negative x on the
        // secondary) still needs to resolve correctly.
        val leftScreen = Rectangle(-1920, 0, 1920, 1080)
        assertEquals(
            true,
            isVisibleOnAnyScreen(x = -1000.0, y = 100.0, width = 800.0, height = 600.0, screens = listOf(leftScreen, PRIMARY_SCREEN)),
        )
    }

    @Test
    fun windowXAndYDefaultToNull() {
        val saved = LocalSettings()
        assertNull(saved.windowX)
        assertNull(saved.windowY)
    }
}
