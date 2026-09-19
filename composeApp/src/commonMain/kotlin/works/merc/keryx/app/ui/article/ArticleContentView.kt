package works.merc.keryx.app.ui.article

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.NativeTextSelectionContextMenu
import works.merc.keryx.app.platform.VerticalScrollbarIfNeeded
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_embed_open
import works.merc.keryx.app.resources.article_reader_simple_notice
import works.merc.keryx.app.ui.common.FlatTonalButton

/** Matches the `line-height: 1.6` the web-view document sets on its own body. */
private const val BODY_LINE_HEIGHT_RATIO = 1.6f

/** Fixed column width for the simplified table rendering, which does no column measurement. */
private val TABLE_COLUMN_WIDTH = 160.dp

private val QUOTE_BAR_WIDTH = 3.dp
private val BLOCK_SPACING = 12.dp
private val NESTED_SPACING = 6.dp
private val BULLET_MARKER_WIDTH = 24.dp

/**
 * Draws an article with Compose instead of a native web view, for platforms where that web view
 * cannot be created at all (see [works.merc.keryx.app.platform.isNativeWebViewSupported]).
 *
 * Takes the same assembled document string the web view would have received, so both readers stay
 * driven by one set of upstream state decisions — see [parseArticleContent].
 *
 * What a browser supplies implicitly, this has to state outright: the whole visual vocabulary here
 * (heading sizes, list markers, quote indent, monospace code) stands in for the UA default
 * stylesheet, since the reader's own CSS defines almost none of it. Embedded content that needs a
 * real engine — iframes, script-driven widgets, video — degrades to a button that opens it in the
 * browser.
 */
@Composable
internal fun ArticleContentView(html: String, modifier: Modifier = Modifier) {
    val content = remember(html) { parseArticleContent(html) }

    content.centeredNotice?.let { notice ->
        NativeTextSelectionContextMenu {
            SelectionContainer {
                Box(modifier, contentAlignment = Alignment.Center) {
                    Text(
                        text = notice,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
        }
        return
    }

    // Keyed on the document, so switching articles starts the new one at its top instead of
    // inheriting the previous article's offset. (The web-view reader's own scroll memory works the
    // other way round — it survives precisely because the same native instance is reused.)
    val listState = remember(html) { LazyListState() }

    Box(modifier) {
        // Wraps the whole article body (title, meta, and every block) so its text stays
        // selectable/copyable the way the web-view reader's own document text is — the one
        // affordance the block-by-block reproduction below doesn't get for free. Link taps
        // (ArticleTitle, inline links in buildInlineString) still work inside it: they're
        // carried by LinkAnnotation.Clickable rather than a competing Modifier.clickable, which
        // is exactly the combination SelectionContainer is designed to coexist with. The
        // NativeTextSelectionContextMenu around it only swaps the widget the selection's own
        // right-click menu is drawn with, for the same OS-native one every other context menu in
        // the app uses — it adds no gesture handling of its own.
        NativeTextSelectionContextMenu {
            SelectionContainer {
                LazyColumn(
                    state = listState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(BLOCK_SPACING),
                ) {
                    item {
                        Text(
                            text = stringResource(Res.string.article_reader_simple_notice),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    content.title?.let { title ->
                        item { ArticleTitle(title, content.titleUrl) }
                    }
                    content.meta?.let { meta ->
                        item {
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    items(content.blocks) { block -> ArticleBlockView(block) }
                }
            }
        }
        VerticalScrollbarIfNeeded(listState)
    }
}

@Composable
private fun ArticleTitle(title: String, titleUrl: String?) {
    val style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.SemiBold)
    if (titleUrl == null) {
        Text(title, style = style)
        return
    }
    // The web-view document makes the title a link to the article itself; keep that here — as a
    // real link annotation rather than a bare clickable, so it carries the same link semantics
    // the inline links below do. No link styling is applied: the document's own CSS leaves the
    // title's look alone too (`.article-title a { color: inherit; text-decoration: none }`).
    val annotated = remember(title, titleUrl) {
        buildAnnotatedString {
            withLink(LinkAnnotation.Clickable(tag = titleUrl, linkInteractionListener = { BrowserOpener.open(titleUrl) })) {
                append(title)
            }
        }
    }
    Text(annotated, style = style)
}

@Composable
private fun ArticleBlockView(block: ArticleBlock, modifier: Modifier = Modifier) {
    when (block) {
        is ArticleBlock.Paragraph -> Text(block.text.annotated(), style = bodyTextStyle(), modifier = modifier)

        is ArticleBlock.Caption -> Text(
            text = block.text.annotated(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier,
        )

        is ArticleBlock.Heading -> Text(block.text.annotated(), style = headingStyle(block.level), modifier = modifier)

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

/** `bodyMedium` with the document's own line height, which M3's default leading is tighter than. */
@Composable
private fun bodyTextStyle(): TextStyle = MaterialTheme.typography.bodyMedium.let {
    it.copy(lineHeight = it.fontSize * BODY_LINE_HEIGHT_RATIO)
}

@Composable
private fun headingStyle(level: Int): TextStyle = when (level) {
    1 -> MaterialTheme.typography.headlineSmall
    2 -> MaterialTheme.typography.titleLarge
    3 -> MaterialTheme.typography.titleMedium
    else -> MaterialTheme.typography.titleSmall
}

@Composable
private fun ArticleInline.annotated(): AnnotatedString {
    val linkColor = MaterialTheme.colorScheme.primary
    return remember(this, linkColor) { buildInlineString(linkColor) }
}

private fun ArticleInline.buildInlineString(linkColor: Color): AnnotatedString = buildAnnotatedString {
    for (span in spans) {
        val style = SpanStyle(
            fontWeight = if (span.bold) FontWeight.Bold else null,
            fontStyle = if (span.italic) FontStyle.Italic else null,
            fontFamily = if (span.code) FontFamily.Monospace else null,
            textDecoration = if (span.strikethrough) TextDecoration.LineThrough else null,
        )
        val link = span.link
        if (link == null) {
            withStyle(style) { append(span.text) }
        } else {
            // Every tap here is unambiguously a link tap, so there is none of the web view's
            // request-interception guesswork — just open the browser.
            withLink(LinkAnnotation.Clickable(tag = link, linkInteractionListener = { BrowserOpener.open(link) })) {
                withStyle(style.copy(color = linkColor, textDecoration = TextDecoration.Underline)) {
                    append(span.text)
                }
            }
        }
    }
}
