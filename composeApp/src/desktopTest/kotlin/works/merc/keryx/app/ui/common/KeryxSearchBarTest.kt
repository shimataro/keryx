package works.merc.keryx.app.ui.common

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The `actual` never renders in production (desktop always resolves `PaneLayout.Triple`, where
 * the feed list is a permanent pane with its own editable field instead, and narrow layouts only
 * exist on Android) — this exercises the desktop `actual` so the narrow-layout search screen is
 * still covered by `desktopTest` (see the `expect`'s own KDoc).
 */
@OptIn(ExperimentalTestApi::class)
class KeryxSearchBarTest {

    @Test
    fun expandedBarShowsTheQueryAndReportsEdits() = runDesktopComposeUiTest {
        var reported: String? = null
        setContent {
            MaterialTheme {
                KeryxExpandedSearchBar(
                    query = "kotlin",
                    onQueryChange = { reported = it },
                    placeholder = "Search articles…",
                    onNavigateUp = {},
                    navigateUpEnabled = true,
                    navigateUpContentDescription = "Back",
                    clearContentDescription = "Clear",
                    onSearchAction = {},
                )
            }
        }

        onNodeWithText("kotlin").assertIsDisplayed()
        onNode(hasSetTextAction()).performTextInput("x")

        assertEquals("kotlinx", reported)
    }

    @Test
    fun expandedBarClearButtonEmptiesTheQuery() = runDesktopComposeUiTest {
        var reported: String? = null
        setContent {
            MaterialTheme {
                KeryxExpandedSearchBar(
                    query = "kotlin",
                    onQueryChange = { reported = it },
                    placeholder = "Search articles…",
                    onNavigateUp = {},
                    navigateUpEnabled = true,
                    navigateUpContentDescription = "Back",
                    clearContentDescription = "Clear",
                    onSearchAction = {},
                )
            }
        }

        onNodeWithContentDescription("Clear").performClick()

        assertEquals("", reported)
    }

    @Test
    fun expandedBarNavigateUpInvokesOnNavigateUp() = runDesktopComposeUiTest {
        var backClicked = false
        setContent {
            MaterialTheme {
                KeryxExpandedSearchBar(
                    query = "",
                    onQueryChange = {},
                    placeholder = "Search articles…",
                    onNavigateUp = { backClicked = true },
                    navigateUpEnabled = true,
                    navigateUpContentDescription = "Back",
                    clearContentDescription = "Clear",
                    onSearchAction = {},
                )
            }
        }

        onNodeWithContentDescription("Back").performClick()

        assertEquals(true, backClicked)
    }

    @Test
    fun expandedBarNavigateUpIsDisabledWhenNotEnabled() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                KeryxExpandedSearchBar(
                    query = "",
                    onQueryChange = {},
                    placeholder = "Search articles…",
                    onNavigateUp = {},
                    navigateUpEnabled = false,
                    navigateUpContentDescription = "Back",
                    clearContentDescription = "Clear",
                    onSearchAction = {},
                )
            }
        }

        onNodeWithContentDescription("Back").assertIsNotEnabled()
    }
}
