package works.merc.keryx.app.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_embed_open
import works.merc.keryx.app.ui.common.FlatTonalButton

private val QUOTE_BAR_WIDTH = 3.dp
private val NESTED_SPACING = 6.dp
private val BULLET_MARKER_WIDTH = 24.dp

/** UA-default `dd { margin-left: 40px }` — a fixed physical inset, not an em-relative one. */
private val DEFINITION_INDENT = 40.dp

/** Marker glyphs cycled by nesting depth, matching a browser's default disc/circle/square cycle. */
private val BULLET_GLYPHS = listOf("•", "◦", "▪")

@Composable
internal fun ArticleBlockView(block: ArticleBlock, modifier: Modifier = Modifier) {
    when (block) {
        is ArticleBlock.Paragraph -> LinkText(
            text = block.text.annotated(),
            style = bodyTextStyle(),
            color = if (block.muted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            textAlign = block.align,
            modifier = modifier,
        )

        is ArticleBlock.Caption -> LinkText(
            text = block.text.annotated(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = block.align,
            modifier = modifier,
        )

        is ArticleBlock.Heading -> LinkText(
            text = block.text.annotated(),
            style = headingStyle(block.level),
            textAlign = block.align,
            modifier = modifier,
        )

        is ArticleBlock.Bullets -> BulletsView(block, modifier)

        is ArticleBlock.Quote -> QuoteView(block, modifier)

        is ArticleBlock.Code -> CodeView(block, modifier)

        is ArticleBlock.Picture -> PictureView(block, modifier)

        is ArticleBlock.Figure -> FigureView(block, modifier)

        is ArticleBlock.Table -> TableView(block, modifier)

        is ArticleBlock.Definition -> DefinitionView(block, modifier)

        is ArticleBlock.Embed -> EmbedView(block, modifier)

        ArticleBlock.Rule -> HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun BulletsView(block: ArticleBlock.Bullets, modifier: Modifier) {
    val markerColor = MaterialTheme.colorScheme.onSurfaceVariant
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NESTED_SPACING)) {
        block.items.forEachIndexed { index, item ->
            Row {
                val markerText = if (block.ordered) {
                    "${block.start + index}."
                } else {
                    BULLET_GLYPHS[block.depth.coerceAtLeast(0) % BULLET_GLYPHS.size]
                }
                Text(
                    text = markerText,
                    style = bodyTextStyle(),
                    color = markerColor,
                    // UA default right-aligns an ordered-list counter against the following text.
                    textAlign = if (block.ordered) TextAlign.End else TextAlign.Start,
                    modifier = Modifier.width(BULLET_MARKER_WIDTH),
                )
                Column(verticalArrangement = Arrangement.spacedBy(NESTED_SPACING)) {
                    item.forEach { child -> ArticleBlockView(child) }
                }
            }
        }
    }
}

@Composable
private fun QuoteView(block: ArticleBlock.Quote, modifier: Modifier) {
    val barColor = MaterialTheme.colorScheme.outlineVariant
    Column(
        modifier = modifier
            // Painted rather than laid out, so the bar needs no layout slot of its own and
            // stretches to whatever height the quote's content ends up being.
            .drawBehind { drawRect(color = barColor, size = Size(QUOTE_BAR_WIDTH.toPx(), size.height)) }
            .padding(start = QUOTE_BAR_WIDTH + 9.dp),
        verticalArrangement = Arrangement.spacedBy(NESTED_SPACING),
    ) {
        block.children.forEach { child -> ArticleBlockView(child) }
    }
}

@Composable
private fun CodeView(block: ArticleBlock.Code, modifier: Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(4.dp))
            .background(MaterialTheme.colorScheme.surfaceContainerHighest)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 8.dp),
    ) {
        Text(
            text = block.text,
            style = bodyTextStyle().copy(fontFamily = FontFamily.Monospace),
            softWrap = false,
        )
    }
}

@Composable
private fun PictureView(block: ArticleBlock.Picture, modifier: Modifier = Modifier) {
    var failed by remember(block.src) { mutableStateOf(false) }
    if (failed) {
        block.alt?.let {
            Text(
                text = it,
                style = bodyTextStyle().copy(fontStyle = FontStyle.Italic),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = modifier,
            )
        }
        return
    }
    AsyncImage(
        model = block.src,
        contentDescription = block.alt,
        // Inside, not Fit: it shrinks an oversized image to the column but leaves a smaller one
        // at its own size, which is what the document's `max-width: 100%` does.
        contentScale = ContentScale.Inside,
        alignment = Alignment.CenterStart,
        onError = { failed = true },
        modifier = modifier.fillMaxWidth(),
    )
}

@Composable
private fun FigureView(block: ArticleBlock.Figure, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NESTED_SPACING)) {
        block.children.forEach { child -> ArticleBlockView(child) }
    }
}

@Composable
private fun DefinitionView(block: ArticleBlock.Definition, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        LinkText(text = block.term.annotated(), style = bodyTextStyle().copy(fontWeight = FontWeight.Bold))
        LinkText(text = block.description.annotated(), style = bodyTextStyle(), modifier = Modifier.padding(start = DEFINITION_INDENT))
    }
}

private data class TableCellInfo(val row: Int, val col: Int, val isHeader: Boolean)

private fun buildCellInfos(rows: List<TableRow>): List<TableCellInfo> = buildList {
    rows.forEachIndexed { r, row -> row.cells.forEachIndexed { c, _ -> add(TableCellInfo(r, c, row.isHeader)) } }
}

/**
 * Deliberately simple: no colspan/rowspan handling — a real table layout is out of scope for a
 * fallback reader. Column widths *are* measured from content (unlike a fixed-width column, which
 * either wasted space on narrow columns or clipped wide ones): every cell is measured unconstrained,
 * each column takes the widest cell in it, and the whole table sits inside [horizontalScroll] for
 * when that natural width overflows the pane. Row separators are kept (see [ArticleContentView]'s
 * "keep the app's own decoration" policy) by painting them at the row boundaries this layout already
 * computes, rather than interleaving separate `HorizontalDivider` composables between rows the way a
 * plain `Column` of rows could — a single [Layout] spanning every cell is what lets columns actually
 * line up across rows in the first place.
 */
@Composable
private fun TableView(block: ArticleBlock.Table, modifier: Modifier) {
    val columnCount = remember(block) { block.rows.maxOf { it.cells.size } }
    val cellInfos = remember(block) { buildCellInfos(block.rows) }
    var rowBoundaries by remember(block) { mutableStateOf(IntArray(0)) }
    val dividerColor = MaterialTheme.colorScheme.outlineVariant
    val headerStyle = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold)
    val cellStyle = MaterialTheme.typography.bodySmall

    Layout(
        content = {
            block.rows.forEach { row ->
                row.cells.forEach { cell ->
                    LinkText(
                        text = cell.annotated(),
                        style = if (row.isHeader) headerStyle else cellStyle,
                        textAlign = if (row.isHeader) TextAlign.Center else null,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        },
        modifier = modifier
            .horizontalScroll(rememberScrollState())
            .drawBehind {
                val boundaries = rowBoundaries
                if (boundaries.size <= 1) return@drawBehind
                for (i in 1 until boundaries.size - 1) {
                    val y = boundaries[i].toFloat()
                    drawLine(dividerColor, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
                }
            },
    ) { measurables, _ ->
        val loose = Constraints()
        val placeables = measurables.map { it.measure(loose) }
        val columnWidths = IntArray(columnCount)
        val rowHeights = IntArray(block.rows.size)
        cellInfos.forEachIndexed { i, info ->
            columnWidths[info.col] = maxOf(columnWidths[info.col], placeables[i].width)
            rowHeights[info.row] = maxOf(rowHeights[info.row], placeables[i].height)
        }
        val columnStarts = IntArray(columnCount + 1)
        for (c in 0 until columnCount) columnStarts[c + 1] = columnStarts[c] + columnWidths[c]
        val boundaries = IntArray(rowHeights.size + 1)
        for (r in rowHeights.indices) boundaries[r + 1] = boundaries[r] + rowHeights[r]
        rowBoundaries = boundaries

        layout(columnStarts.last(), boundaries.last()) {
            cellInfos.forEachIndexed { i, info ->
                val columnStart = columnStarts[info.col]
                val x = if (info.isHeader) columnStart + (columnWidths[info.col] - placeables[i].width) / 2 else columnStart
                placeables[i].placeRelative(x, boundaries[info.row])
            }
        }
    }
}

@Composable
private fun EmbedView(block: ArticleBlock.Embed, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FlatTonalButton(onClick = { BrowserOpener.open(block.url) }) {
            Text(stringResource(Res.string.article_embed_open))
        }
        Text(
            text = block.url,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Approximates `<mark>`'s UA-default yellow highlight without hardcoding a jarring pure yellow. */
private val HIGHLIGHT_COLOR = Color(0xFFFFEB3B).copy(alpha = 0.5f)

@Composable
private fun ArticleInline.annotated(): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    // Sized relative to the article body's own base size, not the enclosing element's (a heading,
    // a table cell) — a simplification: a <sup>/<sub>/<small> nested inside a heading is rare
    // enough in real feed markup that this is not worth threading per-context base sizes for.
    return remember(this, linkColor) { buildInlineString(linkColor, ARTICLE_BODY_FONT_SIZE.value) }
}

private fun ArticleInline.buildInlineString(linkColor: Color, baseFontSizeSp: Float): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val decoration = when {
            span.underline && span.strikethrough -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            span.underline -> TextDecoration.Underline
            span.strikethrough -> TextDecoration.LineThrough
            else -> null
        }
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            textDecoration = decoration,
            fontSize = if (span.sizeScale != 1f) (baseFontSizeSp * span.sizeScale).sp else TextUnit.Unspecified,
            baselineShift = when (span.baseline) {
                InlineBaseline.Super -> BaselineShift.Superscript
                InlineBaseline.Sub -> BaselineShift.Subscript
                InlineBaseline.Normal -> null
            },
            color = span.color ?: Color.Unspecified,
            background = span.background
                ?: if (span.highlight) HIGHLIGHT_COLOR else Color.Unspecified,
        )
        val link = span.link
        if (link == null) {
            withStyle(style) { append(span.text) }
        } else {
            // Every tap here is unambiguously a link tap, so there is none of the web view's
            // request-interception guesswork — just open the browser. A link's own inline color
            // (rare, but a real CSS cascade winner — see InlineStyle's KDoc) is kept instead of
            // being forced to the theme's link color; the underline stays regardless, since that
            // comes from the UA default `text-decoration` on `<a>`, which the reader's CSS never
            // overrides either.
            withLink(LinkAnnotation.Clickable(tag = link, linkInteractionListener = { BrowserOpener.open(link) })) {
                val linkStyle = if (span.color != null) style else style.copy(color = linkColor, textDecoration = TextDecoration.Underline)
                withStyle(linkStyle) { append(span.text) }
            }
        }
    }
}
