package works.merc.keryx.app.platform

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Regression guard for the desktop right-click path: right-clicking an element that has
 * [nativeContextMenu] must still invoke [onOpen] to select the row before showing the menu.
 * The Android long-press path intentionally ignores [onOpen]; this test makes sure the desktop
 * path is not accidentally changed to match.
 */
@OptIn(ExperimentalTestApi::class)
class NativeMenuDesktopGestureTest {

    @Test
    fun rightClickInvokesOnOpen() = runDesktopComposeUiTest {
        var opened = false
        setContent {
            Box(
                Modifier
                    .size(200.dp)
                    .testTag("menu-host")
                    .nativeContextMenu(
                        items = { listOf(NativeMenuItem("Test action") {}) },
                        onOpen = { opened = true },
                    )
            )
        }

        onNodeWithTag("menu-host").assertExists().performMouseInput { rightClick() }
        waitForIdle()

        assertTrue(opened, "right-click must invoke onOpen on desktop")
    }
}
