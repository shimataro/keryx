package works.merc.keryx.app.ui.home

import androidx.compose.material3.ColorScheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import works.merc.keryx.app.data.local.FtsSearch

/**
 * Desktop's highlighter-pen span for matched search terms (bold + yellow marker background + a dark
 * text color). Theme-independent and constant regardless of row selection, so a match stays legible
 * both on the normal surface and on the teal `primary` background of a selected row (where the row's
 * `onPrimary` text would otherwise wash out over the yellow). Bold alone was invisible on unread
 * titles (already fully bold), which is why the background marker was added. Android uses a
 * theme-derived pair instead — see [searchHighlightSpanStyle].
 */
val SearchHighlightSpanStyle = SpanStyle(
    fontWeight = FontWeight.Bold,
    background = Color(0xFFFFE082), // amber 200-ish marker
    color = Color(0xFF3E2723),     // near-black, readable on the yellow marker
)

/**
 * The span [markedToAnnotatedString] paints matched search terms with on this platform.
 *
 * Desktop keeps the fixed [SearchHighlightSpanStyle]. A touch-primary platform (Android) follows its
 * color scheme instead — including Material You dynamic color — with M3's own container/on-container
 * pair ([ColorScheme.tertiaryContainer] / [ColorScheme.onTertiaryContainer]), a role pair Material
 * guarantees legible against each other in light and dark alike. Because the span sets both the
 * background and the text color, the match stays readable whatever the row's own selection
 * background is, which is the same property the fixed desktop marker relies on.
 */
internal fun searchHighlightSpanStyle(isTouchPrimary: Boolean, colorScheme: ColorScheme): SpanStyle =
    if (isTouchPrimary) {
        SpanStyle(
            fontWeight = FontWeight.Bold,
            background = colorScheme.tertiaryContainer,
            color = colorScheme.onTertiaryContainer,
        )
    } else {
        SearchHighlightSpanStyle
    }

/**
 * Turns FTS5 highlight/snippet markup (matched spans wrapped in
 * [FtsSearch.MARK_START]/[FtsSearch.MARK_END]) into an [AnnotatedString] whose matched spans get the
 * [highlight] span (by default desktop's [SearchHighlightSpanStyle] — see [searchHighlightSpanStyle]
 * for the per-platform choice). The sentinel chars are consumed, never rendered. Unbalanced markup is handled defensively: a redundant start or a stray
 * end is ignored, and an unclosed start extends the highlight to the end.
 */
fun markedToAnnotatedString(
    marked: String,
    highlight: SpanStyle = SearchHighlightSpanStyle,
): AnnotatedString = buildAnnotatedString {
    var marking = false
    for (ch in marked) {
        when (ch) {
            FtsSearch.MARK_START -> if (!marking) { pushStyle(highlight); marking = true }
            FtsSearch.MARK_END -> if (marking) { pop(); marking = false }
            else -> append(ch)
        }
    }
}
