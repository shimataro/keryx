package works.merc.keryx.app.ui.home

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class NewArticlesPillTest {

    /**
     * Waits for the pill's fade (the `animateFloatAsState` alpha driving whether it draws at all)
     * to settle, rather than a single [ComposeUiTest.waitForIdle] — mirrors
     * `ArticleListPaneTest.waitForNoSearchResultsHint`'s own reasoning for the same kind of wait.
     */
    private fun ComposeUiTest.waitForText(text: String, present: Boolean) =
        waitUntil(timeoutMillis = 3000) {
            onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() == present
        }

    /** Every real call site relies on the show delay (see [NewArticlesPill]'s own KDoc); these
     *  tests pass 0 to stay deterministic without waiting out that real 200ms window too. */
    @Test
    fun rendersTheArticleCount() = runDesktopComposeUiTest {
        setContent {
            NewArticlesPill(count = 3, up = true, onClick = {}, showDelayMillis = 0)
        }
        waitForText("新着 3 件", present = true)
    }

    @Test
    fun rendersNothingAtZeroCount() = runDesktopComposeUiTest {
        setContent {
            NewArticlesPill(count = 0, up = true, onClick = {}, showDelayMillis = 0)
        }
        waitForIdle()

        assertEquals(0, onAllNodesWithText("新着", substring = true).fetchSemanticsNodes().size)
    }

    @Test
    fun clickingInvokesOnClick() = runDesktopComposeUiTest {
        var clicks = 0
        setContent {
            NewArticlesPill(count = 5, up = true, onClick = { clicks++ }, showDelayMillis = 0)
        }
        waitForText("新着 5 件", present = true)

        onNodeWithText("新着 5 件").performClick()

        assertEquals(1, clicks)
    }

    @Test
    fun goingBackToZeroHidesItAgain() = runDesktopComposeUiTest {
        var count by mutableStateOf(2)
        setContent {
            NewArticlesPill(count = count, up = true, onClick = {}, showDelayMillis = 0)
        }
        waitForText("新着 2 件", present = true)

        count = 0

        waitForText("新着 2 件", present = false)
    }
}
