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
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalTestApi::class)
class KeyboardNavTest {

    private fun firedEvents(
        textInputFocused: Boolean = false,
        isMacOs: Boolean = false,
        press: KeyInjectionScope.() -> Unit,
    ): List<String> {
        val fired = mutableListOf<String>()
        runDesktopComposeUiTest {
            setContent {
                Box(
                    Modifier.testTag("root").size(10.dp).focusable().homeKeyboardShortcuts(
                        textInputFocused = textInputFocused,
                        onEscape = { fired += "escape"; true },
                        onUp = { fired += "up" },
                        onDown = { fired += "down" },
                        onLeft = { fired += "left" },
                        onRight = { fired += "right" },
                        onNextArticle = { fired += "nextArticle" },
                        onPreviousArticle = { fired += "previousArticle" },
                        onFeedListRename = { fired += "feedListRename" },
                        onFeedListDelete = { fired += "feedListDelete" },
                        onSearch = { fired += "search" },
                        isMacOs = isMacOs,
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
    fun bareUDoesNotFireAnything() {
        // Toggle read/star/open-in-browser/copy-URL no longer have a bare-key binding here — they
        // are Ctrl+Shift+<letter> app-menu accelerators instead (see AppMenuShortcut), since a bare
        // key was too easy to trigger by accident for actions with side effects.
        assertEquals(emptyList(), firedEvents { pressKey(Key.U) })
    }

    @Test
    fun bareSDoesNotFireAnything() {
        assertEquals(emptyList(), firedEvents { pressKey(Key.S) })
    }

    @Test
    fun bareODoesNotFireAnything() {
        assertEquals(emptyList(), firedEvents { pressKey(Key.O) })
    }

    @Test
    fun bareCDoesNotFireAnything() {
        assertEquals(emptyList(), firedEvents { pressKey(Key.C) })
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
    fun ctrlJDoesNotFireNextArticle() {
        assertEquals(emptyList(), firedEvents { withKeyDown(Key.CtrlLeft) { pressKey(Key.J) } })
    }

    @Test
    fun ctrlKDoesNotFirePreviousArticle() {
        assertEquals(emptyList(), firedEvents { withKeyDown(Key.CtrlLeft) { pressKey(Key.K) } })
    }

    @Test
    fun bareRDoesNotFireAnything() {
        // Refresh-selected-feed likewise has no bare-key binding here anymore — see AppMenuShortcut.FeedRefresh.
        assertEquals(emptyList(), firedEvents { pressKey(Key.R) })
    }

    @Test
    fun f2FiresOnFeedListRenameWhenNotMac() {
        assertEquals(listOf("feedListRename"), firedEvents(isMacOs = false) { pressKey(Key.F2) })
    }

    @Test
    fun enterDoesNotFireFeedListRenameWhenNotMac() {
        assertEquals(emptyList(), firedEvents(isMacOs = false) { pressKey(Key.Enter) })
    }

    @Test
    fun enterFiresOnFeedListRenameWhenMac() {
        assertEquals(listOf("feedListRename"), firedEvents(isMacOs = true) { pressKey(Key.Enter) })
    }

    @Test
    fun f2DoesNotFireFeedListRenameWhenMac() {
        assertEquals(emptyList(), firedEvents(isMacOs = true) { pressKey(Key.F2) })
    }

    @Test
    fun deleteFiresOnFeedListDeleteOnly() {
        assertEquals(listOf("feedListDelete"), firedEvents { pressKey(Key.Delete) })
    }

    @Test
    fun backspaceFiresOnFeedListDeleteOnly() {
        assertEquals(listOf("feedListDelete"), firedEvents { pressKey(Key.Backspace) })
    }

    @Test
    fun ctrlDeleteDoesNotFireFeedListDelete() {
        assertEquals(emptyList(), firedEvents { withKeyDown(Key.CtrlLeft) { pressKey(Key.Delete) } })
    }

    @Test
    fun escapeFiresOnEscapeOnly() {
        assertEquals(listOf("escape"), firedEvents { pressKey(Key.Escape) })
    }

    @Test
    fun escapeStillFiresWhileSearchFieldFocused() {
        // Escape is the one shortcut not suppressed by the search field: it aborts an in-progress
        // feed/folder drag, which can be running whichever element happens to hold focus.
        assertEquals(listOf("escape"), firedEvents(textInputFocused = true) { pressKey(Key.Escape) })
    }

    @Test
    fun shortcutsAreSuppressedWhileSearchFieldFocused() {
        // With the sidebar search field focused, all shortcuts step aside so typed letters/arrows
        // reach the field instead of being swallowed by this root handler.
        assertEquals(emptyList(), firedEvents(textInputFocused = true) { pressKey(Key.J) })
        assertEquals(emptyList(), firedEvents(textInputFocused = true) { pressKey(Key.DirectionDown) })
        assertEquals(
            emptyList(),
            firedEvents(textInputFocused = true) { withKeyDown(Key.CtrlLeft) { pressKey(Key.F) } },
        )
    }
}
