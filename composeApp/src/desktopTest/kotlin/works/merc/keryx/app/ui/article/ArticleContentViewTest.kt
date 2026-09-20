package works.merc.keryx.app.ui.article

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.doubleClick
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.text.LinkAnnotation
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Renders the Compose fallback reader, which stands in for the native web view on platforms that
 * have no binary for it. Assertions use the Japanese resource strings because the test JVM pins
 * that locale (see `composeApp/build.gradle.kts`).
 */
@OptIn(ExperimentalTestApi::class)
class ArticleContentViewTest {
    private val theme = ArticleHtmlTheme(
        surface = Color(1f, 1f, 1f),
        onSurface = Color(0f, 0f, 0f),
        linkColor = Color(0f, 0f, 1f),
        mutedColor = Color(0.5f, 0.5f, 0.5f),
        fontScale = 1.0f,
    )

    private val simpleNotice = "この環境では WebView を利用できないため、簡易表示にしています"
    private val embedLabel = "埋め込みコンテンツをブラウザーで開く"

    private fun document(body: String) = wrapArticleHtml(
        theme,
        title = "Article title",
        meta = "Author · 2026-01-01",
        body = body,
        baseUrl = "https://example.com/a",
        titleUrl = "https://example.com/a",
    )

    @Test
    fun rendersHeaderBodyAndNotice() = runDesktopComposeUiTest {
        setContent {
            ArticleContentView(document("<h2>Section</h2><p>Body text here.</p>"))
        }

        onNodeWithText(simpleNotice).assertIsDisplayed()
        onNodeWithText("Article title").assertIsDisplayed()
        onNodeWithText("Author · 2026-01-01").assertIsDisplayed()
        onNodeWithText("Section").assertIsDisplayed()
        onNodeWithText("Body text here.").assertIsDisplayed()
    }

    @Test
    fun rendersListsQuotesAndCode() = runDesktopComposeUiTest {
        setContent {
            ArticleContentView(
                document("<ul><li>first item</li></ul><blockquote><p>quoted</p></blockquote><pre>code line</pre>"),
            )
        }

        onNodeWithText("first item").assertIsDisplayed()
        onNodeWithText("quoted").assertIsDisplayed()
        onNodeWithText("code line").assertIsDisplayed()
    }

    @Test
    fun exposesTheArticleTitleAsALink() = runDesktopComposeUiTest {
        setContent {
            ArticleContentView(document("<p>Body text here.</p>"))
        }

        val node = onNodeWithText("Article title").fetchSemanticsNode()
        val links = node.config.getOrNull(SemanticsProperties.Text)
            .orEmpty()
            .flatMap { it.getLinkAnnotations(0, it.length) }
        assertEquals(
            listOf("https://example.com/a"),
            links.map { (it.item as LinkAnnotation.Clickable).tag },
        )
    }

    @Test
    fun rendersLinkTextInline() = runDesktopComposeUiTest {
        setContent {
            ArticleContentView(document("""<p>see <a href="https://example.com/x">the link</a></p>"""))
        }

        // The link is part of the paragraph's own AnnotatedString, so the whole line is one node.
        onNodeWithText("see the link").assertIsDisplayed()
    }

    @Test
    fun offersEmbeddedContentAsABrowserButton() = runDesktopComposeUiTest {
        setContent {
            ArticleContentView(document("""<iframe src="https://www.youtube.com/embed/x"></iframe>"""))
        }

        onNodeWithText(embedLabel).assertIsDisplayed()
        onNodeWithText("https://www.youtube.com/embed/x").assertIsDisplayed()
    }

    @Test
    fun showsOnlyTheCenteredMessageForThePlaceholderDocument() = runDesktopComposeUiTest {
        setContent {
            ArticleContentView(articlePlaceholderHtml(theme, "記事が選択されていません"))
        }

        onNodeWithText("記事が選択されていません").assertIsDisplayed()
        // The simplified-view notice belongs to an article being shown, not to the empty state.
        onNodeWithText(simpleNotice).assertDoesNotExist()
    }

    @Test
    fun showsTheNoContentNoticeAsBodyText() = runDesktopComposeUiTest {
        setContent {
            ArticleContentView(
                articleNoContentHtml(theme, title = "Article title", meta = "", message = "本文がありません"),
            )
        }

        onNodeWithText("Article title").assertIsDisplayed()
        onNodeWithText("本文がありません").assertIsDisplayed()
    }

    // Proves the article body is wrapped in a real SelectionContainer end-to-end: a double-click
    // word-selects text, and Ctrl/Cmd+C copies it through the actual SelectionManager copy path
    // (LocalClipboard, not the deprecated LocalClipboardManager). Regresses if the SelectionContainer
    // wrap around the LazyColumn is ever removed.
    @Test
    fun allowsSelectingAndCopyingBodyText() = runDesktopComposeUiTest {
        val clipboard = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                // A single-word paragraph, so a center double-click word-selects it unambiguously
                // regardless of exactly where the click lands within the node's bounds.
                ArticleContentView(document("<p>Selectable</p>"))
            }
        }

        onNodeWithText("Selectable").performMouseInput { doubleClick() }
        waitForIdle()
        onRoot().performKeyInput {
            withKeyDown(copyModifierKey) { pressKey(Key.C) }
        }
        waitForIdle()

        assertEquals("Selectable", clipboard.copiedText)
    }

    // The companion to the double-click test above, and the case it cannot stand in for: a
    // continuous press-drag-release that spans two separate LazyColumn items. The filler makes the
    // list genuinely scrollable, so the drag is also competing with the LazyColumn's own scroll
    // gesture rather than running over a list that has nothing to scroll.
    @Test
    fun allowsDragSelectingAcrossParagraphs() = runDesktopComposeUiTest {
        val clipboard = FakeClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                val filler = (1..FILLER_PARAGRAPHS).joinToString("") { "<p>Filler paragraph $it</p>" }
                ArticleContentView(document("<p>First paragraph</p><p>Second paragraph</p>$filler"))
            }
        }

        val first = onNodeWithText("First paragraph").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("Second paragraph").fetchSemanticsNode().boundsInRoot
        val start = Offset(first.left + 2f, first.center.y)
        val end = Offset(second.right - 2f, second.center.y)

        onRoot().performMouseInput {
            moveTo(start)
            press()
            moveTo(Offset((start.x + end.x) / 2f, (start.y + end.y) / 2f))
            moveTo(end)
            release()
        }
        waitForIdle()
        onRoot().performKeyInput {
            withKeyDown(copyModifierKey) { pressKey(Key.C) }
        }
        waitForIdle()

        assertEquals("First paragraph\nSecond paragraph", clipboard.copiedText)
    }

    @Test
    fun placesTheSimplifiedNoticeAboveTheTitle() = runDesktopComposeUiTest {
        setContent {
            ArticleContentView(document("<p>Body text here.</p>"))
        }

        val noticeTop = onNodeWithText(simpleNotice).fetchSemanticsNode().boundsInRoot.top
        val titleTop = onNodeWithText("Article title").fetchSemanticsNode().boundsInRoot.top
        assertTrue(noticeTop < titleTop)
    }

    // Regression test for the custom table Layout (ArticleBlockViews.kt): a cell-index mistake in
    // its column/row math would either crash or silently drop cells rather than fail a type check.
    @Test
    fun rendersTableHeaderAndDataCells() = runDesktopComposeUiTest {
        setContent {
            ArticleContentView(document("<table><tr><th>Name</th><th>Age</th></tr><tr><td>Alice</td><td>30</td></tr></table>"))
        }

        onNodeWithText("Name").assertIsDisplayed()
        onNodeWithText("Age").assertIsDisplayed()
        onNodeWithText("Alice").assertIsDisplayed()
        onNodeWithText("30").assertIsDisplayed()
    }

    @Test
    fun rendersDefinitionListsAndFigureCaptions() = runDesktopComposeUiTest {
        setContent {
            ArticleContentView(
                document(
                    "<dl><dt>Term</dt><dd>Meaning</dd></dl>" +
                        """<figure><img src="https://example.com/i.png"><figcaption>caption text</figcaption></figure>""",
                ),
            )
        }

        onNodeWithText("Term").assertIsDisplayed()
        onNodeWithText("Meaning").assertIsDisplayed()
        onNodeWithText("caption text").assertIsDisplayed()
    }

    /**
     * Builds a document long enough to overflow the test window (see [FILLER_PARAGRAPHS]'s own
     * KDoc), with a distinctive first/last paragraph to assert scroll position against.
     */
    private fun longScrollableDocument(): String {
        val filler = (1..FILLER_PARAGRAPHS).joinToString("") { "<p>Filler paragraph $it</p>" }
        return document("<p>First paragraph</p>$filler<p>Last paragraph</p>")
    }

    // Drives FallbackReaderScrollHost.scroll(...) from inside the composition — via a
    // LaunchedEffect keyed on a counter flipped from the test body — rather than calling the
    // suspend function directly from the test's own coroutine, which runs on a different
    // TestCoroutineScheduler than the one driving composition/animation here (see
    // runDesktopComposeUiTest's own KDoc on effectContext vs runTestContext) and would race the
    // animateScrollBy call inside the handler instead of reliably awaiting it.
    @Test
    fun repeatedPageScrollsReachTheEndOfALongArticle() = runDesktopComposeUiTest {
        val host = FallbackReaderScrollHost()
        var scrollRequests by mutableStateOf(0)
        setContent {
            LaunchedEffect(scrollRequests) {
                if (scrollRequests > 0) host.scroll(1, ArticleScrollUnit.Page)
            }
            CompositionLocalProvider(LocalFallbackReaderScrollHost provides host) {
                ArticleContentView(longScrollableDocument())
            }
        }
        waitForIdle()
        onAllNodesWithText("Last paragraph").assertCountEquals(0)

        repeat(20) {
            scrollRequests++
            waitForIdle()
        }

        onNodeWithText("Last paragraph").assertIsDisplayed()
    }

    // Companion to repeatedPageScrollsReachTheEndOfALongArticle: a single Line scroll is a fixed,
    // small per-keypress amount (browser-style ↑/↓), not a fraction of the viewport, so it must
    // move far less than one Page scroll does — nowhere near enough to reach the article's end.
    @Test
    fun aSingleLineScrollMovesMuchLessThanAPageScroll() = runDesktopComposeUiTest {
        val host = FallbackReaderScrollHost()
        var scrollRequests by mutableStateOf(0)
        setContent {
            LaunchedEffect(scrollRequests) {
                if (scrollRequests > 0) host.scroll(1, ArticleScrollUnit.Line)
            }
            CompositionLocalProvider(LocalFallbackReaderScrollHost provides host) {
                ArticleContentView(longScrollableDocument())
            }
        }
        waitForIdle()
        onAllNodesWithText("Last paragraph").assertCountEquals(0)

        scrollRequests++
        waitForIdle()

        onAllNodesWithText("Last paragraph").assertCountEquals(0)
    }

    // The touch-primary-carousel invariant: an inactive instance (a preloaded, not-yet-shown
    // neighbour page) never registers with the scroll host at all, so scroll requests reaching the
    // host while it is the only instance present are simply dropped.
    @Test
    fun anInactiveInstanceNeverRespondsToScrollRequests() = runDesktopComposeUiTest {
        val host = FallbackReaderScrollHost()
        var scrollRequests by mutableStateOf(0)
        setContent {
            LaunchedEffect(scrollRequests) {
                if (scrollRequests > 0) host.scroll(1, ArticleScrollUnit.Page)
            }
            CompositionLocalProvider(LocalFallbackReaderScrollHost provides host) {
                ArticleContentView(longScrollableDocument(), active = false)
            }
        }
        waitForIdle()
        onNodeWithText("First paragraph").assertIsDisplayed()

        repeat(20) {
            scrollRequests++
            waitForIdle()
        }

        onNodeWithText("First paragraph").assertIsDisplayed()
        onAllNodesWithText("Last paragraph").assertCountEquals(0)
    }

    // Companion to repeatedPageScrollsReachTheEndOfALongArticle: unlike Page, a single Edge request
    // (direction = 1) reaches the true end in one call, no repetition needed.
    @Test
    fun endReachesTheBottomInASingleRequest() = runDesktopComposeUiTest {
        val host = FallbackReaderScrollHost()
        var scrollRequests by mutableStateOf(0)
        setContent {
            LaunchedEffect(scrollRequests) {
                if (scrollRequests > 0) host.scroll(1, ArticleScrollUnit.Edge)
            }
            CompositionLocalProvider(LocalFallbackReaderScrollHost provides host) {
                ArticleContentView(longScrollableDocument())
            }
        }
        waitForIdle()
        onAllNodesWithText("Last paragraph").assertCountEquals(0)

        scrollRequests++
        waitForIdle()

        onNodeWithText("Last paragraph").assertIsDisplayed()
    }

    // Companion to endReachesTheBottomInASingleRequest: a single Edge request (direction = -1)
    // reaches the true top in one call from a position scrolled away from it.
    @Test
    fun homeReachesTheTopInASingleRequestFromTheBottom() = runDesktopComposeUiTest {
        val host = FallbackReaderScrollHost()
        var scrollRequests by mutableStateOf(0)
        var unit by mutableStateOf(ArticleScrollUnit.Edge)
        var direction by mutableStateOf(1)
        setContent {
            LaunchedEffect(scrollRequests) {
                if (scrollRequests > 0) host.scroll(direction, unit)
            }
            CompositionLocalProvider(LocalFallbackReaderScrollHost provides host) {
                ArticleContentView(longScrollableDocument())
            }
        }
        waitForIdle()
        onAllNodesWithText("Last paragraph").assertCountEquals(0)

        // Get to the bottom first, via the same one-shot Edge/direction=1 request.
        scrollRequests++
        waitForIdle()
        onNodeWithText("Last paragraph").assertIsDisplayed()
        onAllNodesWithText("First paragraph").assertCountEquals(0)

        direction = -1
        scrollRequests++
        waitForIdle()

        onNodeWithText("First paragraph").assertIsDisplayed()
    }

    // The active=false invariant (see anInactiveInstanceNeverRespondsToScrollRequests above), but
    // for an Edge request: confirms the gating applies uniformly across all three ArticleScrollUnit
    // cases, not just Page.
    @Test
    fun anInactiveInstanceNeverRespondsToEdgeScrollRequests() = runDesktopComposeUiTest {
        val host = FallbackReaderScrollHost()
        var scrollRequests by mutableStateOf(0)
        setContent {
            LaunchedEffect(scrollRequests) {
                if (scrollRequests > 0) host.scroll(1, ArticleScrollUnit.Edge)
            }
            CompositionLocalProvider(LocalFallbackReaderScrollHost provides host) {
                ArticleContentView(longScrollableDocument(), active = false)
            }
        }
        waitForIdle()
        onNodeWithText("First paragraph").assertIsDisplayed()

        scrollRequests++
        waitForIdle()

        onNodeWithText("First paragraph").assertIsDisplayed()
        onAllNodesWithText("Last paragraph").assertCountEquals(0)
    }
}

/** Enough paragraphs to overflow the test window, so the reader's LazyColumn really can scroll. */
private const val FILLER_PARAGRAPHS = 100

/** Ctrl+C everywhere except macOS, which copies with Cmd+C instead (`isCopyKeyEvent`). */
private val copyModifierKey: Key
    get() = if (System.getProperty("os.name") == "Mac OS X") Key.MetaLeft else Key.CtrlLeft

/** Captures what [SelectionContainer]'s copy handler writes, without touching the real OS clipboard. */
@OptIn(ExperimentalComposeUiApi::class)
private class FakeClipboard : Clipboard {
    var copiedText: String? = null

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        copiedText = clipEntry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
    }

    override val nativeClipboard: NativeClipboard get() = Unit
}
