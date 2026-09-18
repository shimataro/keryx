package works.merc.keryx.app.platform

/**
 * A vertical scroll indicator thumb's own length, as a fraction of its track's length, for content
 * of [contentSize] px seen through a [viewportSize] px window — or `0f` when no thumb should be
 * drawn at all: the content fits entirely, the sizes have not been measured yet, or
 * [minLengthFraction] would fill the whole track anyway. `0f` is otherwise never a valid length (a
 * real thumb always has positive length), so it doubles as the "don't draw" signal callers check
 * before calling [scrollIndicatorStartFraction].
 *
 * Either size may be `Int.MAX_VALUE`, which is the sentinel `androidx.compose.foundation.ScrollIndicatorState`
 * documents for "not known yet"; that yields `0f` rather than a nonsense length.
 *
 * This lives in `commonMain` although only the Android `VerticalScrollbarIfNeeded` calls it: there
 * is no `androidUnitTest` source set (see `docs/testing.md`), so pure logic belonging to an
 * Android-only class is extracted here to be covered from `commonTest`, the way
 * `UpdateInstallPolicy`'s `canInstallAndroidApkUpdate` already is.
 */
internal fun scrollIndicatorLengthFraction(
    contentSize: Int,
    viewportSize: Int,
    minLengthFraction: Float,
): Float {
    if (contentSize == Int.MAX_VALUE || viewportSize == Int.MAX_VALUE) return 0f
    if (viewportSize <= 0 || contentSize <= 0) return 0f
    if (contentSize <= viewportSize) return 0f
    val lengthFraction = (viewportSize.toFloat() / contentSize).coerceIn(minLengthFraction.coerceIn(0f, 1f), 1f)
    return if (lengthFraction >= 1f) 0f else lengthFraction
}

/**
 * Where a thumb of [lengthFraction] should start on its track, as a fraction of that track, for
 * content of [contentSize] px seen through a [viewportSize] px window scrolled [scrollOffset] px
 * from the start. Only meaningful when [scrollIndicatorLengthFraction] (with the same
 * [contentSize]/[viewportSize] and the [lengthFraction] it produced) returned a positive length —
 * callers are expected to have already checked that and not call this otherwise.
 *
 * This is the scroll progress applied to the track *left over* after the thumb's own length, not
 * the raw `scrollOffset / contentSize` ratio. Mapping the window directly would push the thumb past
 * the end of the track the moment a minimum length clamps a very long list's thumb — the same
 * reason Android's own `ScrollBarDrawable.setParameters(range, offset, extent)` distributes over
 * the remaining track.
 *
 * [scrollOffset] may be `Int.MAX_VALUE`, the same "not known yet" sentinel
 * [scrollIndicatorLengthFraction] checks for [contentSize]/[viewportSize]; that yields `0f` here
 * too, matching the guard symmetrically even though [contentSize]/[viewportSize] being known
 * already implies [scrollOffset] is too in every `ScrollIndicatorState` this repository has seen.
 */
internal fun scrollIndicatorStartFraction(
    scrollOffset: Int,
    contentSize: Int,
    viewportSize: Int,
    lengthFraction: Float,
): Float {
    if (scrollOffset == Int.MAX_VALUE) return 0f
    val range = contentSize - viewportSize
    if (range <= 0) return 0f
    val progress = (scrollOffset.toFloat() / range).coerceIn(0f, 1f)
    return progress * (1f - lengthFraction)
}

/**
 * [minLengthPx] (a dp-fixed floor, resolved to px once at composition time) as a fraction of a
 * track whose length is only known once the indicator is actually measured, so the absolute floor
 * is converted at draw time rather than baked in as a fixed fraction — a fixed fraction would make
 * the minimum thumb size scale with the pane's own height instead of staying a constant dp size.
 *
 * [trackLengthPx] `<= 0f` yields `1f` (no thumb can be drawn at all), which duplicates the caller's
 * own `trackLength <= 0f` guard before it ever calls this — that duplication is deliberate: unlike
 * the caller's guard, this one is reachable from `commonTest`.
 *
 * This lives in `commonMain` for the same reason as [scrollIndicatorLengthFraction] above, though
 * only the Android `VerticalScrollbarIfNeeded` calls it — see that function's own KDoc.
 */
internal fun minLengthFraction(minLengthPx: Float, trackLengthPx: Float): Float =
    if (trackLengthPx <= 0f) 1f else (minLengthPx / trackLengthPx).coerceIn(0f, 1f)
