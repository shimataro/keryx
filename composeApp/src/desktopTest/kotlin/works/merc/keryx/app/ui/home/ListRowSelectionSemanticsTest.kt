package works.merc.keryx.app.ui.home

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelectable
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.domain.ArticleListRow
import kotlin.test.Test

/**
 * `listRowClickable` (`ListRowChrome.kt`) is built on `Modifier.selectable` specifically so a row's
 * [selected] state reaches accessibility services as this row's own semantics — not just as the
 * painted highlight from `selectionBackground`, which [LocalRowSelectionVisible] can hide entirely
 * (`PaneLayout.Single`). See review finding #3 (`v0.11.0..HEAD`). [ArticleRow] stands in for every
 * `listRowClickable` call site here; all six route through the same function.
 */
@OptIn(ExperimentalTestApi::class)
class ListRowSelectionSemanticsTest {

    @Test
    fun selectedRowExposesSelectedSemantics() = runDesktopComposeUiTest {
        setContent {
            ArticleRow(
                article = article("a1"),
                feedTitle = "Feed",
                feedFavicon = null,
                selected = true,
                focused = true,
                rowHeight = 48.dp,
                faviconSize = 20.dp,
                onClick = {},
                onToggleRead = {},
                onToggleStar = {},
                onCopyUrl = {},
                onOpenInBrowser = {},
            )
        }
        waitForIdle()

        onNodeWithText("Article a1").assertIsSelectable().assertIsSelected()
    }

    @Test
    fun unselectedRowExposesNotSelectedSemantics() = runDesktopComposeUiTest {
        setContent {
            ArticleRow(
                article = article("a1"),
                feedTitle = "Feed",
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
            )
        }
        waitForIdle()

        onNodeWithText("Article a1").assertIsSelectable().assertIsNotSelected()
    }
}

private fun article(id: String): ArticleListRow = ArticleListRow(
    id = id,
    feed_id = "f1",
    title = "Article $id",
    url = "u$id",
    published_at = 0L,
    created_at = 0L,
    is_read = 1L,
    is_starred = 0L,
)
