package works.merc.keryx.app.ui.home

import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
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

    // --- ripplePulse (see ArticleReturnRippleTest for the pure-function/timing coverage of
    // ripplePulseFor/playPulseRipple this wiring itself calls into) ---

    @Test
    fun aNonzeroRipplePulsePlaysAPressOnTheProvidedInteractionSource() = runDesktopComposeUiTest {
        val interactionSource = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()
        var pulse by mutableStateOf(0)

        setContent {
            LaunchedEffect(Unit) { interactionSource.interactions.collect { interactions += it } }
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
                interactionSource = interactionSource,
                ripplePulse = pulse,
            )
        }
        waitForIdle()
        assertEquals(emptyList(), interactions)

        pulse = 1
        waitForIdle()

        // Only the immediate Press is asserted here — playPulseRipple's Release follows a 220ms
        // delay, and waiting that out reliably inside a Compose UI test (rather than kotlinx-
        // coroutines-test's virtual time) isn't a pattern this codebase already relies on
        // elsewhere; the full Press-then-Release sequence and its timing are covered
        // deterministically by ArticleReturnRippleTest's own playPulseRipple test instead.
        assertEquals(listOf(PressInteraction.Press::class), interactions.map { it::class })
    }

    @Test
    fun aZeroRipplePulseNeverPlaysAnything() = runDesktopComposeUiTest {
        val interactionSource = MutableInteractionSource()
        val interactions = mutableListOf<Interaction>()

        setContent {
            LaunchedEffect(Unit) { interactionSource.interactions.collect { interactions += it } }
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
                interactionSource = interactionSource,
                ripplePulse = 0,
            )
        }
        waitForIdle()

        assertEquals(emptyList(), interactions)
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
