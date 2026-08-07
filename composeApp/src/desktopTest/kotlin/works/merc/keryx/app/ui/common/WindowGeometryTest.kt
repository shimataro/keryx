package works.merc.keryx.app.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import java.awt.Frame
import java.awt.GraphicsEnvironment
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Covers the pure parts of the dialog window geometry: [centeredPosition]'s owner-centering and
 * screen-bounds clamp, the auto-fit arithmetic ([fitWindowSize], [sizeMatches]) and the
 * drift-correction state machine ([nextDialogFit]). Actually applying a size to a real
 * `DialogWindow` is left to the manual checks in `docs/testing.md`.
 *
 * The centering tests use a real, never-shown [Frame] as the owner — [java.awt.Window]'s
 * constructor eagerly resolves a [java.awt.GraphicsConfiguration] from the default screen device,
 * so `frame.graphicsConfiguration` is already meaningful before the frame has a peer, and
 * `currentScreenBounds` can read real bounds from it.
 */
class WindowGeometryTest {

    private val screenBounds = GraphicsEnvironment.getLocalGraphicsEnvironment()
        .defaultScreenDevice.defaultConfiguration.bounds

    @Test
    fun `centeredPosition returns PlatformDefault when owner is null`() {
        assertEquals(WindowPosition.PlatformDefault, centeredPosition(owner = null, size = DpSize(400.dp, 300.dp)))
    }

    @Test
    fun `centeredPosition centers over an owner that fits fully on screen`() {
        val owner = Frame().apply {
            setBounds(screenBounds.x, screenBounds.y, screenBounds.width / 2, screenBounds.height / 2)
        }
        val size = DpSize(100.dp, 80.dp)

        val position = centeredPosition(owner, size)

        val expectedX = owner.x.dp + (owner.width.dp - size.width) / 2f
        val expectedY = owner.y.dp + (owner.height.dp - size.height) / 2f
        assertEquals(expectedX, position.x)
        assertEquals(expectedY, position.y)
    }

    @Test
    fun `centeredPosition clamps to screen bounds when naive centering would spill off the right and bottom edges`() {
        // Owner sits at the screen's bottom-right corner, much smaller than the dialog, so naively
        // centering the dialog over it would place most of the dialog off-screen.
        val owner = Frame().apply {
            setBounds(screenBounds.x + screenBounds.width - 10, screenBounds.y + screenBounds.height - 10, 10, 10)
        }
        val size = DpSize(400.dp, 300.dp)

        val position = centeredPosition(owner, size)

        val maxX = screenBounds.x.dp + screenBounds.width.dp - size.width
        val maxY = screenBounds.y.dp + screenBounds.height.dp - size.height
        assertEquals(maxX, position.x)
        assertEquals(maxY, position.y)
    }

    // --- fitWindowSize -----------------------------------------------------------------------

    @Test
    fun `fitWindowSize returns null for a degenerate measurement`() {
        val density = Density(2f)
        assertNull(fitWindowSize(IntSize(0, 100), density, 800.dp, 0.dp))
        assertNull(fitWindowSize(IntSize(100, 0), density, 800.dp, 0.dp))
        assertNull(fitWindowSize(IntSize(0, 0), density, 800.dp, 0.dp))
    }

    @Test
    fun `fitWindowSize converts measured pixels with the given density`() {
        // The dialog's own density, not the owner window's: at owner density 2 / dialog density 1 a
        // 640dp-wide content used to become a 320pt window (clipped tab bar, over-wrapped content).
        val fitted = fitWindowSize(IntSize(800, 600), Density(2f), maxHeightDp = 800.dp, decorationAllowance = 0.dp)

        assertEquals(DpSize(400.dp, 300.dp), fitted)
    }

    @Test
    fun `fitWindowSize honours a fractional density`() {
        val fitted = fitWindowSize(IntSize(800, 600), Density(1.5f), maxHeightDp = 800.dp, decorationAllowance = 0.dp)

        assertEquals(533.333f, fitted!!.width.value, 0.01f)
        assertEquals(400f, fitted.height.value, 0.01f)
    }

    @Test
    fun `fitWindowSize leaves content shorter than the cap untouched`() {
        val fitted = fitWindowSize(IntSize(800, 600), Density(2f), maxHeightDp = 500.dp, decorationAllowance = 0.dp)

        assertEquals(300.dp, fitted?.height)
    }

    @Test
    fun `fitWindowSize clamps the content height to the cap`() {
        val fitted = fitWindowSize(IntSize(800, 2000), Density(2f), maxHeightDp = 500.dp, decorationAllowance = 0.dp)

        assertEquals(500.dp, fitted?.height)
    }

    @Test
    fun `fitWindowSize adds the decoration allowance on top of the clamped height`() {
        // The Windows/Linux path: the cap bounds how much of the screen the *content* may claim, so
        // the resulting window may exceed it by exactly the OS decoration's height.
        val fitted = fitWindowSize(IntSize(800, 2000), Density(2f), maxHeightDp = 500.dp, decorationAllowance = 40.dp)

        assertEquals(540.dp, fitted?.height)
    }

    @Test
    fun `fitWindowSize adds nothing when the decoration allowance is zero`() {
        // The macOS path: the merged title row is already part of the measured content.
        val fitted = fitWindowSize(IntSize(800, 600), Density(2f), maxHeightDp = 800.dp, decorationAllowance = 0.dp)

        assertEquals(300.dp, fitted?.height)
    }

    // --- sizeMatches -------------------------------------------------------------------------

    @Test
    fun `sizeMatches accepts a difference within the rounding tolerance`() {
        val target = DpSize(640.dp, 519.dp)

        assertTrue(sizeMatches(DpSize(640.dp, 519.37.dp), target))
        assertTrue(sizeMatches(DpSize(642.dp, 517.dp), target), "the tolerance boundary is inclusive")
    }

    @Test
    fun `sizeMatches rejects a width-only mismatch`() {
        // The mixed-DPI symptom: right height, half the width.
        assertFalse(sizeMatches(DpSize(320.dp, 519.dp), DpSize(640.dp, 519.dp)))
    }

    @Test
    fun `sizeMatches rejects a height-only mismatch`() {
        // The stuck-placeholder symptom: right width, the 240dp placeholder height.
        assertFalse(sizeMatches(DpSize(640.dp, 240.dp), DpSize(640.dp, 519.dp)))
    }

    // --- nextDialogFit -----------------------------------------------------------------------

    @Test
    fun `nextDialogFit applies size and position for the first measurement`() {
        val decision = nextDialogFit(DialogFitState(), TARGET, PLACEHOLDER, repositionOnResize = true)

        assertTrue(decision.applySize)
        assertTrue(decision.applyPosition)
        assertEquals(TARGET, decision.state.target)
        assertEquals(1, decision.state.corrections)
        assertTrue(decision.state.positionApplied)
    }

    @Test
    fun `nextDialogFit places the dialog once even when the window already fits`() {
        val decision = nextDialogFit(DialogFitState(), TARGET, TARGET, repositionOnResize = false)

        assertFalse(decision.applySize)
        assertTrue(decision.applyPosition)
        assertEquals(0, decision.state.corrections)
    }

    @Test
    fun `nextDialogFit corrects a size applied behind Compose's back after the target had settled`() {
        // The regression this whole guard exists for. Replays what the settings dialog was observed
        // doing: fitted from the placeholder, settled, then clobbered by Compose's own asynchronous
        // application of the initial DialogState size. The old bounded loop had already broken out
        // at step 2 and never saw step 3, so the dialog stayed 240dp tall for its whole lifetime.
        var state = DialogFitState()

        val fromPlaceholder = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false)
        assertTrue(fromPlaceholder.applySize)
        assertTrue(fromPlaceholder.applyPosition)
        state = fromPlaceholder.state

        val settled = nextDialogFit(state, TARGET, TARGET, repositionOnResize = false)
        assertFalse(settled.applySize)
        assertFalse(settled.applyPosition)
        state = settled.state

        val clobbered = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false)
        assertTrue(clobbered.applySize)
        assertFalse(clobbered.applyPosition)
    }

    @Test
    fun `nextDialogFit corrects the un-sized size macOS gives a freshly realized dialog`() {
        var state = nextDialogFit(DialogFitState(), TARGET, PLACEHOLDER, repositionOnResize = false).state
        state = nextDialogFit(state, TARGET, TARGET, repositionOnResize = false).state

        val clobbered = nextDialogFit(state, TARGET, MAC_UNSIZED, repositionOnResize = false)

        assertTrue(clobbered.applySize)
    }

    @Test
    fun `nextDialogFit does not re-place a dialog when only correcting drift`() {
        // A dialog the user has dragged elsewhere must not snap back because of a drift correction.
        var state = nextDialogFit(DialogFitState(), TARGET, PLACEHOLDER, repositionOnResize = true).state
        state = nextDialogFit(state, TARGET, TARGET, repositionOnResize = true).state

        val drift = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = true)

        assertTrue(drift.applySize)
        assertFalse(drift.applyPosition)
    }

    @Test
    fun `nextDialogFit re-places a repositioning dialog when its target changes`() {
        // The add-feed dialog growing to show its candidate list re-centers, as before.
        val state = nextDialogFit(DialogFitState(), TARGET, TARGET, repositionOnResize = true).state

        val grown = nextDialogFit(state, GROWN_TARGET, TARGET, repositionOnResize = true)

        assertTrue(grown.applySize)
        assertTrue(grown.applyPosition)
    }

    @Test
    fun `nextDialogFit keeps a non-repositioning dialog anchored across target changes`() {
        // The settings dialog's top edge must not move when a tab changes its height.
        val state = nextDialogFit(DialogFitState(), TARGET, TARGET, repositionOnResize = false).state

        val grown = nextDialogFit(state, GROWN_TARGET, TARGET, repositionOnResize = false)

        assertTrue(grown.applySize)
        assertFalse(grown.applyPosition)
    }

    @Test
    fun `nextDialogFit stops correcting after the per-target attempt cap`() {
        var state = DialogFitState()
        repeat(MAX_FIT_CORRECTIONS) { attempt ->
            val decision = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false)
            assertTrue(decision.applySize, "attempt ${attempt + 1} should still be spent")
            assertFalse(decision.reportGiveUp)
            state = decision.state
        }

        val exhausted = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false)

        assertFalse(exhausted.applySize)
    }

    @Test
    fun `nextDialogFit reports giving up once per target`() {
        var state = DialogFitState()
        repeat(MAX_FIT_CORRECTIONS) {
            state = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false).state
        }

        val firstGiveUp = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false)
        assertTrue(firstGiveUp.reportGiveUp)
        state = firstGiveUp.state

        val secondGiveUp = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false)
        assertFalse(secondGiveUp.reportGiveUp, "the warning must not repeat on every drift event")
    }

    @Test
    fun `nextDialogFit grants a fresh attempt budget when the target changes`() {
        var state = DialogFitState()
        repeat(MAX_FIT_CORRECTIONS + 1) {
            state = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false).state
        }

        val newTarget = nextDialogFit(state, GROWN_TARGET, PLACEHOLDER, repositionOnResize = false)

        assertTrue(newTarget.applySize)
        assertEquals(1, newTarget.state.corrections)
        assertFalse(newTarget.state.gaveUpReported)
    }

    @Test
    fun `nextDialogFit does not spend attempts on events where the window already matches`() {
        // Otherwise the cap would be consumed by the guard's own success feedback rather than by
        // genuine fights with the window manager.
        var state = nextDialogFit(DialogFitState(), TARGET, PLACEHOLDER, repositionOnResize = false).state
        assertEquals(1, state.corrections)

        state = nextDialogFit(state, TARGET, TARGET, repositionOnResize = false).state
        assertEquals(1, state.corrections)

        state = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false).state
        assertEquals(2, state.corrections)
    }

    private companion object {
        /** A settled settings-dialog fit: [KeryxTabDialog]'s fixed width, a representative height. */
        val TARGET = DpSize(640.dp, 519.dp)

        /** What `placeholderSize` gives that dialog before its content has been measured. */
        val PLACEHOLDER = DpSize(640.dp, 240.dp)

        /** Roughly what macOS gives a `DialogWindow` whose size has not been applied yet. */
        val MAC_UNSIZED = DpSize(80.dp, 28.dp)

        /** A taller fit, i.e. what a tab switch or an expanding dialog produces. */
        val GROWN_TARGET = DpSize(640.dp, 700.dp)
    }
}
