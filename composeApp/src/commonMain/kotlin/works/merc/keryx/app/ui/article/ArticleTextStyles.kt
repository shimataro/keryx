package works.merc.keryx.app.ui.article

import androidx.compose.ui.unit.sp

/**
 * The browser's own UA-default stylesheet, restated in Compose terms, since almost none of it is
 * defined by the reader document's own CSS (see [ArticleContentView]'s KDoc). Everything here is a
 * plain function of the model, never of `MaterialTheme.typography` — the fallback reader must match
 * the *article document's* own type scale, not this app's UI chrome scale.
 */

/**
 * The reader document's own base font size (`html { font-size: 100% }`, i.e. a plain browser's
 * 16px root) — this is "1em" for every scale below, and deliberately not `MaterialTheme.typography
 * .bodyMedium`'s 14sp default, which would leave the fallback reader's body text visibly smaller
 * than the same article rendered in the WebView. `fontScale` is not applied here: it already
 * reaches every `sp` value through `LocalDensity` (see `KeryxTheme.kt`), the same way it reaches
 * the WebView's own `font-size: N%` — applying it a second time here would double it.
 */
internal val ARTICLE_BODY_FONT_SIZE = 16.sp

/** Matches the `line-height: 1.6` the reader document sets on its own body. */
internal const val ARTICLE_LINE_HEIGHT_RATIO = 1.6f

/** UA-default heading font-size, as a multiple of the body size (`h1..h6`'s own `font-size: NNem`). */
internal fun headingFontScale(level: Int): Float = when (level) {
    1 -> 2.0f
    2 -> 1.5f
    3 -> 1.17f
    4 -> 1.0f
    5 -> 0.83f
    else -> 0.67f
}

/**
 * UA-default top/bottom margin for [block], expressed in the *body's* em (not the block's own
 * font-size) so every block's margin can be compared and maximized directly in [gapBetween].
 * Values mirror a plain browser's default stylesheet: a heading's own `margin: NNem 0` is defined
 * against *its own* font-size, so it is multiplied by [headingFontScale] here to convert it to the
 * body's em.
 */
internal fun blockMarginEm(block: ArticleBlock): Pair<Float, Float> = when (block) {
    is ArticleBlock.Heading -> headingMarginEm(block.level).let { it * headingFontScale(block.level) to it * headingFontScale(block.level) }
    is ArticleBlock.Paragraph -> 1.0f to 1.0f
    is ArticleBlock.Bullets -> 1.0f to 1.0f
    is ArticleBlock.Quote -> 1.0f to 1.0f
    is ArticleBlock.Code -> 1.0f to 1.0f
    is ArticleBlock.Figure -> 1.0f to 1.0f
    ArticleBlock.Rule -> 0.5f to 0.5f
    is ArticleBlock.Table -> 0f to 0f
    // Caption/Picture/Embed carry no UA-default margin of their own; spacing around them comes
    // from the figure/list/quote that contains them, or from a fixed small gap at the call site.
    is ArticleBlock.Caption, is ArticleBlock.Picture, is ArticleBlock.Embed -> 0f to 0f
}

private fun headingMarginEm(level: Int): Float = when (level) {
    1 -> 0.67f
    2 -> 0.83f
    3 -> 1.0f
    4 -> 1.33f
    5 -> 1.67f
    else -> 2.33f
}

/**
 * The gap actually rendered between two adjacent blocks, reproducing CSS margin collapsing: two
 * adjacent vertical margins collapse into the *larger* of the two rather than summing. [previous]
 * is null for the first block in the document (nothing above it to collapse with).
 */
internal fun gapBetween(previous: ArticleBlock?, next: ArticleBlock): Float {
    val nextTop = blockMarginEm(next).first
    if (previous == null) return 0f
    val previousBottom = blockMarginEm(previous).second
    return maxOf(previousBottom, nextTop)
}
