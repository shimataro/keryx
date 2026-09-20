package works.merc.keryx.app.ui.article

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.NativeTextSelectionContextMenu
import works.merc.keryx.app.platform.VerticalScrollbarIfNeeded
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_reader_simple_notice

/** `.article-title { margin: 0 0 4px }` — a fixed physical gap, not em-relative (see ArticleTextStyles.kt). */
private val TITLE_BOTTOM_MARGIN = 4.dp

/** `.article-meta { margin: 0 0 16px }`. */
private val META_BOTTOM_MARGIN = 16.dp

/** App-only spacing before the header; the reader document's own CSS has nothing above the title. */
private val NOTICE_BOTTOM_GAP = 8.dp

private const val TITLE_FONT_SCALE = 1.6f
private const val TITLE_LINE_HEIGHT_RATIO = 1.3f
private const val META_FONT_SCALE = 0.85f

/**
 * Draws an article with Compose instead of a native web view, for platforms where that web view
 * cannot be created at all (see [works.merc.keryx.app.platform.isNativeWebViewSupported]).
 *
 * Takes the same assembled document string the web view would have received, so both readers stay
 * driven by one set of upstream state decisions — see [parseArticleContent].
 *
 * What a browser supplies implicitly, this has to state outright: the whole visual vocabulary here
 * (heading sizes, block margins, list markers, quote indent, monospace code) stands in for the UA
 * default stylesheet, since the reader's own CSS defines almost none of it (only `a`, `img`/`video`/
 * `iframe`, `table`, `td`/`th` and the body's own typography — see `ArticleWebViewHtml.kt`). Sizes
 * and margins below are therefore plain functions of the *document's* own type scale
 * (`ArticleTextStyles.kt`), deliberately not `MaterialTheme.typography` — this reader is
 * reconstructing a browser's rendering of the article, not this app's own UI chrome. Embedded
 * content that needs a real engine — iframes, script-driven widgets, video — degrades to a button
 * that opens it in the browser.
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
                    // Matches the document's own `padding: 16px 8px 24px` (top/right-left/bottom) —
                    // a fixed physical padding, not em-relative, so it does not scale with fontScale
                    // any more than the WebView's own px padding does.
                    contentPadding = PaddingValues(start = 8.dp, top = 16.dp, end = 8.dp, bottom = 24.dp),
                ) {
                    item {
                        Text(
                            text = stringResource(Res.string.article_reader_simple_notice),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = NOTICE_BOTTOM_GAP),
                        )
                    }
                    content.title?.let { title ->
                        item { ArticleTitle(title, content.titleUrl, modifier = Modifier.padding(bottom = TITLE_BOTTOM_MARGIN)) }
                    }
                    content.meta?.let { meta ->
                        item {
                            val fontSize = ARTICLE_BODY_FONT_SIZE.value * META_FONT_SCALE
                            Text(
                                text = meta,
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontSize = fontSize.sp,
                                    lineHeight = (fontSize * ARTICLE_LINE_HEIGHT_RATIO).sp,
                                ),
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = META_BOTTOM_MARGIN),
                            )
                        }
                    }
                    itemsIndexed(content.blocks) { index, block ->
                        val gapEm = gapBetween(content.blocks.getOrNull(index - 1), block)
                        ArticleBlockView(block, modifier = Modifier.padding(top = blockGapDp(gapEm)))
                    }
                }
            }
        }
        VerticalScrollbarIfNeeded(listState)
    }
}

/**
 * Converts a UA-default block margin, expressed in body em (see [gapBetween]), into an actual gap —
 * through `sp`, not a plain `Dp` multiplication, so it scales with the accessibility font-size
 * setting exactly the way the reader document's own `em`-based UA margins scale with its
 * `font-size: N%` (see [ARTICLE_BODY_FONT_SIZE]'s KDoc for why that setting must not be applied a
 * second time here).
 */
@Composable
private fun blockGapDp(gapEm: Float): Dp {
    if (gapEm == 0f) return 0.dp
    return with(LocalDensity.current) { (ARTICLE_BODY_FONT_SIZE.value * gapEm).sp.toDp() }
}

@Composable
private fun ArticleTitle(title: String, titleUrl: String?, modifier: Modifier = Modifier) {
    val fontSize = ARTICLE_BODY_FONT_SIZE.value * TITLE_FONT_SCALE
    val style: TextStyle = MaterialTheme.typography.bodyMedium.copy(
        fontSize = fontSize.sp,
        fontWeight = FontWeight.SemiBold,
        lineHeight = (fontSize * TITLE_LINE_HEIGHT_RATIO).sp,
    )
    if (titleUrl == null) {
        Text(title, style = style, modifier = modifier)
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
    Text(annotated, style = style, modifier = modifier)
}

/** `bodyMedium` at the reader document's own base size (see [ARTICLE_BODY_FONT_SIZE]'s KDoc). */
@Composable
internal fun bodyTextStyle(): TextStyle {
    val size = ARTICLE_BODY_FONT_SIZE.value
    return MaterialTheme.typography.bodyMedium.copy(
        fontSize = size.sp,
        lineHeight = (size * ARTICLE_LINE_HEIGHT_RATIO).sp,
    )
}

/** UA-default heading size for [level] (`h1..h6`), scaled off the same base as [bodyTextStyle]. */
@Composable
internal fun headingStyle(level: Int): TextStyle {
    val size = ARTICLE_BODY_FONT_SIZE.value * headingFontScale(level)
    return MaterialTheme.typography.bodyMedium.copy(
        fontSize = size.sp,
        fontWeight = FontWeight.Bold,
        lineHeight = (size * ARTICLE_LINE_HEIGHT_RATIO).sp,
    )
}
