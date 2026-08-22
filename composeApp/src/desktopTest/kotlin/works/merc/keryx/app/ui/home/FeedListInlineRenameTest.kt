package works.merc.keryx.app.ui.home

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.cancel
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end Compose UI tests for the feed list's in-row rename editor and the tag color popover,
 * which replaced the `TextPromptDialog`-based rename flow (see `InlineRename.kt`). Unlike the
 * dialogs they replaced — real native `DialogWindow`s that proved undrivable from a
 * `runDesktopComposeUiTest` in CI, see the comment in `FeedListDragTest.kt` — the editor is ordinary
 * in-window Compose content, so the whole flow can be driven with real key and mouse input and
 * asserted against the persisted DB state.
 *
 * The fixture ([newHomeViewModel]) and the DB helpers are shared with `FeedListDragTest`.
 */
@OptIn(ExperimentalTestApi::class)
class FeedListInlineRenameTest {

    /**
     * Mirrors `HomeScreen.kt`'s own wiring: the rename shortcut's request id, and — crucially —
     * `onTextInputFocusChange` feeding `homeKeyboardShortcuts`, without which the root's bare-key
     * shortcuts would swallow the editor's keystrokes. `isMacOs = false` pins the rename key to F2
     * so these tests behave identically on every runner (macOS binds Return instead).
     */
    @Composable
    private fun InlineRenameTestHost(vm: HomeViewModel) {
        val dragOverlay = remember { FeedDragOverlayState() }
        var renameSelectedRequestId by remember { mutableStateOf(0) }
        var deleteSelectedRequestId by remember { mutableStateOf(0) }
        var textInputFocused by remember { mutableStateOf(false) }
        Box(
            Modifier.testTag(ROOT_TEST_TAG).size(320.dp, 700.dp).focusable().homeKeyboardShortcuts(
                textInputFocused = textInputFocused,
                onEscape = { false },
                onUp = {},
                onDown = {},
                onLeft = {},
                onRight = {},
                onNextArticle = {},
                onPreviousArticle = {},
                onFeedListRename = { renameSelectedRequestId++ },
                onFeedListDelete = { deleteSelectedRequestId++ },
                onSearch = {},
                isMacOs = false,
            ),
        ) {
            FeedListPane(
                vm = vm,
                focused = true,
                dragOverlay = dragOverlay,
                onActivated = {},
                onTextInputFocusChange = { textInputFocused = it },
                renameSelectedRequestId = renameSelectedRequestId,
                deleteSelectedRequestId = deleteSelectedRequestId,
            )
        }
    }

    private fun ComposeUiTest.setInlineRenameContent(vm: HomeViewModel, menuController: MenuController = testMenuController) {
        setContent {
            KoinApplication(configuration = koinConfiguration { modules(module { single { menuController } }) }) {
                InlineRenameTestHost(vm)
            }
        }
        waitForIdle()
    }

    /**
     * Selects [filter] on [instance] (defaults to the folder-canonical row), then starts inline
     * editing on it with the real F2 shortcut.
     */
    private fun ComposeUiTest.startInlineRename(
        vm: HomeViewModel,
        filter: ArticleFilter,
        instance: FeedListRowSelection = FeedListRowSelection.canonicalFor(filter),
    ) {
        vm.selectFilter(filter, instance)
        waitForIdle()
        onNodeWithTag(ROOT_TEST_TAG).requestFocus()
        onNodeWithTag(ROOT_TEST_TAG).performKeyInput { pressKey(Key.F2) }
        waitForIdle()
        editor().assertIsDisplayed()
    }

    private fun ComposeUiTest.editor() = onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG, useUnmergedTree = true)

    private fun ComposeUiTest.typeName(name: String) {
        editor().performTextReplacement(name)
        waitForIdle()
    }

    private fun ComposeUiTest.pressEnter() {
        editor().performKeyInput { pressKey(Key.Enter) }
        waitForIdle()
    }

    /** Moves focus off the editor the way clicking anywhere else does, exercising the blur path. */
    private fun ComposeUiTest.blur() {
        onNodeWithTag(ROOT_TEST_TAG).requestFocus()
        waitForIdle()
    }

    private fun KeryxDatabase.customTitleOf(feedId: String): String? =
        feedsQueries.getById(feedId).executeAsOne().custom_title

    private fun KeryxDatabase.folderNameOf(folderId: String): String =
        foldersQueries.watchAll().executeAsList().first { it.id == folderId }.name

    private fun KeryxDatabase.tagOf(tagId: String) = tagsQueries.watchAll().executeAsList().first { it.id == tagId }

    @Test
    fun f2StartsInlineEditingAndEnterCommitsTheNewFeedTitle() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"))

            typeName("Renamed")
            pressEnter()

            assertEquals("Renamed", db.customTitleOf("a"))
            editor().assertDoesNotExist()
            onNodeWithText("Renamed", useUnmergedTree = true).assertExists()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun escapeCancelsInlineEditingAndRestoresTheOriginalName() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"))

            typeName("Discarded")
            editor().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            assertNull(db.customTitleOf("a"), "Escape must never write")
            editor().assertDoesNotExist()
            onNodeWithText("Feed a", useUnmergedTree = true).assertExists()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun theCancelIconAbandonsTheEditTheSameWayEscapeDoes() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"))

            typeName("Discarded")
            onNodeWithTag(INLINE_RENAME_CANCEL_TEST_TAG, useUnmergedTree = true).performClick()
            waitForIdle()

            assertNull(db.customTitleOf("a"))
            editor().assertDoesNotExist()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun losingFocusCommitsAValidName() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"))

            typeName("Renamed by blur")
            blur()

            assertEquals("Renamed by blur", db.customTitleOf("a"))
            editor().assertDoesNotExist()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun committingABlankFeedTitleResetsItToTheFeedsOwnTitle() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.feedsQueries.updateCustomTitle("Custom", 0L, 0L, "a")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"))

            typeName("")
            // The placeholder stands in for the dialog's old "blank resets to the default title"
            // supporting line, which would have made the row taller than the label it replaced.
            onNodeWithText("Feed a", useUnmergedTree = true).assertExists()

            pressEnter()

            assertNull(db.customTitleOf("a"), "a blank title clears custom_title")
            onNodeWithText("Feed a", useUnmergedTree = true).assertExists()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun aDuplicateFolderNameBlocksEnterAndRevertsSilentlyOnBlur() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Alpha", sortOrder = 0L)
        db.insertFolder("d2", "Beta", sortOrder = 1L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Folder("d2"))

            typeName("Alpha")
            pressEnter()

            assertEquals("Beta", db.folderNameOf("d2"), "a duplicate name must not be committable")
            editor().assertIsDisplayed()

            blur()

            assertEquals("Beta", db.folderNameOf("d2"), "blur on an invalid value reverts rather than commits")
            editor().assertDoesNotExist()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun aBlankFolderNameCannotBeCommitted() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Alpha", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Folder("d1"))

            typeName("   ")
            pressEnter()

            assertEquals("Alpha", db.folderNameOf("d1"))
            editor().assertIsDisplayed()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun aTagIsRenamedInPlaceKeepingItsColor() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.tagsQueries.upsert(
            id = "tag1", name = "Tag One", color = "#43A047", sort_order = 0L,
            deleted_at = null, updated_at = 0L, created_at = 0L,
        )
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Tag("tag1"))

            typeName("Renamed tag")
            pressEnter()

            val tag = db.tagOf("tag1")
            assertEquals("Renamed tag", tag.name)
            assertEquals("#43A047", tag.color, "renaming must not touch the color")
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun theTagColorDotAppliesAColorImmediately() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("tag1", "Tag One", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setInlineRenameContent(vm)

            onNodeWithTag(tagColorDotTestTag("tag1"), useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(tagColorSwatchTestTag("#1E88E5"), useUnmergedTree = true).performClick()
            waitForIdle()

            val tag = db.tagOf("tag1")
            assertEquals("#1E88E5", tag.color)
            assertEquals("Tag One", tag.name, "picking a color must not touch the name")
            onNodeWithTag(tagColorSwatchTestTag("#1E88E5"), useUnmergedTree = true).assertDoesNotExist()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun theTagColorDotStillWorksWhileTheRowIsBeingRenamed() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("tag1", "Tag One", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Tag("tag1"))
            typeName("Renamed tag")

            // The dot is a plain click target regardless of edit mode; taking focus commits the
            // in-progress name first (the documented blur behavior), and the color lands on top.
            onNodeWithTag(tagColorDotTestTag("tag1"), useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(tagColorSwatchTestTag("#E53935"), useUnmergedTree = true).performClick()
            waitForIdle()

            val tag = db.tagOf("tag1")
            assertEquals("Renamed tag", tag.name)
            assertEquals("#E53935", tag.color)
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun theRenameFeedMenuCommandStartsInlineEditingForTheSelection() = runDesktopComposeUiTest {
        // The entry point the Feed menu bar and the KDE Global Menu use. The row's own right-click
        // menu item reaches the same edit-mode state, but a real AWT/Swing popup is outside a
        // Compose UI test's reach (see `nativeContextMenu` and the manual checks in docs/testing.md).
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        val menuController = testMenuController
        try {
            setInlineRenameContent(vm, menuController)
            vm.selectFilter(ArticleFilter.Feed("a"))
            onNodeWithTag(ROOT_TEST_TAG).requestFocus()
            waitForIdle()

            // `send` resumes its collector on the calling thread, so the resulting state write lands
            // outside the scene's own dispatch and needs an extra pass to reach the composition.
            menuController.send(MenuCommand.RenameFeed)
            waitForIdle()
            waitUntil { onAllNodesWithTag(INLINE_RENAME_FIELD_TEST_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

            editor().assertIsDisplayed()
            typeName("From the menu")
            pressEnter()
            assertEquals("From the menu", db.customTitleOf("a"))
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun f2StartsInlineEditingOnTheTagNestedRowWhenItIsThePrimarySelection() = runDesktopComposeUiTest {
        // Regression test for the bug where a feed selected via its tag-nested row instead opened
        // the editor on its folder-group row, force-expanding the (collapsed) folder to do it.
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("a", folderId = "d1", sortOrder = 0L)
        db.insertTag("t1", "Tag One", sortOrder = 0L)
        db.insertFeedTag("a", "t1")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            // The feed's only rendered row is the tag-nested one: its folder is collapsed while the
            // tag it's attached to is expanded.
            vm.toggleFolderCollapsed("d1")
            vm.toggleTagExpanded("t1")
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"), FeedListRowSelection.FeedInTag("a", "t1"))

            // The folder must never be force-expanded to host the editor.
            assertTrue("d1" in vm.collapsedFolderIds.value)

            typeName("Renamed via tag row")
            pressEnter()

            assertEquals("Renamed via tag row", db.customTitleOf("a"))
            editor().assertDoesNotExist()
            onNodeWithText("Renamed via tag row", useUnmergedTree = true).assertExists()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun theRenameFeedMenuCommandStartsInlineEditingOnTheTagNestedRowWhenItIsThePrimarySelection() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("a", folderId = "d1", sortOrder = 0L)
        db.insertTag("t1", "Tag One", sortOrder = 0L)
        db.insertFeedTag("a", "t1")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        val menuController = testMenuController
        try {
            vm.toggleFolderCollapsed("d1")
            vm.toggleTagExpanded("t1")
            setInlineRenameContent(vm, menuController)
            vm.selectFilter(ArticleFilter.Feed("a"), FeedListRowSelection.FeedInTag("a", "t1"))
            onNodeWithTag(ROOT_TEST_TAG).requestFocus()
            waitForIdle()

            menuController.send(MenuCommand.RenameFeed)
            waitForIdle()
            waitUntil { onAllNodesWithTag(INLINE_RENAME_FIELD_TEST_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

            editor().assertIsDisplayed()
            assertTrue("d1" in vm.collapsedFolderIds.value)
            typeName("From the menu via tag row")
            pressEnter()
            assertEquals("From the menu via tag row", db.customTitleOf("a"))
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun folderRowRenameStillWorksWhenTheFeedIsAlsoAttachedToATag() = runDesktopComposeUiTest {
        // No regression: a feed carrying a tag that also renders under it must still edit its
        // folder-group row when that's the instance actually selected.
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("a", folderId = "d1", sortOrder = 0L)
        db.insertTag("t1", "Tag One", sortOrder = 0L)
        db.insertFeedTag("a", "t1")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            // The feed renders twice: once under its folder, once under the expanded tag.
            vm.toggleTagExpanded("t1")
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"), FeedListRowSelection.FeedInFolderGroup("a"))

            assertEquals(1, onAllNodesWithTag(INLINE_RENAME_FIELD_TEST_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)

            typeName("Renamed via folder row")
            pressEnter()

            assertEquals("Renamed via folder row", db.customTitleOf("a"))
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun aStrandedTagRowEditIsClosedWhenItsTagCollapsesMidEdit() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertTag("t1", "Tag One", sortOrder = 0L)
        db.insertFeedTag("a", "t1")
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            vm.toggleTagExpanded("t1")
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"), FeedListRowSelection.FeedInTag("a", "t1"))

            // The tag collapses out from under the in-progress edit — the row hosting the editor
            // stops rendering entirely, and nothing else would ever call onRenameCommit/onRenameCancel.
            vm.toggleTagExpanded("t1")
            waitForIdle()

            editor().assertDoesNotExist()

            // inlineEdit must not be stuck: a fresh F2 elsewhere still opens an editor. The editor's
            // own field took focus while it was open, so it must be moved back to the root first,
            // exactly like the other tests' blur() does after closing an editor.
            onNodeWithTag(ROOT_TEST_TAG).requestFocus()
            onNodeWithTag(ROOT_TEST_TAG).performKeyInput { pressKey(Key.F2) }
            waitForIdle()
            editor().assertIsDisplayed()

            // Close the reopened editor before teardown: an editor left mounted would fire its
            // blur-commit as the scene disposes, writing to the DB after driver.close() below.
            editor().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }
}

private const val ROOT_TEST_TAG = "root"
