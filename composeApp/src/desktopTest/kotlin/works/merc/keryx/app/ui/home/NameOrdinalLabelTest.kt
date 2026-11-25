package works.merc.keryx.app.ui.home

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.presentation.home.DropBoundary
import works.merc.keryx.app.presentation.home.OrdinalName
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Same-named tags and folders are numbered on screen ("Work", "Work (2)") — the display contract
 * the sidebar rows already rely on, exercised here on mock rows: the DB no longer keeps two live
 * rows of one name out of a create dialog (`NameValidation` warns the user instead), and a sync
 * merge can insert one, so the rows must render the ordinal correctly (external-spec §7). The
 * rename field's initial value stays the row's own name, ordinal-free.
 */
@OptIn(ExperimentalTestApi::class)
class NameOrdinalLabelTest {

    private fun tag(id: String, name: String): Tags = Tags(
        id = id,
        name = name,
        color = null,
        sort_order = 0L,
        deleted_at = null,
        updated_at = 0L,
        created_at = 0L,
        name_updated_at = null,
        color_updated_at = null,
        sort_order_updated_at = null,
        v2_id = null,
    )

    private fun folder(id: String, name: String): Folders = Folders(
        id = id,
        name = name,
        sort_order = 0L,
        deleted_at = null,
        updated_at = 0L,
        created_at = 0L,
        name_updated_at = null,
        sort_order_updated_at = null,
        v2_id = null,
    )

    @Test
    fun aTagWithAnOrdinalShowsItAndAUniquelyNamedOneShowsTheBareName() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                TagRow(
                    tag = tag("t2", "Work"),
                    label = OrdinalName("Work", 2),
                    count = 0,
                    expanded = false,
                    selected = false,
                    focused = false,
                    isDropTarget = false,
                    onToggleExpanded = {},
                    onClick = {},
                    onEdit = {},
                    onDelete = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Work (2)").assertIsDisplayed()
    }

    @Test
    fun aUniquelyNamedTagShowsTheBareName() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                TagRow(
                    tag = tag("t1", "Job"),
                    label = OrdinalName("Job", null),
                    count = 0,
                    expanded = false,
                    selected = false,
                    focused = false,
                    isDropTarget = false,
                    onToggleExpanded = {},
                    onClick = {},
                    onEdit = {},
                    onDelete = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Job").assertIsDisplayed()
    }

    @Test
    fun aFolderWithAnOrdinalShowsIt() = runDesktopComposeUiTest {
        setContent {
            MaterialTheme {
                FolderGroupHeader(
                    folder = folder("d2", "Work"),
                    label = OrdinalName("Work", 2),
                    count = 0,
                    collapsed = false,
                    selected = false,
                    focused = false,
                    firstFeedId = null,
                    nextFolderId = null,
                    feedIdsInFolder = emptySet(),
                    activeBoundaryState = remember { mutableStateOf<DropBoundary?>(null) },
                    onToggleCollapse = {},
                    onClick = {},
                    onEdit = {},
                    onDelete = {},
                )
            }
        }
        waitForIdle()
        onNodeWithText("Work (2)").assertIsDisplayed()
    }

    @Test
    fun theRenameEditorStartsFromTheRowSOwnNameWithoutTheOrdinal() = runDesktopComposeUiTest {
        val committed = mutableListOf<String>()
        setContent {
            MaterialTheme {
                TagRow(
                    tag = tag("t2", "Work"),
                    label = OrdinalName("Work", 2),
                    count = 0,
                    expanded = false,
                    selected = false,
                    focused = false,
                    isDropTarget = false,
                    onToggleExpanded = {},
                    onClick = {},
                    onEdit = {},
                    onDelete = {},
                    editingName = true,
                    onRenameCommit = { committed += it },
                    onRenameCancel = {},
                )
            }
        }
        waitForIdle()
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).assertIsDisplayed()
        // The editor starts from the raw name and commits it unchanged: a rename must write back
        // exactly what the row holds, not the numbered display label.
        onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG).performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
        assertEquals(listOf("Work"), committed)
    }
}
