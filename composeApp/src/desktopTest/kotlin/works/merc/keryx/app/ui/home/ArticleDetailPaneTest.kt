package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.data.local.db.Articles
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * `ArticleDetailPaneContent` composes its native reader unconditionally, regardless of whether an
 * article is selected — deliberately, since mounting/unmounting that heavyweight AWT surface is
 * what causes the whole-window flicker documented in `docs/known-issues.md`. These tests exercise
 * the Compose-only shell around it with a stub [reader] slot, since the real reader is a genuine
 * native OS browser view a Compose UI test's offscreen renderer cannot host.
 */
@OptIn(ExperimentalTestApi::class)
class ArticleDetailPaneTest {

    @Test
    fun readerBoundsAreIdenticalWithAndWithoutASelection() = runDesktopComposeUiTest {
        var article by mutableStateOf<Articles?>(null)

        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()
        val emptyBounds = onNodeWithTag(ARTICLE_READER_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        article = testArticle()
        waitForIdle()
        val selectedBounds = onNodeWithTag(ARTICLE_READER_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

        assertEquals(emptyBounds, selectedBounds)
    }

    @Test
    fun readerStaysComposedWithNoArticleSelected() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = null,
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithTag(ARTICLE_READER_TEST_TAG, useUnmergedTree = true).assertExists()
    }

    @Test
    fun starAndMarkUnreadAreDisabledWithNoSelectionAndEnabledWithASelection() = runDesktopComposeUiTest {
        var article by mutableStateOf<Articles?>(null)

        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()
        onNodeWithContentDescription("スター").assertIsNotEnabled()
        onNodeWithContentDescription("未読に戻す").assertIsNotEnabled()

        article = testArticle()
        waitForIdle()
        onNodeWithContentDescription("スター").assertIsEnabled()
        onNodeWithContentDescription("未読に戻す").assertIsEnabled()
    }

    @Test
    fun copyAndOpenAreDisabledForASelectedArticleWithNoUrl() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(url = ""),
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").assertIsNotEnabled()
        onNodeWithContentDescription("ブラウザーで開く").assertIsNotEnabled()
    }

    @Test
    fun copyAndOpenAreVisibleButDisabledWithNoSelection() = runDesktopComposeUiTest {
        setContent {
            ArticleDetailPaneContent(
                article = null,
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").assertIsNotEnabled()
        onNodeWithContentDescription("ブラウザーで開く").assertIsNotEnabled()
    }

    @Test
    fun blankContentFallsBackToSummaryInsteadOfShowingNoContentNotice() = runDesktopComposeUiTest {
        var capturedBody: String? = null

        setContent {
            ArticleDetailPaneContent(
                article = testArticle(content = "   ", summary = "fallback summary"),
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, body, _, _ -> capturedBody = body; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals("fallback summary", capturedBody)
    }

    @Test
    fun readerReceivesArticleUrlAsBaseUrl() = runDesktopComposeUiTest {
        var capturedBaseUrl: String? = "unset"
        val article = testArticle()

        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, _, baseUrl, _ -> capturedBaseUrl = baseUrl; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(article.url, capturedBaseUrl)
    }

    @Test
    fun readerReceivesArticleUrlAsFourthArg() = runDesktopComposeUiTest {
        var capturedArticleUrl: String? = "unset"
        val article = testArticle()

        setContent {
            ArticleDetailPaneContent(
                article = article,
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, _, _, articleUrl -> capturedArticleUrl = articleUrl; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(article.url, capturedArticleUrl)
    }

    @Test
    fun readerReceivesNullArticleUrlWhenNoArticleSelected() = runDesktopComposeUiTest {
        var capturedArticleUrl: String? = "unset"

        setContent {
            ArticleDetailPaneContent(
                article = null,
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, _, _, articleUrl -> capturedArticleUrl = articleUrl; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(null, capturedArticleUrl)
    }

    @Test
    fun readerReceivesNullBaseUrlWhenNoArticleSelected() = runDesktopComposeUiTest {
        var capturedBaseUrl: String? = "unset"

        setContent {
            ArticleDetailPaneContent(
                article = null,
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, _, baseUrl, _ -> capturedBaseUrl = baseUrl; Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        assertEquals(null, capturedBaseUrl)
    }

    @Test
    fun copyingTheUrlShowsASnackbarWhenAHostIsProvided() = runDesktopComposeUiTest {
        val snackbarHostState = SnackbarHostState()
        setContent {
            CompositionLocalProvider(LocalSnackbarHostState provides snackbarHostState) {
                ArticleDetailPaneContent(
                    article = testArticle(),
                    modifier = Modifier.size(400.dp, 500.dp),
                    reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
                )
            }
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").performClick()
        waitForIdle()

        assertEquals("URL をコピーしました", snackbarHostState.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun copyingTheUrlDoesNotCrashWithNoHostProvided() = runDesktopComposeUiTest {
        // LocalSnackbarHostState defaults to null (desktop's own steady state — see its KDoc).
        setContent {
            ArticleDetailPaneContent(
                article = testArticle(),
                modifier = Modifier.size(400.dp, 500.dp),
                reader = { _, _, _, _ -> Box(Modifier.fillMaxSize()) },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("URL をコピー").performClick()
        waitForIdle()

        onNodeWithContentDescription("URL をコピーしました").assertExists()
    }
}

private fun testArticle(
    id: String = "a1",
    url: String = "https://example.com/$id",
    title: String = "Article $id",
    content: String? = "<p>content</p>",
    summary: String? = null,
    isStarred: Long = 0L,
): Articles = Articles(
    id = id,
    feed_id = "f1",
    guid = "g$id",
    url = url,
    title = title,
    summary = summary,
    content = content,
    author = null,
    published_at = 1_754_000_000_000L,
    thumbnail_url = null,
    is_read = 1L,
    read_at = null,
    is_starred = isStarred,
    starred_at = null,
    cached_at = 0L,
    search_text = "",
    updated_at = 0L,
    created_at = 0L,
    deleted_at = null,
    deleted_updated_at = null,
)
