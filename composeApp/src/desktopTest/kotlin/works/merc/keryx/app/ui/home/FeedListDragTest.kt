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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.runDesktopComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.compose.KoinApplication
import org.koin.dsl.module
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.singleProviderCloudSession
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * End-to-end Compose UI tests for the hand-rolled (non-OS-level) feed-list reorder/attach drag: a
 * real `pointerInput` gesture hosted on `FeedListPane`'s drag-host `Box`
 * ([FEED_LIST_DRAG_HOST_TEST_TAG]), driven here through [performMouseInput] exactly like real mouse
 * input, with assertions against the actual persisted DB state (not a mocked controller) — this is
 * precisely the coverage the OS-level-DnD -> Compose-native rewrite was meant to unlock (see
 * `FeedListDragController.kt` / `FeedListDragGestures.kt`).
 */
@OptIn(ExperimentalTestApi::class)
class FeedListDragTest {

    /** Comfortably above `FeedListDragGestures.kt`'s private `MOUSE_DRAG_THRESHOLD_DP` (4dp), so a
     * single move reliably crosses the drag-start threshold regardless of test-environment density
     * rounding. */
    private val androidx.compose.ui.test.ComposeUiTest.dragThresholdCrossPx: Float
        get() = with(density) { 10.dp.toPx() }

    /** [nodeCenterInRoot] translated into [hostBoundsInRoot]'s local coordinate space — the space
     * [performMouseInput] positions are expressed in when invoked on the drag-host node itself. */
    private fun localOf(nodeCenterInRoot: Offset, hostBoundsInRoot: Rect): Offset =
        Offset(nodeCenterInRoot.x - hostBoundsInRoot.left, nodeCenterInRoot.y - hostBoundsInRoot.top)

    @Composable
    private fun FeedListDragTestHost(vm: HomeViewModel, dragOverlay: FeedDragOverlayState) {
        // Mirrors HomeScreen.kt's own request-id wiring for the keyboard rename/delete shortcuts,
        // so tests can drive them end to end (real F2/Delete key presses -> real FeedListPane
        // dialogs) exactly like a user would, rather than poking FeedListPane's private state.
        var renameSelectedRequestId by remember { mutableStateOf(0) }
        var deleteSelectedRequestId by remember { mutableStateOf(0) }
        Box(
            Modifier.testTag("root").size(320.dp, 700.dp).focusable().homeKeyboardShortcuts(
                searchFieldFocused = false,
                onEscape = { dragOverlay.cancel() },
                onUp = {},
                onDown = {},
                onLeft = {},
                onRight = {},
                onNextArticle = {},
                onPreviousArticle = {},
                onFeedListRename = { renameSelectedRequestId++ },
                onFeedListDelete = { deleteSelectedRequestId++ },
                onSearch = {},
            ),
        ) {
            FeedListPane(
                vm = vm,
                focused = true,
                dragOverlay = dragOverlay,
                onActivated = {},
                renameSelectedRequestId = renameSelectedRequestId,
                deleteSelectedRequestId = deleteSelectedRequestId,
            )
            FeedDragGhost(dragOverlay)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.setFeedListDragContent(
        vm: HomeViewModel,
        dragOverlay: FeedDragOverlayState = FeedDragOverlayState(),
        // Only needed by tests that open a KeryxAlertDialog (e.g. the delete/unsubscribe confirm
        // dialogs the rename/delete shortcuts trigger) — DesktopModalWindow reads it via koinInject
        // for the theme mode. Existing drag tests never open one, so this stays null for them.
        settingsRepository: SettingsRepository? = null,
        // Tests that need to send a MenuCommand (e.g. RenameFeed/UnsubscribeFeed, mirroring the Feed
        // menu bar's items) pass their own instance so they can call send(...) on the exact instance
        // FeedListPane is collecting from.
        menuController: MenuController = MenuController(),
    ): FeedDragOverlayState {
        setContent {
            KoinApplication(
                application = {
                    modules(
                        module {
                            single { menuController }
                            settingsRepository?.let { sr -> single { sr } }
                        },
                    )
                },
            ) {
                FeedListDragTestHost(vm, dragOverlay)
            }
        }
        return dragOverlay
    }

    @Test
    fun dragsAFeedAboveAnotherAndPersistsTheNewOrder() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        db.insertFeed("c", sortOrder = 2L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val cBounds = onNodeWithText("Feed c", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(cBounds.center, hostBounds)
            // Top quarter of "a"'s row resolves to its BEFORE boundary (see resolveRowHalf).
            val target = localOf(Offset(aBounds.center.x, aBounds.top + aBounds.height * 0.25f), hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(target)
                release()
            }
            waitForIdle()

            val order = db.feedsQueries.getByFolder(null).executeAsList().map { it.id }
            assertEquals(listOf("c", "a", "b"), order)
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun draggingOutsideTheHostHorizontallyNeverAppliesADrop() = runDesktopComposeUiTest {
        // Row hit-testing (bandAt/resolveHitBand) only ever compares vertical position, so a
        // pointerInput-based gesture — unlike the platform DnD this replaced, whose target
        // dispatch was bounds-based — keeps receiving move/release events even once the pointer
        // has traveled out over a sibling pane. Without an explicit horizontal-bounds check
        // (isWithinHost), a release out there would still land on whichever row happens to sit at
        // the same height as the release point.
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val bBounds = onNodeWithText("Feed b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)
            // Same height as "b"'s row, but far to the right of the host's own width — as if the
            // pointer had traveled out over the article list pane.
            val outsideRight = localOf(Offset(hostBounds.right + 200f, bBounds.center.y), hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(outsideRight)
            }
            waitForIdle()
            // Must not show as a valid target just because it shares "b"'s row height.
            onNodeWithTag(FEED_DRAG_GHOST_TEST_TAG, useUnmergedTree = true).assertExists()

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput { release() }
            waitForIdle()
            onNodeWithTag(FEED_DRAG_GHOST_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()

            assertEquals(listOf("a", "b"), db.feedsQueries.getByFolder(null).executeAsList().map { it.id })
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun movingLessThanTheThresholdDoesNotStartADragAndStillSelects() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        db.insertFeed("c", sortOrder = 2L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, 1f)) // well under the 4dp threshold
                release()
            }
            waitForIdle()

            val order = db.feedsQueries.getByFolder(null).executeAsList().map { it.id }
            assertEquals(listOf("a", "b", "c"), order, "a sub-threshold move must not reorder anything")
            assertEquals(ArticleFilter.Feed("a"), vm.filter.value, "the row's own click must still register as a selection")
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun droppingAFeedOnAFolderHeaderMovesItIntoThatFolder() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("folder1", "Folder One", sortOrder = 0L)
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val folderBounds = onNodeWithText("Folder One", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)
            val target = localOf(folderBounds.center, hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(target)
                release()
            }
            waitForIdle()

            assertEquals("folder1", db.feedsQueries.getById("a").executeAsOne().folder_id)
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun droppingAFeedOnATagRowAttachesTheTagWithoutMovingIt() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("folder1", "Folder One", sortOrder = 0L)
        db.insertFeed("a", sortOrder = 0L, folderId = "folder1")
        db.insertTag("tag1", "Tag One", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val tagBounds = onNodeWithText("Tag One", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)
            val target = localOf(tagBounds.center, hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(target)
                release()
            }
            waitForIdle()

            assertEquals(listOf("tag1"), db.feed_tagsQueries.watchTagIdsForFeed("a").executeAsList())
            assertEquals(
                "folder1",
                db.feedsQueries.getById("a").executeAsOne().folder_id,
                "attaching a tag must not move the feed out of its folder",
            )
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun aRightClickDuringADragDoesNotOpenTheContextMenuAndDoesNotAbortTheDrag() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        db.insertFeed("c", sortOrder = 2L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val cBounds = onNodeWithText("Feed c", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)
            // Bottom quarter of "c" (the last row) resolves to "append to the end" (see
            // FeedListDropIndex.belowBoundaryForFeed / nextFeedInGroup == null).
            val target = localOf(Offset(cBounds.center.x, cBounds.top + cBounds.height * 0.75f), hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
            }
            waitForIdle()
            onNodeWithTag(FEED_DRAG_GHOST_TEST_TAG, useUnmergedTree = true).assertExists()

            // A right-click landing mid-drag: the primary button is still held throughout, exactly
            // like a real user accidentally bumping the secondary button while dragging.
            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                press(MouseButton.Secondary)
                release(MouseButton.Secondary)
            }
            waitForIdle()

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(target)
                release(MouseButton.Primary)
            }
            waitForIdle()

            // No native context menu opened and no click fired anywhere (left or right) — the whole
            // gesture stayed a single drag from press to the final release.
            assertEquals(ArticleFilter.All, vm.filter.value)
            val order = db.feedsQueries.getByFolder(null).executeAsList().map { it.id }
            assertEquals(listOf("b", "c", "a"), order, "the drag must still complete despite the mid-drag right-click")
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun theDragGhostAppearsOnlyWhileDragging() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()
            onNodeWithTag(FEED_DRAG_GHOST_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
            }
            waitForIdle()
            onNodeWithTag(FEED_DRAG_GHOST_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start + Offset(0f, dragThresholdCrossPx))
            }
            waitForIdle()
            onNodeWithTag(FEED_DRAG_GHOST_TEST_TAG, useUnmergedTree = true).assertExists()

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                release()
            }
            waitForIdle()
            onNodeWithTag(FEED_DRAG_GHOST_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun escapeCancelsADragWithoutReordering() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        db.insertFeed("c", sortOrder = 2L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()
            onNodeWithTag("root", useUnmergedTree = true).requestFocus()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val cBounds = onNodeWithText("Feed c", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)
            val target = localOf(cBounds.center, hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
            }
            waitForIdle()
            onNodeWithTag(FEED_DRAG_GHOST_TEST_TAG, useUnmergedTree = true).assertExists()

            onNodeWithTag("root", useUnmergedTree = true).performKeyInput { pressKey(Key.Escape) }
            waitForIdle()

            onNodeWithTag(FEED_DRAG_GHOST_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
            assertEquals(listOf("a", "b", "c"), db.feedsQueries.getByFolder(null).executeAsList().map { it.id })

            // Release the now-orphaned mouse gesture (the pointerInput loop's own `dragging` flag
            // wasn't told about the external cancel) to leave no dangling press for later tests.
            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(target)
                release()
            }
            waitForIdle()

            assertEquals(listOf("a", "b", "c"), db.feedsQueries.getByFolder(null).executeAsList().map { it.id })
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun dragsAFolderOntoAnotherFolderToReorder() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Alpha", sortOrder = 0L)
        db.insertFolder("d2", "Beta", sortOrder = 1L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val betaBounds = onNodeWithText("Beta", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val alphaBounds = onNodeWithText("Alpha", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(betaBounds.center, hostBounds)
            // Top quarter of "Alpha" resolves to BeforeFolder("d1") (see resolveRowHalf).
            val target = localOf(Offset(alphaBounds.center.x, alphaBounds.top + alphaBounds.height * 0.25f), hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(target)
                release()
            }
            waitForIdle()

            val order = db.foldersQueries.watchAll().executeAsList().map { it.id }
            assertEquals(listOf("d2", "d1"), order)
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun pressingTheRenameKeyWithAFeedSelectedOpensTheRenameDialog() = runDesktopComposeUiTest {
        // FeedListDragTestHost wires homeKeyboardShortcuts with the real platform isMacOs (same as
        // production HomeScreen.kt), so the key that fires the rename shortcut is OS-dependent —
        // F2 everywhere except macOS, where it's Enter/Return (see KeyboardNav.kt).
        val renameKey = if (works.merc.keryx.app.platform.isMacOs) Key.Enter else Key.F2
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm, settingsRepository = fixture.settingsRepository)
            waitForIdle()
            vm.selectFilter(ArticleFilter.Feed("a"))
            waitForIdle()

            onNodeWithTag("root").requestFocus()
            onNodeWithTag("root").performKeyInput { pressKey(renameKey) }
            waitForIdle()

            onNodeWithText("タイトルを変更").assertExists()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun pressingDeleteWithAFeedSelectedOpensTheUnsubscribeConfirmation() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm, settingsRepository = fixture.settingsRepository)
            waitForIdle()
            vm.selectFilter(ArticleFilter.Feed("a"))
            waitForIdle()

            onNodeWithTag("root").requestFocus()
            onNodeWithTag("root").performKeyInput { pressKey(Key.Delete) }
            waitForIdle()

            onNodeWithText("「Feed a」の購読を削除しますか？").assertExists()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun pressingDeleteWithNoFeedFolderOrTagSelectedOpensNoDialog() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()
            vm.selectFilter(ArticleFilter.All)
            waitForIdle()

            onNodeWithTag("root").requestFocus()
            onNodeWithTag("root").performKeyInput { pressKey(Key.Delete) }
            waitForIdle()

            onNodeWithText("「Feed a」の購読を削除しますか？").assertDoesNotExist()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }

    @Test
    fun sendingRenameOrUnsubscribeFeedCommandWithNoFeedSelectedOpensNoDialog() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        val menuController = MenuController()
        try {
            setFeedListDragContent(vm, menuController = menuController)
            waitForIdle()
            vm.selectFilter(ArticleFilter.All)
            waitForIdle()

            menuController.send(MenuCommand.RenameFeed)
            menuController.send(MenuCommand.UnsubscribeFeed)
            waitForIdle()

            onNodeWithText("タイトルを変更").assertDoesNotExist()
            onNodeWithText("「Feed a」の購読を削除しますか？").assertDoesNotExist()
        } finally {
            vm.viewModelScope.cancel()
            fixture.close()
            driver.close()
        }
    }
}

/** A [NotificationMessages] fake returning canned, recognizable strings. */
private class FeedListDragTestNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: KeryxException): String = "syncFailed:${exception::class.simpleName}"
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
}

private class FeedListDragTestTokenStorage : TokenStorage {
    private var stored: OAuthTokens? = null
    override fun save(tokens: OAuthTokens) { stored = tokens }
    override fun load(): OAuthTokens? = stored
    override fun clear() { stored = null }
}

private fun feedListDragTestNotFoundHttpClient(): HttpClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
    followRedirects = false
    expectSuccess = false
    install(HttpTimeout)
}

/**
 * Bundles the [HomeViewModel] under test with the resources [newHomeViewModel] creates outside
 * its own [HomeViewModel.viewModelScope] — [SyncRepository]'s channel-consumer scope and the
 * MockEngine [HttpClient]s — so tests can release them in `finally`.
 */
private class HomeViewModelFixture(
    val vm: HomeViewModel,
    private val syncScope: CoroutineScope,
    private val httpClients: List<HttpClient>,
    val settingsRepository: SettingsRepository,
) {
    fun close() {
        syncScope.cancel()
        httpClients.forEach { it.close() }
    }
}

/**
 * Builds a real [HomeViewModel] over an already-seeded [db], wired the same way
 * `ArticleListPaneTest.newMinimalViewModel` is — every dispatcher is [Dispatchers.Unconfined] so DB
 * writes triggered by the drag gesture (via [FeedRepository.moveFeed] etc.) apply synchronously
 * within the test.
 */
private fun newHomeViewModel(
    driver: SqlDriver,
    db: KeryxDatabase,
    syncScheduler: SyncScheduler = SyncScheduler {},
    clock: Clock = Clock { 0L },
): HomeViewModelFixture {
    // A fresh, unique directory per call (not a fixed name shared across every test in this file):
    // LocalSettingsStore persists lastFilter/collapsedFolderIds/etc. to a JSON file there, and a
    // shared path would leak state between tests within the same run (e.g. a filter selected by
    // one test becoming the *restored* initial filter of the next), exactly like
    // `HomeViewModelTest`'s per-instance `Random.nextInt()`-suffixed directory.
    val dir = FileIO.join(AppDirs.tempDir(), "feed-list-drag-test-${Random.nextInt()}")
    val fetcherClient = feedListDragTestNotFoundHttpClient()
    val faviconClient = feedListDragTestNotFoundHttpClient()
    val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
    val feedRepository = FeedRepository(
        db, FeedFetcher(fetcherClient), FaviconResolver(faviconClient), articleRepository,
        ftsManagerIndexed(driver), syncScheduler, NotificationCenter(), FeedListDragTestNotificationMessages(),
        clock, Dispatchers.Unconfined,
    )
    val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
    val folderRepository = FolderRepository(db, feedRepository, syncScheduler, clock, Dispatchers.Unconfined)
    val settingsRepository = SettingsRepository(
        db, LocalSettingsStore(dirOverride = dir), syncScheduler, clock, writeDispatcher = Dispatchers.Unconfined,
    )
    val activityCenter = ActivityCenter()
    val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
    val syncRepository = SyncRepository(
        driver = driver,
        db = db,
        ftsManager = FtsManager(driver),
        cloudProvider = { null },
        clock = clock,
        scope = syncScope,
        activityCenter = activityCenter,
        notificationCenter = NotificationCenter(),
        notificationMessages = FeedListDragTestNotificationMessages(),
        localDbPath = "unused",
        tempDir = "unused",
    )
    val tokenStorage = FeedListDragTestTokenStorage()
    val authClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
    val authManager = DropboxAuthManager(authClient, clock = clock)
    val cloudSession = singleProviderCloudSession(
        client = authClient,
        tokenStorage = tokenStorage,
        authManager = authManager,
        clientId = "",
        clock = clock,
    )
    val vm = HomeViewModel(
        feedRepository, articleRepository, tagRepository, folderRepository, settingsRepository,
        syncRepository, cloudSession, activityCenter, clock, NewArticleNotifier(), FeedListDragTestNotificationMessages(),
        Dispatchers.Unconfined, Dispatchers.Unconfined,
    )
    return HomeViewModelFixture(vm, syncScope, listOf(fetcherClient, faviconClient, authClient), settingsRepository)
}
