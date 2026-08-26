package works.merc.keryx.app.android

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.nativeContextMenu
import kotlin.test.assertFalse

/**
 * Gesture policy tests for the Android [nativeContextMenu] actual (long-press triggered
 * [DropdownMenu]). These live in androidApp because Compose Multiplatform's Android instrumented
 * test runner needs a real Android application module to host [createComposeRule].
 */
class NativeMenuAndroidGestureTest {

    @get:Rule
    val composeTestRule = createComposeRule()

    @Test
    fun longPressOpensMenuWithoutInvokingOnOpen() {
        var opened = false
        composeTestRule.setContent {
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

        composeTestRule.onNodeWithTag("menu-host").performTouchInput { longClick() }

        composeTestRule.onNodeWithText("Test action").assertIsDisplayed()
        assertFalse(opened, "long-press must not invoke onOpen on Android")
    }

    @Test
    fun shortTapDoesNotOpenMenu() {
        var opened = false
        composeTestRule.setContent {
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

        composeTestRule.onNodeWithTag("menu-host").performClick()

        composeTestRule.onNodeWithText("Test action").assertDoesNotExist()
        assertFalse(opened, "short tap must not invoke onOpen")
    }

    @Test
    fun swipeBeyondTouchSlopDoesNotOpenMenu() {
        var opened = false
        composeTestRule.setContent {
            Box(
                Modifier
                    .size(400.dp)
                    .testTag("menu-host")
                    .nativeContextMenu(
                        items = { listOf(NativeMenuItem("Test action") {}) },
                        onOpen = { opened = true },
                    )
            )
        }

        composeTestRule.onNodeWithTag("menu-host").performTouchInput {
            down(center)
            // Keep the pointer down (unlike a plain swipeDown(), which lifts well before the
            // long-press timeout) while moving well past touch slop, so this actually exercises
            // the slop-cancels-the-long-press branch instead of the changedToUp branch a quick
            // swipe would hit first.
            advanceEventTime(50)
            moveBy(Offset(0f, 200f))
            advanceEventTime(600)
        }

        composeTestRule.onNodeWithText("Test action").assertDoesNotExist()
        assertFalse(opened, "a move beyond touch slop must not invoke onOpen")
    }

    @Test
    fun smallWiggleWithinSlopStillOpensMenu() {
        var opened = false
        composeTestRule.setContent {
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

        composeTestRule.onNodeWithTag("menu-host").performTouchInput {
            down(center)
            // Wiggle a few pixels, well inside the typical touch slop, while keeping the pointer
            // down well past the long-press timeout so the menu reliably opens regardless of the
            // device's configured threshold.
            advanceEventTime(50)
            moveBy(Offset(3f, 3f))
            advanceEventTime(600)
        }

        composeTestRule.onNodeWithText("Test action").assertIsDisplayed()
        assertFalse(opened, "small wiggle inside slop must not invoke onOpen")
    }
}
