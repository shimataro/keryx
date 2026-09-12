package works.merc.keryx.app.android

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.click
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
import kotlin.test.assertEquals
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
            up()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Test action").assertDoesNotExist()
        assertFalse(opened, "a move beyond touch slop must not invoke onOpen")
    }

    @Test
    fun emptyItemsListConsumesLongPressWithoutOpeningMenu() {
        var opened = false
        composeTestRule.setContent {
            Box(
                Modifier
                    .size(200.dp)
                    .testTag("menu-host")
                    .nativeContextMenu(
                        items = { emptyList() },
                        onOpen = { opened = true },
                    )
            )
        }

        composeTestRule.onNodeWithTag("menu-host").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        // No menu is shown for an empty items list, but the long-press gesture is still consumed
        // so that chained clickables (e.g. listRowClickable) do not also fire.
        assertFalse(opened, "empty items must not invoke onOpen on Android")
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
            up()
        }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Test action").assertIsDisplayed()
        assertFalse(opened, "small wiggle inside slop must not invoke onOpen")
    }

    /**
     * Regression guard for a real Keryx row shape: [nativeContextMenu] on the row, a plain
     * `clickable` on a nested child (e.g. the tag color dot or the folder/tag expand chevron). A
     * long press anywhere in the row — including on top of the child — must open only the row's
     * menu; the child's own tap must not also fire once the finger lifts. Before the `Initial`-pass
     * fix, the child (a descendant, resolved before the ancestor on the `Main` pass `clickable`
     * uses) would see an unconsumed `up` and fire its `onClick` in addition to the menu opening.
     */
    @Test
    fun longPressDoesNotFireANestedChildClickable() {
        var childClicks = 0
        composeTestRule.setContent {
            Box(
                Modifier
                    .size(200.dp)
                    .testTag("menu-host")
                    .nativeContextMenu(
                        items = { listOf(NativeMenuItem("Test action") {}) },
                        onOpen = {},
                    ),
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .testTag("child")
                        .clickable { childClicks++ },
                )
            }
        }

        composeTestRule.onNodeWithTag("child").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Test action").assertIsDisplayed()
        assertEquals(0, childClicks, "a long press must not also fire the nested child's onClick")
    }

    /** The short-tap counterpart of [longPressDoesNotFireANestedChildClickable]: an ordinary tap on
     * the nested child must still reach its own `onClick`, and must not open the row's menu. */
    @Test
    fun shortTapOnANestedChildStillFiresItsClickable() {
        var childClicks = 0
        composeTestRule.setContent {
            Box(
                Modifier
                    .size(200.dp)
                    .testTag("menu-host")
                    .nativeContextMenu(
                        items = { listOf(NativeMenuItem("Test action") {}) },
                        onOpen = {},
                    ),
            ) {
                Box(
                    Modifier
                        .size(48.dp)
                        .testTag("child")
                        .clickable { childClicks++ },
                )
            }
        }

        composeTestRule.onNodeWithTag("child").performTouchInput { click() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Test action").assertDoesNotExist()
        assertEquals(1, childClicks, "a short tap on the nested child must still fire its onClick")
    }

    /**
     * Regression guard for a pane background carrying an empty-items [nativeContextMenu] (e.g.
     * `FeedListPane`'s/`ArticleListPane`'s own background, used to move keyboard focus on
     * desktop's right-click) around every row, each of which has its own [nativeContextMenu] with
     * real items. A long press on a row must still open *that row's* menu — the empty-items
     * ancestor's own claim must not win the race and swallow the press first.
     */
    @Test
    fun longPressInsideAnEmptyMenuAncestorStillOpensTheInnerMenu() {
        var childClicks = 0
        composeTestRule.setContent {
            Box(
                Modifier
                    .size(300.dp)
                    .testTag("pane-background")
                    .nativeContextMenu(items = { emptyList() }, onOpen = {}),
            ) {
                Box(
                    Modifier
                        .size(200.dp)
                        .testTag("row")
                        .nativeContextMenu(
                            items = { listOf(NativeMenuItem("Inner action") {}) },
                            onOpen = {},
                        ),
                ) {
                    Box(
                        Modifier
                            .size(48.dp)
                            .testTag("child")
                            .clickable { childClicks++ },
                    )
                }
            }
        }

        composeTestRule.onNodeWithTag("child").performTouchInput { longClick() }
        composeTestRule.waitForIdle()

        composeTestRule.onNodeWithText("Inner action").assertIsDisplayed()
        assertEquals(0, childClicks, "the row's own menu must win over the empty-items ancestor")
    }
}
