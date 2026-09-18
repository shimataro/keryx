package works.merc.keryx.app.platform

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// This composable is platform-independent Compose code (no Android-only API), but only Android's
// PlatformScrollbar.android.kt calls it — desktop keeps its own draggable VerticalScrollbar (see
// PlatformScrollbar.desktop.kt). It lives here rather than in androidMain so a future Compose
// target that needs the same non-interactive fading idiom (e.g. iOS, per external-spec.md §2's
// "planned") can reuse it without copying ~100 lines across source sets first.
//
// Android has no equivalent of desktop's draggable androidx.compose.foundation.VerticalScrollbar
// (that API is desktop-only), so this draws a thin, non-interactive overlay indicator instead: the
// same SCROLLBARS_INSIDE_OVERLAY idiom Android's own View-based lists use, opaque while scrolling
// and faded out shortly after it stops.
//
// Compose's LazyColumn / Modifier.verticalScroll do not draw any scrollbar on their own — unlike
// the View system's RecyclerView/ScrollView, which paint one unprompted via android:scrollbars.
// Without this, Android silently has no scroll-position indicator at all, on device and emulator
// alike.

private val SCROLL_INDICATOR_THICKNESS = 4.dp
private val SCROLL_INDICATOR_CORNER_RADIUS = 2.dp

/** Horizontal gap between the thumb's outer edge and the pane's side (the end edge in LTR). */
private val SCROLL_INDICATOR_SIDE_MARGIN = 2.dp

/** Vertical gap between the track's top/bottom and the pane's own top/bottom (or its insets). */
private val SCROLL_INDICATOR_TRACK_VERTICAL_MARGIN = 2.dp

private val SCROLL_INDICATOR_MIN_LENGTH = 32.dp

/** Deliberately well above AOSP's own ~300ms default (ViewConfiguration.SCROLL_BAR_DEFAULT_DELAY)
 *  — the indicator is the only scroll-position cue on Android, so it stays a beat longer. */
private const val SCROLL_INDICATOR_HIDE_DELAY_MS = 800L

/** Matches ViewConfiguration.SCROLL_BAR_FADE_DURATION. */
private const val SCROLL_INDICATOR_FADE_OUT_MS = 250

/**
 * A non-interactive vertical scroll-position indicator: a single [Spacer] painted with
 * [drawBehind], opaque while [state] is scrolling and faded out [SCROLL_INDICATOR_HIDE_DELAY_MS]
 * after it last reported scrolling.
 *
 * [state]'s own `scrollIndicatorState` and scroll-in-progress flag are read only inside
 * [snapshotFlow] / the draw phase, never in composition — so a scroll never recomposes the pane
 * hosting this indicator, which matters given the article list's own LazyColumn item-reuse crash
 * history (see known-issues.md). This is the only node the indicator adds, and it carries no
 * pointer input at all, so it never enters hit testing — see the `ui-guidelines` skill's "Scroll
 * indicators" section for the full set of rules this relies on, including why that matters to
 * `FeedListPane`'s reorder-drag host specifically.
 *
 * The effect is keyed on [state] itself, not just once per call site: `ArticleListPane` passes
 * either its base or its search `LazyListState` through the same `VerticalScrollbarIfNeeded` call
 * depending on `searchActive`, and keying on the state instance (rather than `Unit`/a `remember`ed
 * `Animatable`) is what makes the effect restart — and start watching the *new* state's own
 * scrolling — when that swap happens, instead of staying latched onto whichever state was current
 * at first composition.
 *
 * No content-description or other semantics are attached: this is a decorative echo of state the
 * scrollable content's own semantics already expose, not a control of its own.
 *
 * [state]'s `isScrollInProgress` also reports `true` for a programmatic scroll (e.g.
 * `scrollToIndexIfNeeded`, or a keyboard J/K jump) — matching `RecyclerView.smoothScrollBy`'s own
 * native behavior, this is intentional rather than a leak of desktop-only semantics.
 *
 * The draw phase allocates nothing per frame: [trackStartInsetPx]/[trackEndInsetPx] and
 * [scrollIndicatorLengthFraction]/[scrollIndicatorStartFraction] all take and return primitives
 * rather than a `Pair` or a data class. `ScrollIndicatorState.scrollOffset`/`contentSize` do each
 * still walk `LazyListState`'s visible items once (`visibleItemsAverageSize()`), so a `LazyListState`
 * ends up computing that average twice per frame while scrolling — that duplication is on the
 * framework side of [state], not something this composable can avoid, and is accepted rather than
 * worked around here.
 *
 * The fade animation's current value is read inside [graphicsLayer]'s block, not inside
 * [drawBehind] — a `graphicsLayer` alpha update only invalidates that layer's own composited output,
 * while a `drawBehind` read would invalidate this pane's whole display list on every animation
 * frame of the ~250ms fade-out (this Spacer has no layer of its own otherwise, so that invalidation
 * would propagate up to the nearest one — the pane hosting it). `CompositingStrategy.ModulateAlpha`
 * avoids the extra offscreen buffer a plain alpha layer would otherwise allocate, which a single
 * opaque shape like this thumb doesn't need.
 */
@Composable
internal fun BoxScope.ScrollIndicatorOverlay(
    state: ScrollableState,
    trackStartInsetPx: () -> Float,
    trackEndInsetPx: () -> Float,
) {
    val minLengthPx = with(LocalDensity.current) { SCROLL_INDICATOR_MIN_LENGTH.toPx() }
    // 0f = hidden, 1f = fully opaque — read directly as the layer's own alpha below, with no
    // separate max-alpha scaling: outline (unlike a partly-transparent onSurface) is already a
    // contrast-safe color at full opacity, so "faded in" and "opaque" mean the same thing here.
    val fade = remember(state) { Animatable(0f) }
    LaunchedEffect(state) {
        snapshotFlow { state.isScrollInProgress }.collectLatest { scrolling ->
            if (scrolling) {
                fade.snapTo(1f) // matches View.awakenScrollBars(): snaps opaque, no fade-in
            } else {
                delay(SCROLL_INDICATOR_HIDE_DELAY_MS)
                fade.animateTo(0f, tween(SCROLL_INDICATOR_FADE_OUT_MS))
            }
        }
    }
    // outline is M3's own contrast-guaranteed role for a decorative boundary/indicator against
    // surface — at full alpha it clears WCAG 1.4.11's 3:1 non-text contrast minimum against the
    // app's fixed teal palette (measured ~4.45:1 light / ~5.4:1 dark) and against Material You's
    // dynamic palette, unlike a partly-transparent onSurface, which this indicator is the sole
    // scroll-position cue on Android and has no hover-darkens-further fallback for.
    val color = MaterialTheme.colorScheme.outline
    Spacer(
        Modifier.matchParentSize()
            .graphicsLayer {
                alpha = fade.value
                compositingStrategy = CompositingStrategy.ModulateAlpha
            }
            .drawBehind {
                val trackTop = trackStartInsetPx() + SCROLL_INDICATOR_TRACK_VERTICAL_MARGIN.toPx()
                val trackBottom = size.height - trackEndInsetPx() - SCROLL_INDICATOR_TRACK_VERTICAL_MARGIN.toPx()
                val trackLength = (trackBottom - trackTop).coerceAtLeast(0f)
                if (trackLength <= 0f) return@drawBehind
                val indicatorState = state.scrollIndicatorState ?: return@drawBehind
                val lengthFraction = scrollIndicatorLengthFraction(
                    indicatorState.contentSize,
                    indicatorState.viewportSize,
                    minLengthFraction(minLengthPx, trackLength),
                )
                if (lengthFraction <= 0f) return@drawBehind
                val startFraction = scrollIndicatorStartFraction(
                    indicatorState.scrollOffset,
                    indicatorState.contentSize,
                    indicatorState.viewportSize,
                    lengthFraction,
                )
                val thicknessPx = SCROLL_INDICATOR_THICKNESS.toPx()
                val x = if (layoutDirection == LayoutDirection.Ltr) {
                    size.width - SCROLL_INDICATOR_SIDE_MARGIN.toPx() - thicknessPx
                } else {
                    SCROLL_INDICATOR_SIDE_MARGIN.toPx()
                }
                drawRoundRect(
                    color = color,
                    topLeft = Offset(x, trackTop + startFraction * trackLength),
                    size = Size(thicknessPx, lengthFraction * trackLength),
                    cornerRadius = CornerRadius(SCROLL_INDICATOR_CORNER_RADIUS.toPx()),
                )
            },
    )
}
