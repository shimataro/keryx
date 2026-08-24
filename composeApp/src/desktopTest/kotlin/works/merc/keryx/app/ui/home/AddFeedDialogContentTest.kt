package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import works.merc.keryx.app.core.DiscoveredFeedLink
import works.merc.keryx.app.core.DiscoveredFeedType
import works.merc.keryx.app.domain.AddFeedPreview
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class AddFeedDialogContentTest {

    private val candidates = listOf(
        DiscoveredFeedLink("https://ex.com/a.xml", "Alpha", DiscoveredFeedType.Rss),
        DiscoveredFeedLink("https://ex.com/b.xml", "Beta", DiscoveredFeedType.Atom),
    )

    @Test
    fun rendersCandidateRowsForMultiplePreview() = runDesktopComposeUiTest {
        setContent {
            AddFeedDialogContent(
                url = "https://ex.com",
                onUrlChange = {},
                alreadySubscribed = false,
                phase = null,
                preview = AddFeedPreview.Multiple(candidates),
                selectedCandidates = candidates.map { it.url }.toSet(),
                onToggleCandidate = { _, _ -> },
                onSelectAll = {},
                onClearAll = {},
                errorException = null,
                onSubmit = {},
            )
        }
        waitForIdle()

        onNodeWithText("Alpha").assertIsDisplayed()
        onNodeWithText("Beta").assertIsDisplayed()
    }

    @Test
    fun selectAllAndClearAllToggleTheSelection() = runDesktopComposeUiTest {
        var selected by mutableStateOf(candidates.map { it.url }.toSet())
        setContent {
            AddFeedDialogContent(
                url = "https://ex.com",
                onUrlChange = {},
                alreadySubscribed = false,
                phase = null,
                preview = AddFeedPreview.Multiple(candidates),
                selectedCandidates = selected,
                onToggleCandidate = { url, checked -> selected = if (checked) selected + url else selected - url },
                onSelectAll = { selected = candidates.map { it.url }.toSet() },
                onClearAll = { selected = emptySet() },
                errorException = null,
                onSubmit = {},
            )
        }
        waitForIdle()

        // All selected → the toggle reads "clear all"; clicking it empties the selection.
        onNodeWithText("すべて解除").performClick()
        waitForIdle()
        assertEquals(emptySet(), selected)

        // Now none selected → the toggle reads "select all"; clicking it re-selects everything.
        onNodeWithText("すべて選択").performClick()
        waitForIdle()
        assertEquals(candidates.map { it.url }.toSet(), selected)
    }

    @Test
    fun candidateListSurvivesEnclosingVerticalScroll() = runDesktopComposeUiTest {
        // Mirrors production: KeryxAlertDialog's text slot wraps this content in a verticalScroll,
        // which measures children with infinite height. The candidate LazyColumn must stay bounded
        // (heightIn) or it would crash with an infinite-max-height constraint. Rendering the rows
        // without an exception is the assertion.
        setContent {
            Column(Modifier.size(400.dp, 300.dp).verticalScroll(rememberScrollState())) {
                AddFeedDialogContent(
                    url = "https://ex.com",
                    onUrlChange = {},
                    alreadySubscribed = false,
                    phase = null,
                    preview = AddFeedPreview.Multiple(candidates),
                    selectedCandidates = candidates.map { it.url }.toSet(),
                    onToggleCandidate = { _, _ -> },
                    onSelectAll = {},
                    onClearAll = {},
                    errorException = null,
                    onSubmit = {},
                )
            }
        }
        waitForIdle()

        onNodeWithText("Alpha").assertIsDisplayed()
    }

    @Test
    fun showsTitleAndArticleCountForSinglePreview() = runDesktopComposeUiTest {
        setContent {
            AddFeedDialogContent(
                url = "https://ex.com/feed",
                onUrlChange = {},
                alreadySubscribed = false,
                phase = null,
                preview = AddFeedPreview.Single("https://ex.com/feed", "Feed", 3),
                selectedCandidates = emptySet(),
                onToggleCandidate = { _, _ -> },
                onSelectAll = {},
                onClearAll = {},
                errorException = null,
                onSubmit = {},
            )
        }
        waitForIdle()

        onNodeWithText("Feed").assertIsDisplayed()
        onNodeWithText("記事 3 件").assertIsDisplayed()
    }

    @Test
    fun showsArticleCountAtSingularQuantity() = runDesktopComposeUiTest {
        // Japanese has only a CLDR "other" bucket, so this exercises the plural quantity/formatArgs
        // plumbing (home_add_feed_article_count is now a <plurals> resource) rather than wording.
        setContent {
            AddFeedDialogContent(
                url = "https://ex.com/feed",
                onUrlChange = {},
                alreadySubscribed = false,
                phase = null,
                preview = AddFeedPreview.Single("https://ex.com/feed", "Feed", 1),
                selectedCandidates = emptySet(),
                onToggleCandidate = { _, _ -> },
                onSelectAll = {},
                onClearAll = {},
                errorException = null,
                onSubmit = {},
            )
        }
        waitForIdle()

        onNodeWithText("記事 1 件").assertIsDisplayed()
    }

    @Test
    fun showsPartialResultText() = runDesktopComposeUiTest {
        setContent {
            AddFeedDialogContent(
                url = "https://ex.com",
                onUrlChange = {},
                alreadySubscribed = false,
                phase = null,
                preview = AddFeedPreview.Multiple(candidates),
                selectedCandidates = emptySet(),
                onToggleCandidate = { _, _ -> },
                onSelectAll = {},
                onClearAll = {},
                errorException = null,
                onSubmit = {},
                partialResult = 3 to 1,
            )
        }
        waitForIdle()

        onNodeWithText("3 件追加、1 件失敗しました").assertIsDisplayed()
    }
}
