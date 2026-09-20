package works.merc.keryx.app.ui.article

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.NativeTextSelectionContextMenu
import works.merc.keryx.app.platform.VerticalScrollbarIfNeeded
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.article_reader_simple_notice

private val BLOCK_SPACING = 12.dp

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
