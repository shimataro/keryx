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
import androidx.compose.ui.test.assertTextContains
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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.awaitConditionBlocking
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.TagFolderIdRemap
import works.merc.keryx.app.domain.TagFolderIdRemaps
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.rekeyFolderForTest
import works.merc.keryx.app.rekeyTagForTest
import works.merc.keryx.app.presentation.home.FeedListRowSelection
import works.merc.keryx.app.presentation.home.HomeViewModel
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import javax.swing.SwingUtilities
import kotlin.coroutines.CoroutineContext
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
        var textInput by remember { mutableStateOf<HomeTextInput?>(null) }
        // The feed list is the only pane here, so it is always the keyboard target.
        val feedListKeysActive = feedListItemKeysActive(HomePane.FeedList, textInputFocused = textInput != null)
        Box(
            Modifier.testTag(ROOT_TEST_TAG).size(320.dp, 700.dp).focusable().homeKeyboardShortcuts(
                textInputFocused = textInput != null,
                onEscape = { false },
                onUp = {},
                onDown = {},
                onLeft = {},
                onRight = {},
                onNextArticle = {},
                onPreviousArticle = {},
                onFeedListRename = if (feedListKeysActive) ({ renameSelectedRequestId++ }) else null,
                onFeedListDelete = if (feedListKeysActive) ({ deleteSelectedRequestId++ }) else null,
                onSearch = {},
                isMacOs = false,
            ),
        ) {
            FeedListPane(
                vm = vm,
                focused = true,
                dragOverlay = dragOverlay,
                onActivated = {},
                onTextInputFocusChange = { textInput = it },
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
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"))

            typeName("Renamed")
            pressEnter()

            assertEquals("Renamed", db.customTitleOf("a"))
            editor().assertDoesNotExist()
            onNodeWithText("Renamed", useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun escapeCancelsInlineEditingAndRestoresTheOriginalName() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"))

            typeName("Discarded")
            editor().performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            assertNull(db.customTitleOf("a"), "Escape must never write")
            editor().assertDoesNotExist()
            onNodeWithText("Feed a", useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun theCancelIconAbandonsTheEditTheSameWayEscapeDoes() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"))

            typeName("Discarded")
            onNodeWithTag(INLINE_RENAME_CANCEL_TEST_TAG, useUnmergedTree = true).performClick()
            waitForIdle()

            assertNull(db.customTitleOf("a"))
            editor().assertDoesNotExist()
        }
    }

    @Test
    fun losingFocusCommitsAValidName() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"))

            typeName("Renamed by blur")
            blur()

            assertEquals("Renamed by blur", db.customTitleOf("a"))
            editor().assertDoesNotExist()
        }
    }

    @Test
    fun committingABlankFeedTitleResetsItToTheFeedsOwnTitle() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.feedsQueries.updateCustomTitle("Custom", 0L, 0L, "a")
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"))

            typeName("")
            // The placeholder stands in for the dialog's old "blank resets to the default title"
            // supporting line, which would have made the row taller than the label it replaced.
            onNodeWithText("Feed a", useUnmergedTree = true).assertExists()

            pressEnter()

            assertNull(db.customTitleOf("a"), "a blank title clears custom_title")
            onNodeWithText("Feed a", useUnmergedTree = true).assertExists()
        }
    }

    @Test
    fun aDuplicateFolderNameBlocksEnterAndRevertsSilentlyOnBlur() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Alpha", sortOrder = 0L)
        db.insertFolder("d2", "Beta", sortOrder = 1L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Folder("d2"))

            typeName("Alpha")
            pressEnter()

            assertEquals("Beta", db.folderNameOf("d2"), "a duplicate name must not be committable")
            editor().assertIsDisplayed()

            blur()

            assertEquals("Beta", db.folderNameOf("d2"), "blur on an invalid value reverts rather than commits")
            editor().assertDoesNotExist()
        }
    }

    @Test
    fun aBlankFolderNameCannotBeCommitted() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Alpha", sortOrder = 0L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Folder("d1"))

            typeName("   ")
            pressEnter()

            assertEquals("Alpha", db.folderNameOf("d1"))
            editor().assertIsDisplayed()
        }
    }

    @Test
    fun aTagIsRenamedInPlaceKeepingItsColor() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.tagsQueries.upsert(
            id = "tag1", name = "Tag One", color = "#43A047", sort_order = 0L,
            deleted_at = null, updated_at = 0L, created_at = 0L,
        )
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Tag("tag1"))

            typeName("Renamed tag")
            pressEnter()

            val tag = db.tagOf("tag1")
            assertEquals("Renamed tag", tag.name)
            assertEquals("#43A047", tag.color, "renaming must not touch the color")
        }
    }

    @Test
    fun theTagColorDotAppliesAColorImmediately() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("tag1", "Tag One", sortOrder = 0L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)

            onNodeWithTag(tagColorDotTestTag("tag1"), useUnmergedTree = true).performClick()
            waitForIdle()
            onNodeWithTag(tagColorSwatchTestTag("#1E88E5"), useUnmergedTree = true).performClick()
            waitForIdle()

            val tag = db.tagOf("tag1")
            assertEquals("#1E88E5", tag.color)
            assertEquals("Tag One", tag.name, "picking a color must not touch the name")
            onNodeWithTag(tagColorSwatchTestTag("#1E88E5"), useUnmergedTree = true).assertDoesNotExist()
        }
    }

    @Test
    fun theTagColorDotStillWorksWhileTheRowIsBeingRenamed() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("tag1", "Tag One", sortOrder = 0L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
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
        }
    }

    @Test
    fun theRenameFeedMenuCommandStartsInlineEditingForTheSelection() = runDesktopComposeUiTest {
        // The entry point the Feed menu bar and the KDE Global Menu use. The row's own right-click
        // menu item reaches the same edit-mode state, but a real AWT/Swing popup is outside a
        // Compose UI test's reach (see `nativeContextMenu` and the manual checks in docs/testing.md).
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            val menuController = testMenuController
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
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
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
        }
    }

    @Test
    fun theRenameFeedMenuCommandStartsInlineEditingOnTheTagNestedRowWhenItIsThePrimarySelection() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("a", folderId = "d1", sortOrder = 0L)
        db.insertTag("t1", "Tag One", sortOrder = 0L)
        db.insertFeedTag("a", "t1")
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            val menuController = testMenuController
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
        }
    }

    @Test
    fun f2OnAFeedHiddenInACollapsedFolderExpandsThatFolderAndStartsEditing() = runDesktopComposeUiTest {
        // The feed was selected, then its folder collapsed over it: the selection stays on the
        // feed's (now unrendered) folder-group row. F2 must reveal it rather than silently do nothing.
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("a", folderId = "d1", sortOrder = 0L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            vm.selectFilter(ArticleFilter.Feed("a"))
            vm.toggleFolderCollapsed("d1")
            waitForIdle()
            onNodeWithTag(ROOT_TEST_TAG).requestFocus()
            onNodeWithTag(ROOT_TEST_TAG).performKeyInput { pressKey(Key.F2) }
            waitForIdle()
            waitUntil { onAllNodesWithTag(INLINE_RENAME_FIELD_TEST_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

            assertTrue("d1" !in vm.collapsedFolderIds.value)
            editor().assertIsDisplayed()
            typeName("Revealed and renamed")
            pressEnter()
            assertEquals("Revealed and renamed", db.customTitleOf("a"))
        }
    }

    @Test
    fun theRenameFeedMenuCommandRevealsAFeedHiddenInACollapsedFolder() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("a", folderId = "d1", sortOrder = 0L)
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            val menuController = testMenuController
            setInlineRenameContent(vm, menuController)
            vm.selectFilter(ArticleFilter.Feed("a"))
            vm.toggleFolderCollapsed("d1")
            onNodeWithTag(ROOT_TEST_TAG).requestFocus()
            waitForIdle()

            menuController.send(MenuCommand.RenameFeed)
            waitForIdle()
            waitUntil { onAllNodesWithTag(INLINE_RENAME_FIELD_TEST_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty() }

            assertTrue("d1" !in vm.collapsedFolderIds.value)
            editor().assertIsDisplayed()
            typeName("From the menu, revealed")
            pressEnter()
            assertEquals("From the menu, revealed", db.customTitleOf("a"))
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
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
            // The feed renders twice: once under its folder, once under the expanded tag.
            vm.toggleTagExpanded("t1")
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Feed("a"), FeedListRowSelection.FeedInFolderGroup("a"))

            assertEquals(1, onAllNodesWithTag(INLINE_RENAME_FIELD_TEST_TAG, useUnmergedTree = true).fetchSemanticsNodes().size)

            typeName("Renamed via folder row")
            pressEnter()

            assertEquals("Renamed via folder row", db.customTitleOf("a"))
        }
    }

    @Test
    fun aStrandedTagRowEditIsClosedWhenItsTagCollapsesMidEdit() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertTag("t1", "Tag One", sortOrder = 0L)
        db.insertFeedTag("a", "t1")
        useHomeViewModel(driver, db) { fixture ->
            val vm = fixture.vm
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
        }
    }
    @Test
    fun aReKeyOfTheTagBeingRenamedKeepsTheEditorOpenWithTheTypedText() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("t-old", "Tag One", sortOrder = 0L)
        val remaps = TagFolderIdRemaps(db, Dispatchers.Unconfined)
        useHomeViewModel(driver, db, idRemaps = remaps) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Tag("t-old"))
            typeName("Half typed")

            // A sync merge re-keys the tag onto another id while the editor is open.
            // As a merge does: the re-key is published before the lists that changed are notified.
            driver.rekeyTagForTest("t-old", "t-new") { runBlocking { remaps.publishFromDb() } }
            waitForIdle()

            editor().assertIsDisplayed()
            editor().assertTextContains("Half typed")
            pressEnter()
            assertEquals("Half typed", db.tagOf("t-new").name, "the commit lands on the row the tag became")
        }
    }

    @Test
    fun aReKeyOfTheFolderBeingRenamedKeepsTheEditorOpenWithTheTypedText() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d-old", "Folder One", sortOrder = 0L)
        val remaps = TagFolderIdRemaps(db, Dispatchers.Unconfined)
        useHomeViewModel(driver, db, idRemaps = remaps) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Folder("d-old"))
            typeName("Half typed")

            driver.rekeyFolderForTest("d-old", "d-new") { runBlocking { remaps.publishFromDb() } }
            waitForIdle()

            editor().assertIsDisplayed()
            editor().assertTextContains("Half typed")
            pressEnter()
            assertEquals("Half typed", db.folderNameOf("d-new"))
        }
    }

    /**
     * The other order: another write re-runs the lists over the committed re-key before the merge has
     * published the map (nothing publishes here at all). The row's absence is not believed until the map
     * is caught up from the database, so the editor stays open with the typed text.
     */
    @Test
    fun aListAheadOfTheMapDoesNotDiscardTheTagRenameInProgress() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("t-old", "Tag One", sortOrder = 0L)
        val remaps = TagFolderIdRemaps(db, Dispatchers.Unconfined)
        useHomeViewModel(driver, db, idRemaps = remaps) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Tag("t-old"))
            typeName("Half typed")

            driver.rekeyTagForTest("t-old", "t-new")
            waitForIdle()

            editor().assertIsDisplayed()
            editor().assertTextContains("Half typed")
            pressEnter()
            assertEquals("Half typed", db.tagOf("t-new").name)
        }
    }

    /** As [aListAheadOfTheMapDoesNotDiscardTheTagRenameInProgress], for a folder. */
    @Test
    fun aListAheadOfTheMapDoesNotDiscardTheFolderRenameInProgress() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d-old", "Folder One", sortOrder = 0L)
        val remaps = TagFolderIdRemaps(db, Dispatchers.Unconfined)
        useHomeViewModel(driver, db, idRemaps = remaps) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Folder("d-old"))
            typeName("Half typed")

            driver.rekeyFolderForTest("d-old", "d-new")
            waitForIdle()

            editor().assertIsDisplayed()
            editor().assertTextContains("Half typed")
            pressEnter()
            assertEquals("Half typed", db.folderNameOf("d-new"))
        }
    }

    /**
     * The map ahead of the lists by more than one re-key: t-a -> t-b has reached both, then t-b -> t-c
     * is published (folded: t-a -> t-c, t-b -> t-c) while the lists still show t-b. Neither the edit's
     * own id (t-a) nor its remapped one (t-c) is in the lists, but t-b is the same row: the editor
     * stays on it — through the stranded-edit check and its catch-up — with the typed text, and carries
     * on onto t-c once the lists show it.
     */
    @Test
    fun aMapAheadOfTheListsByAChainedReKeyDoesNotDiscardTheRenameInProgress() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("t-a", "Tag One", sortOrder = 0L)
        val remaps = TagFolderIdRemaps(db, Dispatchers.Unconfined)
        useHomeViewModel(driver, db, idRemaps = remaps) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Tag("t-a"))
            typeName("Half typed")

            driver.rekeyTagForTest("t-a", "t-b") { runBlocking { remaps.publishFromDb() } }
            awaitTags(vm, "t-b")
            editor().assertTextContains("Half typed")

            // The second re-key's map is published and composed — the stranded-edit check and its
            // catch-up run on it — before the lists are notified of the re-key, so they still show t-b.
            driver.rekeyTagForTest("t-b", "t-c") {
                runBlocking { remaps.publishFromDb() }
                assertEquals(mapOf("t-a" to "t-c", "t-b" to "t-c"), vm.tagFolderIdRemap.value.tagIds)
                waitForIdle()
                mainClock.advanceTimeByFrame()
                waitForIdle()
                assertEquals(listOf("t-b"), vm.tags.value.map { it.id }, "the lists have not caught up yet")
                editor().assertIsDisplayed()
                editor().assertTextContains("Half typed")
            }
            awaitTags(vm, "t-c")

            editor().assertIsDisplayed()
            editor().assertTextContains("Half typed")
            pressEnter()
            assertEquals("Half typed", db.tagOf("t-c").name, "the commit lands on the row the tag became")
        }
    }

    /**
     * A [TagFolderIdRemaps] whose reads of `ks_id_remap` fail while [failing] is set — the initial load
     * succeeds (the flag starts clear), so only the catch-up a test provokes afterwards fails.
     */
    private class FailableIdRemaps(db: KeryxDatabase) {
        @Volatile var failing = false
        val remaps = TagFolderIdRemaps({
            check(!failing) { "ks_id_remap read failed (test)" }
            val rows = db.sync_bookkeepingQueries.idRemapTagFolder().executeAsList()
            TagFolderIdRemap(
                tagIds = rows.filter { it.kind == "tag" }.associate { it.old_id to it.new_id },
                folderIds = rows.filter { it.kind == "folder" }.associate { it.old_id to it.new_id },
            )
        }, Dispatchers.Unconfined)
    }

    /**
     * Waits until the ViewModel's tag list shows exactly [ids], then for Compose to settle. The list
     * queries re-run on the repositories' own dispatcher, which [waitForIdle] alone does not wait for.
     */
    private fun ComposeUiTest.awaitTags(vm: HomeViewModel, vararg ids: String) {
        waitUntil(timeoutMillis = 5_000) { vm.tags.value.map { it.id }.toSet() == ids.toSet() }
        waitForIdle()
    }

    /**
     * The lists reach the editor ahead of the map, and catching the map up fails: the row's absence is
     * left undecided — the editor stays open with the typed text. Once the map can be read, the next
     * list value decides it: the row only moved, so the edit carries on and commits onto the new id.
     */
    @Test
    fun aFailedCatchUpKeepsTheEditUntilTheNextListValueFindsTheMovedRow() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("t-old", "Tag One", sortOrder = 0L)
        val failable = FailableIdRemaps(db)
        useHomeViewModel(driver, db, idRemaps = failable.remaps) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Tag("t-old"))
            typeName("Half typed")

            failable.failing = true
            driver.rekeyTagForTest("t-old", "t-new")
            awaitTags(vm, "t-new")

            // The lists show the tag only under its new id, which the stale map does not join to the
            // edit's: no row hosts the editor, but the edit (and its draft) is kept, undecided.
            editor().assertDoesNotExist()

            // The map can be read again; another tag arriving is the next list value.
            failable.failing = false
            db.insertTag("t-other", "Tag Two", sortOrder = 1L)
            awaitTags(vm, "t-new", "t-other")

            editor().assertIsDisplayed()
            editor().assertTextContains("Half typed")
            pressEnter()
            assertEquals("Half typed", db.tagOf("t-new").name, "the commit lands on the row the tag became")
        }
    }

    /**
     * As [aFailedCatchUpKeepsTheEditUntilTheNextListValueFindsTheMovedRow], for a row that is really
     * gone: the failed catch-up keeps the edit (the row coming back shows it again, typed text and
     * all), and the next list value — with the map readable — ends it (the row coming back then shows
     * no editor).
     */
    @Test
    fun aFailedCatchUpKeepsTheEditUntilTheNextListValueConfirmsTheRowGone() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("t1", "Tag One", sortOrder = 0L)
        val failable = FailableIdRemaps(db)
        useHomeViewModel(driver, db, idRemaps = failable.remaps) { fixture ->
            val vm = fixture.vm
            setInlineRenameContent(vm)
            startInlineRename(vm, ArticleFilter.Tag("t1"))
            typeName("Half typed")

            fun reviveT1() {
                driver.execute(null, "UPDATE tags SET deleted_at = NULL WHERE id = 't1'", 0)
                driver.notifyListeners("tags")
            }

            failable.failing = true
            db.tagsQueries.softDelete(deleted_at = 1L, updated_at = 1L, id = "t1")
            awaitTags(vm)
            editor().assertDoesNotExist()

            // Still undecided: the row coming back finds the edit where it was.
            reviveT1()
            awaitTags(vm, "t1")
            editor().assertIsDisplayed()
            editor().assertTextContains("Half typed")

            db.tagsQueries.softDelete(deleted_at = 2L, updated_at = 2L, id = "t1")
            awaitTags(vm)
            editor().assertDoesNotExist()

            // The map can be read again; another tag arriving is the next list value, which confirms
            // the row gone and ends the edit.
            failable.failing = false
            db.insertTag("t-other", "Tag Two", sortOrder = 1L)
            awaitTags(vm, "t-other")

            reviveT1()
            awaitTags(vm, "t1", "t-other")
            editor().assertDoesNotExist()
        }
    }

    /**
     * The ViewModel's background dispatcher: inline (as [Dispatchers.Unconfined] is) until [diverting]
     * is set, then onto threads of its own — so a catch-up started then reads `ks_id_remap` and waits
     * at [GatedIdRemaps]'s gate off the EDT, while the test commits another re-key and the lists
     * re-run (also on these threads) around it. [inFlight] counts the blocks still running there.
     */
    private class DivertingDispatcher : CoroutineDispatcher() {
        @Volatile var diverting = false
        val inFlight = AtomicInteger(0)
        private val pool: ExecutorService = Executors.newCachedThreadPool { runnable ->
            Thread(runnable, "diverted-vm-dispatcher").apply { isDaemon = true }
        }

        override fun isDispatchNeeded(context: CoroutineContext): Boolean = diverting

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            if (!diverting) return block.run()
            inFlight.incrementAndGet()
            pool.execute {
                try {
                    block.run()
                } finally {
                    inFlight.decrementAndGet()
                }
            }
        }

        fun shutdown() {
            pool.shutdownNow()
        }
    }

    /**
     * A [TagFolderIdRemaps] whose next read, once armed ([arm]), reads `ks_id_remap` when it starts —
     * as the database read does — and then waits at a gate until [release]: a re-key committed
     * meanwhile is not in the map it publishes. Later reads are ordinary.
     */
    private class GatedIdRemaps(db: KeryxDatabase) {
        @Volatile private var gate: CountDownLatch? = null
        val entered = CountDownLatch(1)
        val remaps = TagFolderIdRemaps({
            val rows = db.sync_bookkeepingQueries.idRemapTagFolder().executeAsList()
            val read = TagFolderIdRemap(
                tagIds = rows.filter { it.kind == "tag" }.associate { it.old_id to it.new_id },
                folderIds = rows.filter { it.kind == "folder" }.associate { it.old_id to it.new_id },
            )
            gate?.let { held ->
                gate = null
                entered.countDown()
                check(held.await(5, TimeUnit.SECONDS)) { "the gated read was never released (test)" }
            }
            read
        }, Dispatchers.Unconfined)

        /** Arms the next read; returns the gate it waits at, opened by [release]. */
        fun arm(): CountDownLatch = CountDownLatch(1).also { gate = it }

        fun release(gate: CountDownLatch) = gate.countDown()
    }

    /**
     * Two re-keys in a row, t-a -> t-b then t-b -> t-c, with the edit's catch-up between them: the list
     * showing t-b starts a catch-up, which reads the first re-key only; the second commits, and the
     * lists show t-c, before that catch-up returns. Judged against the map it read, t-c is not the
     * edit's row — but the lists changed during the catch-up, so nothing is decided on them there: the
     * newer lists get a catch-up of their own, which reads the second re-key and finds the row. The
     * editor stays open with the typed text, and the commit lands on t-c.
     */
    @Test
    fun aListNewerThanTheCaughtUpMapDoesNotDiscardTheRenameInProgress() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertTag("t-a", "Tag One", sortOrder = 0L)
        val gated = GatedIdRemaps(db)
        val diverting = DivertingDispatcher()
        try {
            useHomeViewModel(driver, db, dispatcher = diverting, idRemaps = gated.remaps) { fixture ->
                val vm = fixture.vm
                setInlineRenameContent(vm)
                startInlineRename(vm, ArticleFilter.Tag("t-a"))
                typeName("Half typed")

                // The first re-key commits and the lists re-run over it; nothing publishes the map.
                driver.rekeyTagForTest("t-a", "t-b")
                awaitConditionBlocking { vm.tags.value.map { it.id } == listOf("t-b") }

                // The lists reach the editor: the edit's catch-up starts and is held after its read.
                val gate = gated.arm()
                diverting.diverting = true
                waitForIdle()
                assertTrue(gated.entered.await(5, TimeUnit.SECONDS), "the edit's catch-up started")

                // The second re-key commits and the lists re-run over it while that catch-up is held.
                // Frames are stopped, so the editor's composition does not recompose (which would
                // cancel the held run) while the lists' delivery reaches it: the collected lists show
                // t-c when the catch-up returns, before any recomposition — as when both are queued on
                // the main thread ahead of the next frame.
                mainClock.autoAdvance = false
                try {
                    driver.rekeyTagForTest("t-b", "t-c")
                    awaitConditionBlocking { vm.tags.value.map { it.id } == listOf("t-c") }
                    diverting.diverting = false
                    gated.release(gate)
                    // The held catch-up publishes the map it read (the first re-key only) and resumes
                    // on the EDT; drain the EDT (twice: anything that continuation queues runs too) so it
                    // has run before frames resume.
                    awaitConditionBlocking { diverting.inFlight.get() == 0 }
                    assertEquals(mapOf("t-a" to "t-b"), vm.tagFolderIdRemap.value.tagIds)
                    SwingUtilities.invokeAndWait {}
                    SwingUtilities.invokeAndWait {}
                } finally {
                    mainClock.autoAdvance = true
                }

                // The newer lists' own catch-up reads the second re-key, and the row is found under it.
                awaitTags(vm, "t-c")
                waitUntil("the editor is back on the re-keyed row", timeoutMillis = 5_000) {
                    onAllNodesWithTag(INLINE_RENAME_FIELD_TEST_TAG, useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }
                assertEquals("t-c", vm.tagFolderIdRemap.value.tag("t-a"))

                editor().assertIsDisplayed()
                editor().assertTextContains("Half typed")
                pressEnter()
                assertEquals("Half typed", db.tagOf("t-c").name, "the commit lands on the row the tag became")
            }
        } finally {
            diverting.shutdown()
        }
    }
}

private const val ROOT_TEST_TAG = "root"
