package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.test.withKeyDown
import androidx.compose.ui.unit.dp
import java.awt.datatransfer.DataFlavor
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

    // `Modifier.clickable` used to back this modifier, and on a mouse platform it takes Compose
    // focus for itself on the pointer-down, one pass *after* the node under the cursor handled the
    // same down. Under a SelectionContainer (the Compose fallback article reader) that wiped the
    // selection the container had just started, so a click-and-drag selected nothing at all. See
    // `Modifier.paneActivation`'s own KDoc.
    @Test
    fun doesNotCancelATextSelectionDraggedBeneathIt() = runDesktopComposeUiTest {
        val clipboard = RecordingClipboard()
        setContent {
            CompositionLocalProvider(LocalClipboard provides clipboard) {
                Box(Modifier.size(300.dp).paneActivation(onActivated = {}, isTouchPrimary = false)) {
                    SelectionContainer {
                        Column {
                            Text("First line")
                            Text("Second line")
                        }
                    }
                }
            }
        }

        val first = onNodeWithText("First line").fetchSemanticsNode().boundsInRoot
        val second = onNodeWithText("Second line").fetchSemanticsNode().boundsInRoot
        onRoot().performMouseInput {
            moveTo(Offset(first.left + 2f, first.center.y))
            press()
            moveTo(Offset(second.center.x, second.center.y))
            moveTo(Offset(second.right - 2f, second.center.y))
            release()
        }
        waitForIdle()
        onRoot().performKeyInput { withKeyDown(copyModifierKey) { pressKey(Key.C) } }
        waitForIdle()

        assertEquals("First line\nSecond line", clipboard.copiedText)
    }
}

/** Ctrl+C everywhere except macOS, which copies with Cmd+C instead (`isCopyKeyEvent`). */
private val copyModifierKey: Key
    get() = if (System.getProperty("os.name") == "Mac OS X") Key.MetaLeft else Key.CtrlLeft

/** Captures what `SelectionContainer`'s copy handler writes, without touching the real OS clipboard. */
@OptIn(ExperimentalComposeUiApi::class)
private class RecordingClipboard : Clipboard {
    var copiedText: String? = null

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        copiedText = clipEntry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
    }

    override val nativeClipboard: NativeClipboard get() = Unit
}
