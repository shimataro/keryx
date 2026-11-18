package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [EditableRowName] on its own: label vs editor, and which callback each way out reaches. */
@OptIn(ExperimentalTestApi::class)
class EditableRowNameTest {

    @Test
    fun showsTheLabelAndNoEditorWhileNotEditing() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Row { EditableRowName(editing = false, label = "My feed", editValue = "My feed", onCommit = {}, onCancel = {}) }
            }
        }
        waitForIdle()
        onNodeWithText("My feed").assertIsDisplayed()
        onAllNodesWithTag(INLINE_RENAME_FIELD_TEST_TAG).assertCountEquals(0)
    }

    @Test
    fun showsTheEditorStartedFromTheEditValueWhileEditing() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                Row { EditableRowName(editing = true, label = "Label", editValue = "Edit me", onCommit = {}, onCancel = {}) }
            }
        }
        waitForIdle()
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).assertIsDisplayed()
        onNodeWithText("Edit me").assertIsDisplayed()
        onAllNodesWithText("Label").assertCountEquals(0)
    }

    @Test
    fun enterCommitsTheTrimmedValueExactlyOnce() = runDesktopComposeUiTest {
        val committed = mutableListOf<String>()
        var cancelled = 0
        setContent {
            MaterialTheme {
                Row {
                    EditableRowName(
                        editing = true, label = "Label", editValue = "Old",
                        onCommit = { committed += it }, onCancel = { cancelled++ },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).performTextReplacement("  New name  ")
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("New name"), committed)
        assertEquals(0, cancelled)
    }

    @Test
    fun escapeCancelsWithoutCommitting() = runDesktopComposeUiTest {
        val committed = mutableListOf<String>()
        var cancelled = 0
        setContent {
            MaterialTheme {
                Row {
                    EditableRowName(
                        editing = true, label = "Label", editValue = "Old",
                        onCommit = { committed += it }, onCancel = { cancelled++ },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).performTextReplacement("Changed")
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).performKeyInput { pressKey(Key.Escape) }
        waitForIdle()
        assertEquals(emptyList(), committed)
        assertEquals(1, cancelled)
    }

    @Test
    fun theCancelAffordanceCancelsWithoutCommitting() = runDesktopComposeUiTest {
        val committed = mutableListOf<String>()
        var cancelled = 0
        setContent {
            MaterialTheme {
                Row {
                    EditableRowName(
                        editing = true, label = "Label", editValue = "Old",
                        onCommit = { committed += it }, onCancel = { cancelled++ },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(INLINE_RENAME_CANCEL_TEST_TAG).performClick()
        waitForIdle()
        assertEquals(emptyList(), committed)
        assertEquals(1, cancelled)
    }

    @Test
    fun enterOnABlankValueIsSwallowedUnlessBlankIsAllowed() = runDesktopComposeUiTest {
        val committed = mutableListOf<String>()
        var allowBlank by mutableStateOf(false)
        setContent {
            MaterialTheme {
                Row {
                    EditableRowName(
                        editing = true, label = "Label", editValue = "Old",
                        onCommit = { committed += it }, onCancel = {}, allowBlank = allowBlank,
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).performTextReplacement("   ")
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(emptyList(), committed)
        allowBlank = true
        waitForIdle()
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf(""), committed)
    }

    @Test
    fun enterIsSwallowedWhileTheBlockingErrorRejectsTheValue() = runDesktopComposeUiTest {
        val committed = mutableListOf<String>()
        setContent {
            MaterialTheme {
                Row {
                    EditableRowName(
                        editing = true, label = "Label", editValue = "Old",
                        onCommit = { committed += it }, onCancel = {},
                        blockingError = { if (it == "dup") "taken" else null },
                    )
                }
            }
        }
        waitForIdle()
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).performTextReplacement("dup")
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(emptyList(), committed)
    }
}
