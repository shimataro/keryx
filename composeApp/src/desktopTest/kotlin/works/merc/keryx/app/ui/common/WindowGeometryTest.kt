package works.merc.keryx.app.ui.common

import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPosition
import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Insets
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
    fun `fitWindowSize returns null for a degenerate height measurement`() {
        val density = Density(2f)
        assertNull(fitWindowSize(400.dp, 0, density, 800.dp, DpSize.Zero))
        assertNull(fitWindowSize(400.dp, -1, density, 800.dp, DpSize.Zero))
    }

    @Test
    fun `fitWindowSize always returns the given content width, ignoring how it was measured`() {
        // The Linux regression guard: an earlier version derived width from the measured content
        // pixels, which self-amplified into a runaway shrink when a modeless dialog's client area
        // was momentarily reported narrower than requested during window placement. Width must now
        // be a pure function of contentWidth (the dialog's fixed width), never of contentHeightPx or
        // density — even a near-zero content height must not perturb it.
        val fitted = fitWindowSize(640.dp, contentHeightPx = 1, Density(2f), maxHeightDp = 800.dp, DpSize.Zero)

        assertEquals(640.dp, fitted?.width)
    }

    @Test
    fun `fitWindowSize converts measured height pixels with the given density`() {
        // The dialog's own density, not the owner window's: at owner density 2 / dialog density 1 a
        // 640dp-tall content used to become a 320pt window (over-wrapped content).
        val fitted = fitWindowSize(400.dp, 600, Density(2f), maxHeightDp = 800.dp, decorationAllowance = DpSize.Zero)

        assertEquals(DpSize(400.dp, 300.dp), fitted)
    }

    @Test
    fun `fitWindowSize honours a fractional density`() {
        val fitted = fitWindowSize(400.dp, 600, Density(1.5f), maxHeightDp = 800.dp, decorationAllowance = DpSize.Zero)

        assertEquals(400f, fitted!!.width.value, 0.01f)
        assertEquals(400f, fitted.height.value, 0.01f)
    }

    @Test
    fun `fitWindowSize leaves content shorter than the cap untouched`() {
        val fitted = fitWindowSize(400.dp, 600, Density(2f), maxHeightDp = 500.dp, decorationAllowance = DpSize.Zero)

        assertEquals(300.dp, fitted?.height)
    }

    @Test
    fun `fitWindowSize clamps the content height to the cap`() {
        val fitted = fitWindowSize(400.dp, 2000, Density(2f), maxHeightDp = 500.dp, decorationAllowance = DpSize.Zero)

        assertEquals(500.dp, fitted?.height)
    }

    @Test
    fun `fitWindowSize adds the decoration allowance to both axes on top of the clamped height`() {
        // The Windows/Linux path: the height cap bounds how much of the screen the *content* may
        // claim, so the resulting window may exceed it by exactly the OS decoration's height. Width
        // has no cap to exceed, so its allowance is added unconditionally.
        val fitted = fitWindowSize(400.dp, 2000, Density(2f), maxHeightDp = 500.dp, DpSize(8.dp, 40.dp))

        assertEquals(408.dp, fitted?.width)
        assertEquals(540.dp, fitted?.height)
    }

    @Test
    fun `fitWindowSize adds nothing when the decoration allowance is zero`() {
        // The macOS path: the merged title row is already part of the measured content.
        val fitted = fitWindowSize(400.dp, 600, Density(2f), maxHeightDp = 800.dp, decorationAllowance = DpSize.Zero)

        assertEquals(400.dp, fitted?.width)
        assertEquals(300.dp, fitted?.height)
    }

    // --- decorationAllowanceFor ---------------------------------------------------------------

    @Test
    fun `decorationAllowanceFor is always zero on macOS regardless of insets`() {
        assertEquals(DpSize.Zero, decorationAllowanceFor(Insets(28, 0, 0, 0), isMacOs = true, fallbackHeight = 40.dp))
    }

    @Test
    fun `decorationAllowanceFor uses real insets on both axes once the window manager reports them`() {
        val allowance = decorationAllowanceFor(Insets(32, 4, 0, 6), isMacOs = false, fallbackHeight = 40.dp)

        assertEquals(DpSize(10.dp, 32.dp), allowance)
    }

    @Test
    fun `decorationAllowanceFor falls back to a height-only guess when insets read all zero`() {
        // The AWT/X11 timing quirk: insets read as (0,0,0,0) before the window is
        // reparented/decorated. The fallback must never claim a width allowance that isn't real.
        val allowance = decorationAllowanceFor(Insets(0, 0, 0, 0), isMacOs = false, fallbackHeight = 40.dp)

        assertEquals(DpSize(0.dp, 40.dp), allowance)
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

    // --- applyWindowGeometry -----------------------------------------------------------------

    @Test
    fun `applyWindowGeometry applies the size and the location in a single bounds change`() {
        // The regression guard: applying the size on its own left the window resizable-but-unmoved
        // for however long the position took to arrive, and a frame painted in that gap showed the
        // dialog at its final size but at AWT's default location for a fresh Window (the screen
        // origin plus the screen insets — the top-left corner).
        val frame = boundsCountingFrame()

        applyWindowGeometry(frame, DpSize(640.dp, 519.dp), WindowPosition.Absolute(436.dp, 371.dp))

        assertEquals(1, frame.setBoundsCalls, "size and location must reach AWT as one bounds change")
        assertBounds(frame, x = 436, y = 371, width = 640, height = 519)
    }

    @Test
    fun `applyWindowGeometry keeps the current location when only a size is given`() {
        val frame = boundsCountingFrame()

        applyWindowGeometry(frame, DpSize(640.dp, 519.dp), position = null)

        assertBounds(frame, x = 10, y = 20, width = 640, height = 519)
    }

    @Test
    fun `applyWindowGeometry keeps the current size when only a position is given`() {
        val frame = boundsCountingFrame()

        applyWindowGeometry(frame, size = null, position = WindowPosition.Absolute(436.dp, 371.dp))

        assertBounds(frame, x = 436, y = 371, width = 100, height = 50)
    }

    @Test
    fun `applyWindowGeometry ignores a position that carries no coordinates`() {
        // PlatformDefault is what centeredPosition returns with no owner window; it has no x/y to
        // apply, so Compose resolves it against its own window-cascade tracker instead.
        val frame = boundsCountingFrame()

        applyWindowGeometry(frame, DpSize(640.dp, 519.dp), WindowPosition.PlatformDefault)

        assertBounds(frame, x = 10, y = 20, width = 640, height = 519)
    }

    @Test
    fun `applyWindowGeometry does not touch the window when there is nothing to apply`() {
        val frame = boundsCountingFrame()

        applyWindowGeometry(frame, size = null, position = null)
        applyWindowGeometry(frame, size = null, position = WindowPosition.PlatformDefault)

        assertEquals(0, frame.setBoundsCalls)
    }

    @Test
    fun `applyWindowGeometry rounds Dp to whole AWT points`() {
        // Must match Compose's own Dp.value.roundToInt(), or sizeMatches would never agree with
        // what the window reports back.
        val frame = boundsCountingFrame()

        applyWindowGeometry(frame, DpSize(639.4.dp, 519.6.dp), WindowPosition.Absolute(436.6.dp, 371.4.dp))

        assertBounds(frame, x = 437, y = 371, width = 639, height = 520)
    }

    @Test
    fun `applyWindowGeometry never requests a negative size`() {
        val frame = boundsCountingFrame()

        applyWindowGeometry(frame, DpSize((-10).dp, (-10).dp), position = null)

        assertBounds(frame, x = 10, y = 20, width = 0, height = 0)
    }

    @Test
    fun `applyWindowGeometry floors size to the given minimum on each axis independently`() {
        // The safety net behind fitWindowSize no longer deriving width from measurement: even a
        // pathological requested size can never collapse a resizable=false dialog below minSize,
        // since the user could not drag it back open.
        val frame = boundsCountingFrame()

        applyWindowGeometry(frame, DpSize(1.dp, 700.dp), position = null, minSize = DpSize(640.dp, 100.dp))

        assertBounds(frame, x = 10, y = 20, width = 640, height = 700)
    }

    @Test
    fun `applyWindowGeometry defaults the minimum to zero`() {
        val frame = boundsCountingFrame()

        applyWindowGeometry(frame, DpSize(1.dp, 1.dp), position = null)

        assertBounds(frame, x = 10, y = 20, width = 1, height = 1)
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
    fun `nextDialogFit grants a fresh attempt budget when the target changes after being reached`() {
        // The legitimate case: e.g. switching tabs after the previous tab's height was
        // successfully applied.
        var state = nextDialogFit(DialogFitState(), TARGET, TARGET, repositionOnResize = false).state
        assertTrue(state.targetReached)

        val newTarget = nextDialogFit(state, GROWN_TARGET, TARGET, repositionOnResize = false)

        assertTrue(newTarget.applySize)
        assertEquals(1, newTarget.state.corrections)
        assertFalse(newTarget.state.gaveUpReported)
    }

    @Test
    fun `nextDialogFit does not grant a fresh attempt budget when the target changes before ever being reached`() {
        // The Linux width-collapse regression this whole field exists to prevent: a target that
        // keeps moving before the window ever actually matches it must not get a fresh correction
        // budget on every change, or the cap never actually stops anything — which is exactly how a
        // modeless dialog's width ran away to ~1dp instead of settling.
        var state = DialogFitState()
        repeat(MAX_FIT_CORRECTIONS) {
            state = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false).state
        }
        assertFalse(state.targetReached)

        val newTarget = nextDialogFit(state, GROWN_TARGET, PLACEHOLDER, repositionOnResize = false)

        assertFalse(
            newTarget.applySize,
            "the budget must not refill for a target that keeps moving before ever being reached",
        )
    }

    @Test
    fun `nextDialogFit does not re-report giving up when a still-unreached target keeps changing`() {
        var state = DialogFitState()
        repeat(MAX_FIT_CORRECTIONS) {
            state = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false).state
        }
        state = nextDialogFit(state, TARGET, PLACEHOLDER, repositionOnResize = false).also {
            assertTrue(it.reportGiveUp)
        }.state

        val afterTargetChange = nextDialogFit(state, GROWN_TARGET, PLACEHOLDER, repositionOnResize = false)

        assertFalse(
            afterTargetChange.reportGiveUp,
            "already reported give-up must not repeat just because the (still unreached) target moved again",
        )
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

    /** A never-shown [Frame] that counts how many times AWT is asked to change its bounds. */
    private class BoundsCountingFrame : Frame() {
        var setBoundsCalls = 0

        override fun setBounds(x: Int, y: Int, width: Int, height: Int) {
            setBoundsCalls++
            super.setBounds(x, y, width, height)
        }
    }

    /** A [BoundsCountingFrame] pre-placed at a known geometry, with the setup call not counted. */
    private fun boundsCountingFrame(): BoundsCountingFrame =
        BoundsCountingFrame().apply {
            setBounds(10, 20, 100, 50)
            setBoundsCalls = 0
        }

    private fun assertBounds(frame: Frame, x: Int, y: Int, width: Int, height: Int) {
        assertEquals(x, frame.x, "x")
        assertEquals(y, frame.y, "y")
        assertEquals(width, frame.width, "width")
        assertEquals(height, frame.height, "height")
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
