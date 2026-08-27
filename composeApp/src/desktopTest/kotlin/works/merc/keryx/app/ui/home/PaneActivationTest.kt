package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [Modifier.paneActivation] backs the click-to-focus behavior on `FeedListPane`/`ArticleListPane`/
 * `ArticleDetailPane`'s background — see its own KDoc for why it must disappear on touch.
 */
@OptIn(ExperimentalTestApi::class)
class PaneActivationTest {

    @Test
    fun clickingInvokesOnActivatedWhenNotTouchPrimary() = runDesktopComposeUiTest {
        var activatedCount = 0
        setContent {
            Box(
                Modifier.testTag("pane").size(100.dp)
                    .paneActivation(onActivated = { activatedCount++ }, isTouchPrimary = false),
            )
        }

        onNodeWithTag("pane").performMouseInput { click() }

        assertEquals(1, activatedCount)
    }

    @Test
    fun clickingDoesNothingWhenTouchPrimary() = runDesktopComposeUiTest {
        var activatedCount = 0
        setContent {
            Box(
                Modifier.testTag("pane").size(100.dp)
                    .paneActivation(onActivated = { activatedCount++ }, isTouchPrimary = true),
            )
        }

        onNodeWithTag("pane").performMouseInput { click() }

        assertEquals(0, activatedCount)
    }
}
