package works.merc.keryx.app.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KeryxTextFieldTest {

    @Test
    fun showsPlaceholderWhenEmptyAndHidesItAfterInput() = runDesktopComposeUiTest {
        var text by mutableStateOf("")
        setContent {
            KeryxTextField(value = text, onValueChange = { text = it }, placeholder = "Search…")
        }
        waitForIdle()

        // Placeholder is shown while the field is empty.
        onNodeWithText("Search…").assertIsDisplayed()

        onNode(hasSetTextAction()).performTextInput("keryx")
        waitForIdle()

        // onValueChange propagated the input, and the placeholder is gone.
        assertEquals("keryx", text)
        onNodeWithText("Search…").assertDoesNotExist()
    }

    @Test
    fun rendersTrailingIcon() = runDesktopComposeUiTest {
        setContent {
            KeryxTextField(
                value = "x",
                onValueChange = {},
                trailingIcon = { KeryxIcon(KeryxIcons.CloseFilled, contentDescription = "Clear") },
            )
        }
        waitForIdle()

        onNodeWithContentDescription("Clear").assertIsDisplayed()
    }

    @Test
    fun fieldHeightStaysStableWhenTallTrailingAppears() = runDesktopComposeUiTest {
        // Mirrors the search box: a tall trailing (40dp) appears only once there's input. The field
        // must keep a stable height (heightIn(min = 40.dp)) so it doesn't jump when the × shows.
        var text by mutableStateOf("")
        setContent {
            KeryxTextField(
                value = text,
                onValueChange = { text = it },
                trailingIcon = { if (text.isNotEmpty()) Box(Modifier.size(40.dp)) },
                modifier = Modifier.testTag("field"),
            )
        }
        waitForIdle()

        onNodeWithTag("field").assertHeightIsEqualTo(40.dp)

        onNode(hasSetTextAction()).performTextInput("x")
        waitForIdle()

        onNodeWithTag("field").assertHeightIsEqualTo(40.dp)
    }
}
