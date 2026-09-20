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
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
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
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_embed_open
import works.merc.keryx.app.ui.common.FlatTonalButton

/** Fixed column width for the simplified table rendering, which does no column measurement. */
private val TABLE_COLUMN_WIDTH = 160.dp

private val QUOTE_BAR_WIDTH = 3.dp
private val NESTED_SPACING = 6.dp
private val BULLET_MARKER_WIDTH = 24.dp

@Composable
internal fun ArticleBlockView(block: ArticleBlock, modifier: Modifier = Modifier) {
    when (block) {
        is ArticleBlock.Paragraph -> Text(
            text = block.text.annotated(),
            style = bodyTextStyle(),
            color = if (block.muted) MaterialTheme.colorScheme.onSurfaceVariant else Color.Unspecified,
            textAlign = block.align,
            modifier = modifier,
        )

        is ArticleBlock.Caption -> Text(
            text = block.text.annotated(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = block.align,
            modifier = modifier,
        )

        is ArticleBlock.Heading -> Text(
            block.text.annotated(),
            style = headingStyle(block.level),
            textAlign = block.align,
            modifier = modifier,
        )

        is ArticleBlock.Bullets -> BulletsView(block, modifier)

        is ArticleBlock.Quote -> QuoteView(block, modifier)

        is ArticleBlock.Code -> CodeView(block, modifier)

        is ArticleBlock.Picture -> AsyncImage(
            model = block.src,
            contentDescription = block.alt,
            // Inside, not Fit: it shrinks an oversized image to the column but leaves a smaller one
            // at its own size, which is what the document's `max-width: 100%` does.
            contentScale = ContentScale.Inside,
            modifier = modifier.fillMaxWidth(),
        )

        is ArticleBlock.Table -> TableView(block, modifier)

        is ArticleBlock.Embed -> EmbedView(block, modifier)

        ArticleBlock.Rule -> HorizontalDivider(modifier, color = MaterialTheme.colorScheme.outlineVariant)
    }
}

@Composable
private fun BulletsView(block: ArticleBlock.Bullets, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(NESTED_SPACING)) {
        block.items.forEachIndexed { index, item ->
            Row {
                Text(
                    text = if (block.ordered) "${index + 1}." else "•",
                    style = bodyTextStyle(),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
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
private fun TableView(block: ArticleBlock.Table, modifier: Modifier) {
    // Deliberately simple: fixed-width columns and no colspan/rowspan handling. A real table
    // layout is out of scope for a fallback reader; keeping the columns aligned is not.
    Column(modifier.horizontalScroll(rememberScrollState())) {
        block.rows.forEachIndexed { index, row ->
            if (index > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row {
                row.forEach { cell ->
                    Text(
                        text = cell.annotated(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(TABLE_COLUMN_WIDTH).padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
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
            background = if (span.highlight) HIGHLIGHT_COLOR else (span.background ?: Color.Unspecified),
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
