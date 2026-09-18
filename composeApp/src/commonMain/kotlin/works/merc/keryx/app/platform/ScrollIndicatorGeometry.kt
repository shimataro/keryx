package works.merc.keryx.app.platform

/**
 * Where a vertical scroll indicator's thumb sits on its track, as fractions of that track's own
 * length — the caller multiplies them by whatever length it measures at draw time.
 */
internal data class ScrollIndicatorThumb(
    /** Distance from the track's start to the thumb's start, as a fraction of the track. */
    val startFraction: Float,
    /** The thumb's own length, as a fraction of the track. */
    val lengthFraction: Float,
)

/**
 * Thumb geometry for content of [contentSize] px seen through a [viewportSize] px window scrolled
 * [scrollOffset] px from the start, or `null` when nothing should be drawn at all — the content
 * fits entirely, the sizes have not been measured yet, or [minLengthFraction] would fill the whole
 * track anyway.
 *
 * Any of the three sizes may be `Int.MAX_VALUE`, which is the sentinel
 * `androidx.compose.foundation.ScrollIndicatorState` documents for "not known yet"; that yields
 * `null` rather than a nonsense thumb.
 *
 * The thumb's start is the scroll progress applied to the track *left over* after the thumb's own
 * length, not the raw `scrollOffset / contentSize` ratio. Mapping the window directly would push
 * the thumb past the end of the track the moment [minLengthFraction] clamps a very long list's
 * thumb — the same reason Android's own `ScrollBarDrawable.setParameters(range, offset, extent)`
 * distributes over the remaining track.
 *
 * This lives in `commonMain` although only the Android `VerticalScrollbarIfNeeded` calls it: there
 * is no `androidUnitTest` source set (see `docs/testing.md`), so pure logic belonging to an
 * Android-only class is extracted here to be covered from `commonTest`, the way
 * `UpdateInstallPolicy`'s `canInstallAndroidApkUpdate` already is.
 */
internal fun scrollIndicatorThumb(
    scrollOffset: Int,
    contentSize: Int,
    viewportSize: Int,
    minLengthFraction: Float,
): ScrollIndicatorThumb? {
    if (scrollOffset == Int.MAX_VALUE || contentSize == Int.MAX_VALUE || viewportSize == Int.MAX_VALUE) return null
    if (viewportSize <= 0 || contentSize <= 0) return null
    val range = contentSize - viewportSize
    if (range <= 0) return null
    val lengthFraction = (viewportSize.toFloat() / contentSize).coerceIn(minLengthFraction.coerceIn(0f, 1f), 1f)
    if (lengthFraction >= 1f) return null
    val progress = (scrollOffset.toFloat() / range).coerceIn(0f, 1f)
    return ScrollIndicatorThumb(startFraction = progress * (1f - lengthFraction), lengthFraction = lengthFraction)
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
 * This lives in `commonMain` for the same reason as `scrollIndicatorThumb` above, though only the
 * Android `VerticalScrollbarIfNeeded` calls it — see that function's own KDoc.
 */
internal fun minLengthFraction(minLengthPx: Float, trackLengthPx: Float): Float =
    if (trackLengthPx <= 0f) 1f else (minLengthPx / trackLengthPx).coerceIn(0f, 1f)
