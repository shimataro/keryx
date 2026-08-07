package works.merc.keryx.app.ui.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.domain.ArticleListRow
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Search results reuse [ArticleRow] with a highlighted [ArticleRow] title (`titleOverride`), so
 * these cover the highlight-in-title path that the removed SearchResultRow used to own.
 */
@OptIn(ExperimentalTestApi::class)
class ArticleRowHighlightTest {

    private val start = FtsSearch.MARK_START
    private val end = FtsSearch.MARK_END

    @Test
    fun rendersStrippedHighlightedTitleAndFeed() = runDesktopComposeUiTest {
        val article = article("a1").copy(title = "Kotlin Multiplatform", is_read = 0L)
        val titleOverride = markedToAnnotatedString("${start}Kotlin${end} Multiplatform")

        setContent {
            ArticleRow(
                article = article,
                feedTitle = "My Feed",
                feedFavicon = null,
                selected = false,
                focused = true,
                rowHeight = 48.dp,
                faviconSize = 20.dp,
                onClick = {},
                onToggleRead = {},
                onToggleStar = {},
                onCopyUrl = {},
                onOpenInBrowser = {},
                titleOverride = titleOverride,
            )
        }
        waitForIdle()

        // The sentinel markers are consumed — only the plain text is rendered.
        onNodeWithText("Kotlin Multiplatform").assertIsDisplayed()
        onNodeWithText("My Feed", substring = true).assertIsDisplayed()
    }

    @Test
    fun clickInvokesCallback() = runDesktopComposeUiTest {
        var clicks = 0
        val article = article("a1").copy(title = "Clickable Title")

        setContent {
            ArticleRow(
                article = article,
                feedTitle = "Feed",
                feedFavicon = null,
                selected = false,
                focused = true,
                rowHeight = 48.dp,
                faviconSize = 20.dp,
                onClick = { clicks++ },
                onToggleRead = {},
                onToggleStar = {},
                onCopyUrl = {},
                onOpenInBrowser = {},
                titleOverride = markedToAnnotatedString("Clickable Title"),
            )
        }
        waitForIdle()

        onNodeWithText("Clickable Title").performClick()
        waitForIdle()

        assertEquals(1, clicks)
    }
}

private fun article(id: String): ArticleListRow = ArticleListRow(    id = id,
    feed_id = "f1",
    title = "Article $id",
    url = "u$id",
    published_at = 0L,
    created_at = 0L,
    is_read = 1L,
    is_starred = 0L,
)
