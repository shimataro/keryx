package works.merc.keryx.app.platform

import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Composed-level coverage for [ScrollIndicatorOverlay] itself, driven through a real
 * [LazyListState] rather than the pure functions [ScrollIndicatorGeometryTest] already covers.
 * This is exactly where the regression this composable actually shipped with — the fade effect
 * staying latched onto whichever `ScrollableState` was current at first composition (fixed by
 * keying `LaunchedEffect`/`Animatable` on `state` itself) — was only ever caught by hand.
 *
 * `ScrollIndicatorOverlay` is called directly (bypassing `VerticalScrollbarIfNeeded`'s
 * `expect`/`actual` dispatch entirely): it is itself platform-independent commonMain code with no
 * Android-only API, so it compiles and runs against the desktop target just as well — the dispatch
 * only decides *whether* this or desktop's own draggable `VerticalScrollbar` gets called, not how
 * this composable itself behaves.
 *
 * The scroll itself is driven through a `LaunchedEffect` keyed on the same `state`/trigger the test
 * flips, rather than a bare `runBlocking { state.scroll { ... } } }` off to the side — that ties the
 * scroll's suspension into the composition's own test-managed coroutine, so `waitForIdle()`/
 * [MainTestClock.advanceTimeUntil] reliably wait for it (and for `ScrollIndicatorOverlay`'s own
 * effect to react) instead of racing a scroll that already finished before the test ever asked to
 * wait. The scroll is animated ([animateScrollBy], not a single [ScrollScope.scrollBy] call) so
 * `isScrollInProgress` stays observably `true` for a real span of virtual time — a single, instant
 * `scrollBy` can flip it true→false within one coroutine resumption, too fast for the
 * `snapshotFlow` this composable's own effect is built on to ever see the `true` in between.
 *
 * **Why the fade-out itself is not asserted here.** `ScrollIndicatorOverlay` reads its alpha inside
 * `Modifier.graphicsLayer { alpha = fade.value; compositingStrategy = ModulateAlpha }` (see that
 * file's KDoc for why `ModulateAlpha`). Verified with a minimal, isolated repro against this
 * project's Compose Multiplatform version: once a `graphicsLayer` under `ModulateAlpha` has been
 * captured once via `captureToImage()`, a *later, isolated* change to its `alpha` alone (nothing
 * else in the layout tree changing at the same time — exactly what `delay` + `animateTo` produce
 * once scrolling has stopped) is never reflected in a subsequent `captureToImage()` call, in either
 * direction (fade to 0 or snap back to 1) — the composited pixels stay frozen at whatever alpha was
 * current the last time some *other* layout change (e.g. the list actually scrolling) forced that
 * layer to be freshly composited. Becoming opaque *while scrolling* (as both tests below check) is
 * exactly such a case — the scroll's own continuous relayout carries the alpha change with it — so
 * it captures reliably; fading out afterwards, with nothing else moving, does not. This is a test
 * environment limitation, not a production bug (the real fade-out is part of routine manual QA, see
 * `docs/testing.md`), so it is not asserted here rather than papered over with a flaky or
 * non-discriminating check.
 */
@OptIn(ExperimentalTestApi::class)
class ScrollIndicatorOverlayTest {

    private val hostWidthDp = 100.dp
    private val hostHeightDp = 300.dp

    /** Long enough that `isScrollInProgress` is guaranteed to still be observably `true` right
     *  after starting the scroll — see the class KDoc for why an animated scroll, not a plain one. */
    private val scrollAnimationMs = 600

    @Composable
    private fun Host(state: LazyListState, scrollTrigger: Int = 0, showIndicator: Boolean = true) {
        LaunchedEffect(state, scrollTrigger) {
            if (scrollTrigger > 0) state.animateScrollBy(2000f, tween(scrollAnimationMs))
        }
        // MaterialTheme.colorScheme.outline (what ScrollIndicatorOverlay actually draws with) is
        // otherwise resolved from LocalColorScheme's own default, not necessarily this test's
        // white background — wrapping in a real MaterialTheme is what production code does too.
        MaterialTheme {
            Box(Modifier.testTag(HOST_TAG).size(hostWidthDp, hostHeightDp).background(Color.White)) {
                LazyColumn(state = state, modifier = Modifier.fillMaxSize()) {
                    items(50) { Box(Modifier.fillMaxWidth().height(40.dp)) }
                }
                if (showIndicator) {
                    ScrollIndicatorOverlay(state = state, trackStartInsetPx = { 0f }, trackEndInsetPx = { 0f })
                }
            }
        }
    }

    /** Every pixel in the rightmost 10dp strip of the host — wide enough to contain the thumb
     *  (side margin + thickness) regardless of their exact values, without depending on
     *  `ScrollIndicatorOverlay.kt`'s own file-private constants. */
    private fun androidx.compose.ui.test.ComposeUiTest.rightEdgeStripColors(): List<Color> {
        val widthPx = with(density) { hostWidthDp.roundToPx() }
        val heightPx = with(density) { hostHeightDp.roundToPx() }
        val stripStartPx = widthPx - with(density) { 10.dp.roundToPx() }
        val pixels = onNodeWithTag(HOST_TAG).captureToImage().toPixelMap()
        return buildList {
            for (x in stripStartPx until widthPx) {
                for (y in 0 until heightPx) {
                    add(pixels[x, y])
                }
            }
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.thumbIsVisible() = rightEdgeStripColors().any { it != Color.White }

    /**
     * Counts only nodes that actually carry semantics *properties* (a role, an action, a content
     * description, …) — not every [SemanticsNode] in the tree. A bare `Spacer` with no `semantics {}`
     * block of its own still shows up as a structural, property-less node (Compose's tree includes
     * one for every distinct `LayoutNode`), so counting raw node presence would flag that as an
     * added node even though it is invisible to a screen reader and irrelevant to "does the
     * indicator add anything a11y-visible" — which is what this test actually cares about.
     */
    private fun countNodesWithSemanticsProperties(node: SemanticsNode): Int =
        (if (node.config.none()) 0 else 1) + node.children.sumOf { countNodesWithSemanticsProperties(it) }

    @Test
    fun theThumbIsHiddenUntilScrollingStartsThenBecomesOpaque() = runDesktopComposeUiTest {
        val state = LazyListState()
        var scrollTrigger by mutableStateOf(0)
        mainClock.autoAdvance = false
        setContent { Host(state, scrollTrigger) }
        waitForIdle()
        assertTrue(!thumbIsVisible(), "no thumb before any scroll")

        scrollTrigger++
        mainClock.advanceTimeUntil(2_000) { thumbIsVisible() }
        waitForIdle()
        assertTrue(thumbIsVisible(), "thumb should be opaque as soon as scrolling starts")
    }

    @Test
    fun theFadeEffectFollowsAScrollableStateSwap() = runDesktopComposeUiTest {
        val state1 = LazyListState()
        val state2 = LazyListState()
        var activeState by mutableStateOf<LazyListState>(state1)
        var scrollTrigger by mutableStateOf(0)

        mainClock.autoAdvance = false
        setContent { Host(activeState, scrollTrigger) }
        waitForIdle()
        assertTrue(!thumbIsVisible(), "no thumb before any scroll")

        // Swap to state2 before state1 is ever scrolled, then scroll only state2. The regression
        // this guards against left ScrollIndicatorOverlay's own fade effect watching whichever
        // ScrollableState was current at first composition (state1) instead of restarting on the
        // swap, so state2's own isScrollInProgress was never observed and the thumb never appeared
        // no matter how much state2 scrolled.
        activeState = state2
        waitForIdle()
        assertTrue(!thumbIsVisible(), "still no thumb right after the swap — state2 hasn't scrolled yet either")

        scrollTrigger++
        mainClock.advanceTimeUntil(2_000) { thumbIsVisible() }
        waitForIdle()
        assertTrue(thumbIsVisible(), "thumb should follow the swap and react to state2's own scroll")
    }

    @Test
    fun theIndicatorAddsNoSemanticsNodeOfItsOwn() = runDesktopComposeUiTest {
        val state = LazyListState()
        var showIndicator by mutableStateOf(false)
        setContent { Host(state, showIndicator = showIndicator) }
        waitForIdle()
        val without = countNodesWithSemanticsProperties(onNodeWithTag(HOST_TAG).fetchSemanticsNode())

        showIndicator = true
        waitForIdle()
        val with = countNodesWithSemanticsProperties(onNodeWithTag(HOST_TAG).fetchSemanticsNode())

        assertEquals(without, with, "the indicator must not add a semantics node of its own")
    }

    private companion object {
        const val HOST_TAG = "scroll-indicator-overlay-host"
    }
}
