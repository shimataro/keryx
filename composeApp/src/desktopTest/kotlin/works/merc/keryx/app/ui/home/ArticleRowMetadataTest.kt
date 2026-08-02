package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import works.merc.keryx.app.data.local.db.Articles
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The article card's metadata line used to be a single [androidx.compose.material3.Text] joining
 * the feed title and the timestamp with `" · "`, so both shared one ellipsis budget and a long
 * feed title truncated the timestamp away completely.
 */
@OptIn(ExperimentalTestApi::class)
class ArticleRowMetadataTest {

    @Test
    fun longFeedTitleIsClippedWithoutEatingTheTimestamp() = runDesktopComposeUiTest {
        val publishedAt = 1_754_000_000_000L
        // Derived rather than hardcoded: formatTimestamp renders in the system's local time zone.
        val timestamp = formatTimestamp(publishedAt)

        setContent {
            Column(Modifier.width(320.dp)) {
                MetadataTestRow(article("short", publishedAt), feedTitle = "My Feed")
                MetadataTestRow(article("long", publishedAt), feedTitle = "とても長いフィードタイトル".repeat(4))
            }
        }
        waitForIdle()

        // Two independent timestamp nodes: with the old joined string there was none at all,
        // since that node's text was "<feed title> · <timestamp>".
        val timestamps = onAllNodesWithText(timestamp)
        timestamps.assertCountEquals(2)
        timestamps[0].assertIsDisplayed()
        timestamps[1].assertIsDisplayed()

        val shortTitleRow = timestamps[0].getBoundsInRoot()
        val longTitleRow = timestamps[1].getBoundsInRoot()
        // The long feed title took none of the timestamp's width...
        assertEquals(shortTitleRow.right - shortTitleRow.left, longTitleRow.right - longTitleRow.left)
        // ...and both timestamps stay pinned to the same trailing edge.
        assertEquals(shortTitleRow.right, longTitleRow.right)
    }
}

/** Renders an [ArticleRow] with only the parameters this test varies. */
@Composable
private fun MetadataTestRow(article: Articles, feedTitle: String) {
    ArticleRow(
        article = article,
        feedTitle = feedTitle,
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

private fun article(id: String, publishedAt: Long): Articles = Articles(
    id = id,
    feed_id = "f1",
    guid = "g$id",
    url = "u$id",
    title = "Article $id",
    summary = null,
    content = null,
    author = null,
    published_at = publishedAt,
    thumbnail_url = null,
    is_read = 1L,
    read_at = null,
    is_starred = 0L,
    starred_at = null,
    cached_at = 0L,
    search_text = "",
    updated_at = 0L,
    created_at = 0L,
    deleted_at = null,
    deleted_updated_at = null,
)
