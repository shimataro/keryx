package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test

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
}
