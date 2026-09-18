package works.merc.keryx.app.platform

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest

// Android has no equivalent of desktop's draggable androidx.compose.foundation.VerticalScrollbar
// (that API is desktop-only — see PlatformScrollbar.desktop.kt), so this draws a thin,
// non-interactive overlay indicator instead: the same SCROLLBARS_INSIDE_OVERLAY idiom Android's
// own View-based lists use, opaque while scrolling and faded out shortly after it stops.
//
// Compose's LazyColumn / Modifier.verticalScroll do not draw any scrollbar on their own — unlike
// the View system's RecyclerView/ScrollView, which paint one unprompted via android:scrollbars.
// Without this, Android silently has no scroll-position indicator at all, on device and emulator
// alike.

private val SCROLL_INDICATOR_THICKNESS = 4.dp
private val SCROLL_INDICATOR_CORNER_RADIUS = 2.dp
private val SCROLL_INDICATOR_END_MARGIN = 2.dp
private val SCROLL_INDICATOR_TRACK_MARGIN = 2.dp
private val SCROLL_INDICATOR_MIN_LENGTH = 32.dp

/** Medium-emphasis alpha over onSurface — lower than desktop's 0.5f hover color, since this
 *  overlay always sits on top of content rather than beside it. */
private const val SCROLL_INDICATOR_ALPHA = 0.38f

/** Deliberately well above AOSP's own ~300ms default (ViewConfiguration.SCROLL_BAR_DEFAULT_DELAY)
 *  — the indicator is the only scroll-position cue on Android, so it stays a beat longer. */
private const val SCROLL_INDICATOR_HIDE_DELAY_MS = 800L

/** Matches ViewConfiguration.SCROLL_BAR_FADE_DURATION. */
private const val SCROLL_INDICATOR_FADE_OUT_MS = 250

@Composable
actual fun BoxScope.VerticalScrollbarIfNeeded(scrollState: ScrollState) {
    val minLengthPx = with(LocalDensity.current) { SCROLL_INDICATOR_MIN_LENGTH.toPx() }
    ScrollIndicatorOverlay(
        state = scrollState,
        thumb = { trackLengthPx ->
            scrollState.scrollIndicatorState?.let { s ->
                scrollIndicatorThumb(s.scrollOffset, s.contentSize, s.viewportSize, minLengthFraction(minLengthPx, trackLengthPx))
            }
        },
        trackInsets = { 0f to 0f },
    )
}

@Composable
actual fun BoxScope.VerticalScrollbarIfNeeded(listState: LazyListState) {
    val minLengthPx = with(LocalDensity.current) { SCROLL_INDICATOR_MIN_LENGTH.toPx() }
    ScrollIndicatorOverlay(
        state = listState,
        thumb = { trackLengthPx ->
            listState.scrollIndicatorState?.let { s ->
                scrollIndicatorThumb(s.scrollOffset, s.contentSize, s.viewportSize, minLengthFraction(minLengthPx, trackLengthPx))
            }
        },
        // Keeps the track clear of a LazyColumn's own contentPadding (e.g. the navigation-bar
        // inset ArticleListPane/FeedListPane apply as afterContentPadding), without reading
        // WindowInsets.safeDrawing here directly: a sibling Box's Modifier.windowInsetsPadding
        // never consumes inset for this indicator, so reading the raw inset would shrink the
        // track even where the list itself isn't actually padded by it (FeedListPane has none).
        trackInsets = {
            val info = listState.layoutInfo
            info.beforeContentPadding.toFloat() to info.afterContentPadding.toFloat()
        },
    )
}

/**
 * A non-interactive vertical scroll-position indicator: a single [androidx.compose.foundation.Spacer]-like
 * draw layer, opaque while [state] is scrolling and faded out [SCROLL_INDICATOR_HIDE_DELAY_MS] after
 * it last reported scrolling.
 *
 * [thumb] and [state]'s own scroll-in-progress flag are read only inside [snapshotFlow] / the draw
 * phase, never in composition — so a scroll never recomposes the pane hosting this indicator, which
 * matters given the article list's own LazyColumn item-reuse crash history (see known-issues.md).
 * The only node this adds is the one this composable itself creates; it carries no pointer input at
 * all, so it never enters hit testing and can never intercept a press meant for content or a drag
 * handle beneath it (see FeedListPane's own reorder-drag host, whose scrollbar sits beside it
 * exactly because of this).
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
 */
@Composable
private fun BoxScope.ScrollIndicatorOverlay(
    state: ScrollableState,
    thumb: (trackLengthPx: Float) -> ScrollIndicatorThumb?,
    trackInsets: () -> Pair<Float, Float>,
) {
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
    val color = MaterialTheme.colorScheme.onSurface
    Spacer(
        Modifier.matchParentSize().drawBehind {
            val alpha = fade.value * SCROLL_INDICATOR_ALPHA
            if (alpha <= 0f) return@drawBehind
            val (beforePadding, afterPadding) = trackInsets()
            val trackTop = beforePadding + SCROLL_INDICATOR_TRACK_MARGIN.toPx()
            val trackBottom = size.height - afterPadding - SCROLL_INDICATOR_TRACK_MARGIN.toPx()
            val trackLength = (trackBottom - trackTop).coerceAtLeast(0f)
            if (trackLength <= 0f) return@drawBehind
            val t = thumb(trackLength) ?: return@drawBehind
            val thicknessPx = SCROLL_INDICATOR_THICKNESS.toPx()
            val x = if (layoutDirection == LayoutDirection.Ltr) {
                size.width - SCROLL_INDICATOR_END_MARGIN.toPx() - thicknessPx
            } else {
                SCROLL_INDICATOR_END_MARGIN.toPx()
            }
            drawRoundRect(
                color = color,
                alpha = alpha,
                topLeft = Offset(x, trackTop + t.startFraction * trackLength),
                size = Size(thicknessPx, t.lengthFraction * trackLength),
                cornerRadius = CornerRadius(SCROLL_INDICATOR_CORNER_RADIUS.toPx()),
            )
        },
    )
}
