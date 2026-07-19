package works.merc.keryx.app.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.KeyInjectionScope
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KeyboardNavTest {

    private fun firedEvents(
        searchFieldFocused: Boolean = false,
        press: KeyInjectionScope.() -> Unit,
    ): List<String> {
        val fired = mutableListOf<String>()
        runDesktopComposeUiTest {
            setContent {
                Box(
                    Modifier.testTag("root").size(10.dp).focusable().homeKeyboardShortcuts(
                        searchFieldFocused = searchFieldFocused,
                        onUp = { fired += "up" },
                        onDown = { fired += "down" },
                        onLeft = { fired += "left" },
                        onRight = { fired += "right" },
                        onNextArticle = { fired += "nextArticle" },
                        onPreviousArticle = { fired += "previousArticle" },
                        onToggleRead = { fired += "toggleRead" },
                        onToggleStar = { fired += "toggleStar" },
                        onOpenInBrowser = { fired += "openInBrowser" },
                        onCopyUrl = { fired += "copyUrl" },
                        onSearch = { fired += "search" },
                    ),
                )
            }
            onNodeWithTag("root").requestFocus()
            onNodeWithTag("root").performKeyInput(press)
            waitForIdle()
        }
        return fired
    }

    @Test
    fun directionDownFiresOnDownOnly() {
        assertEquals(listOf("down"), firedEvents { pressKey(Key.DirectionDown) })
    }

    @Test
    fun directionUpFiresOnUpOnly() {
        assertEquals(listOf("up"), firedEvents { pressKey(Key.DirectionUp) })
    }

    @Test
    fun directionLeftFiresOnLeftOnly() {
        assertEquals(listOf("left"), firedEvents { pressKey(Key.DirectionLeft) })
    }

    @Test
    fun directionRightFiresOnRightOnly() {
        assertEquals(listOf("right"), firedEvents { pressKey(Key.DirectionRight) })
    }

    @Test
    fun jFiresOnNextArticleOnly() {
        // J always means "next article", regardless of which pane is logically focused —
        // it must not be confused with (or fall back to) the DirectionDown pane-aware dispatch.
        assertEquals(listOf("nextArticle"), firedEvents { pressKey(Key.J) })
    }

    @Test
    fun kFiresOnPreviousArticleOnly() {
        assertEquals(listOf("previousArticle"), firedEvents { pressKey(Key.K) })
    }

    @Test
    fun uFiresOnToggleReadOnly() {
        assertEquals(listOf("toggleRead"), firedEvents { pressKey(Key.U) })
    }

    @Test
    fun sFiresOnToggleStarOnly() {
        assertEquals(listOf("toggleStar"), firedEvents { pressKey(Key.S) })
    }

    @Test
    fun oFiresOnOpenInBrowserOnly() {
        assertEquals(listOf("openInBrowser"), firedEvents { pressKey(Key.O) })
    }

    @Test
    fun cFiresOnCopyUrlOnly() {
        assertEquals(listOf("copyUrl"), firedEvents { pressKey(Key.C) })
    }

    @Test
    fun ctrlFFiresOnSearchOnly() {
        assertEquals(listOf("search"), firedEvents { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } })
    }

    @Test
    fun plainFDoesNotFireSearch() {
        assertEquals(emptyList(), firedEvents { pressKey(Key.F) })
    }

    @Test
    fun shortcutsAreSuppressedWhileSearchFieldFocused() {
        // With the sidebar search field focused, all shortcuts step aside so typed letters/arrows
        // reach the field instead of being swallowed by this root handler.
        assertEquals(emptyList(), firedEvents(searchFieldFocused = true) { pressKey(Key.J) })
        assertEquals(emptyList(), firedEvents(searchFieldFocused = true) { pressKey(Key.DirectionDown) })
        assertEquals(
            emptyList(),
            firedEvents(searchFieldFocused = true) { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } },
        )
    }
}
