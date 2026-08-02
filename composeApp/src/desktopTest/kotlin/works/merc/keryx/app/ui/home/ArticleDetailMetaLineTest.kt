package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The detail header's metadata line used to be a single [androidx.compose.material3.Text] joining
 * the author and the timestamp with `" · "`, so both shared one ellipsis budget and a long author
 * truncated the timestamp away completely — the same defect the article list row had.
 *
 * Unlike that row, this line stays inline (`weight(fill = false)`) rather than pinning the
 * timestamp to the trailing edge, matching the WebView path that renders the same line as
 * flowing text.
 */
@OptIn(ExperimentalTestApi::class)
class ArticleDetailMetaLineTest {

    private val publishedAt = 1_754_000_000_000L

    @Test
    fun longAuthorIsClippedWithoutEatingTheTimestamp() = runDesktopComposeUiTest {
        // Derived rather than hardcoded: formatTimestamp renders in the system's local time zone.
        val timestamp = formatTimestamp(publishedAt)

        setContent {
            Column(Modifier.width(240.dp)) {
                ArticleDetailMetaLine("Alice", publishedAt)
                ArticleDetailMetaLine("とても長い著者名がここに入ります".repeat(4), publishedAt)
            }
        }
        waitForIdle()

        // The separator travels with the timestamp, so this is the whole trailing Text. With the
        // old joined string no node had exactly this text, so the count assertion would fail.
        val timestamps = onAllNodesWithText(" · $timestamp")
        timestamps.assertCountEquals(2)
        timestamps[0].assertIsDisplayed()
        timestamps[1].assertIsDisplayed()

        // The long author took none of the timestamp's width. (Right edges are deliberately NOT
        // compared: this line is inline, so the timestamp sits right after however wide the
        // author rendered.)
        val shortAuthorRow = timestamps[0].getBoundsInRoot()
        val longAuthorRow = timestamps[1].getBoundsInRoot()
        assertEquals(shortAuthorRow.right - shortAuthorRow.left, longAuthorRow.right - longAuthorRow.left)
    }

    @Test
    fun articleMetaTextJoinsAuthorAndTimestamp() {
        assertEquals("Alice · ${formatTimestamp(publishedAt)}", articleMetaText("Alice", publishedAt))
    }

    @Test
    fun articleMetaTextOmitsLeadingSeparatorWhenAuthorIsAbsent() {
        val timestamp = formatTimestamp(publishedAt)
        assertEquals(timestamp, articleMetaText(null, publishedAt))
        // A non-null but blank author must not produce a dangling leading separator either.
        assertEquals(timestamp, articleMetaText("", publishedAt))
        assertEquals(timestamp, articleMetaText("   ", publishedAt))
    }
}
