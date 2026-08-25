package works.merc.keryx.app.ui.common

import androidx.compose.foundation.clickable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Desktop `actual` of [KeryxPaneTopBar] — see its own KDoc: a plain `Row` reproducing each former
 * call site's layout (`navigationIcon` → [title] → trailing [actions]).
 */
@OptIn(ExperimentalTestApi::class)
class KeryxPaneTopBarTest {

    @Test
    fun rendersTitleAndActions() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                KeryxPaneTopBar(title = "Inbox", actions = { Text("Action") })
            }
        }

        onNodeWithText("Inbox").assertIsDisplayed()
        onNodeWithText("Action").assertIsDisplayed()
    }

    @Test
    fun navigationIconIsClickable() = runDesktopComposeUiTest {
        var backClicked = false
        setContent {
            MaterialTheme {
                KeryxPaneTopBar(
                    title = "Article",
                    navigationIcon = { Text("Back", Modifier.clickable { backClicked = true }) },
                )
            }
        }

        onNodeWithText("Back").performMouseInput { click() }

        assertEquals(true, backClicked)
    }

    @Test
    fun rendersNoTitleWhenNull() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                KeryxPaneTopBar(actions = { Text("Only action") })
            }
        }

        onNodeWithText("Only action").assertIsDisplayed()
    }
}
