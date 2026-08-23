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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
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
import org.koin.dsl.koinConfiguration
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
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

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

    /** The pixels at [x] spanning [edge]'s full `LIST_ROW_VERTICAL_MARGIN` band on both sides —
     * i.e. the entire gap a drag insertion marker could paint into around that boundary. */
    private fun androidx.compose.ui.test.ComposeUiTest.bandAround(edge: Int, x: Int): List<Color> {
        val marginPx = with(density) { LIST_ROW_VERTICAL_MARGIN.roundToPx() }
        val pixels = onRoot().captureToImage().toPixelMap()
        return (edge - marginPx until edge + marginPx).map { y -> pixels[x, y] }
    }

    /** The absolute y positions, within [before]/[after] (both taken via [bandAround] for the same
     * [edge]), where the color changed — i.e. where a guide newly appeared or disappeared. */
    private fun guideYs(edge: Int, before: List<Color>, after: List<Color>): List<Int> {
        val marginPx = before.size / 2
        return before.indices.filter { before[it] != after[it] }.map { edge - marginPx + it }
    }

    @Composable
    private fun FeedListDragTestHost(
        vm: HomeViewModel,
        dragOverlay: FeedDragOverlayState,
        isTouchPrimary: Boolean = false,
    ) {
        // Mirrors HomeScreen.kt's own request-id wiring for the keyboard rename/delete shortcuts,
        // so tests can drive them end to end (real F2/Delete key presses -> real FeedListPane
        // dialogs) exactly like a user would, rather than poking FeedListPane's private state.
        var renameSelectedRequestId by remember { mutableStateOf(0) }
        var deleteSelectedRequestId by remember { mutableStateOf(0) }
        Box(
            Modifier.testTag("root").size(320.dp, 700.dp).focusable().homeKeyboardShortcuts(
                textInputFocused = false,
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
                isTouchPrimary = isTouchPrimary,
            )
            FeedDragGhost(dragOverlay)
        }
    }

    private fun androidx.compose.ui.test.ComposeUiTest.setFeedListDragContent(
        vm: HomeViewModel,
        dragOverlay: FeedDragOverlayState = FeedDragOverlayState(),
        menuController: MenuController = testMenuController,
        isTouchPrimary: Boolean = false,
    ): FeedDragOverlayState {
        setContent {
            KoinApplication(
                configuration = koinConfiguration {
                    modules(
                        module {
                            single { menuController }
                        },
                    )
                },
            ) {
                FeedListDragTestHost(vm, dragOverlay, isTouchPrimary)
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
            closeHomeViewModelFixture(vm, fixture, driver)
        }
    }

    @Test
    fun touchPressAwayFromTheHandleNeverStartsAReorder() = runDesktopComposeUiTest {
        // With isTouchPrimary, feedListReorderDrag only starts from the row's trailing handle
        // band — everywhere else on the row must fall through untouched so the LazyColumn's own
        // scroll gesture can claim it instead (see feedListReorderDrag's KDoc). A press+move on
        // the row's own title text (comfortably left of the 44dp band) exercises exactly that.
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm, isTouchPrimary = true)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val bBounds = onNodeWithText("Feed b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)
            val target = localOf(bBounds.center, hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performTouchInput {
                down(start)
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(target)
                up()
            }
            waitForIdle()

            val order = db.feedsQueries.getByFolder(null).executeAsList().map { it.id }
            assertEquals(listOf("a", "b"), order)
        } finally {
            closeHomeViewModelFixture(vm, fixture, driver)
        }
    }

    @Test
    fun touchPressOnTheHandleReordersTheFeed() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        db.insertFeed("b", sortOrder = 1L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm, isTouchPrimary = true)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val bBounds = onNodeWithText("Feed b", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            // Comfortably inside the trailing 44dp handle band, regardless of exactly where the
            // title text itself sits.
            val handleX = hostBounds.right - with(density) { 10.dp.toPx() }
            val start = localOf(Offset(handleX, aBounds.center.y), hostBounds)
            // Bottom quarter of "b"'s row resolves to its AFTER boundary (see resolveRowHalf).
            val target = localOf(Offset(handleX, bBounds.top + bBounds.height * 0.75f), hostBounds)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performTouchInput {
                down(start)
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(target)
                up()
            }
            waitForIdle()

            val order = db.feedsQueries.getByFolder(null).executeAsList().map { it.id }
            assertEquals(listOf("b", "a"), order)
        } finally {
            closeHomeViewModelFixture(vm, fixture, driver)
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
            closeHomeViewModelFixture(vm, fixture, driver)
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
            closeHomeViewModelFixture(vm, fixture, driver)
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
            closeHomeViewModelFixture(vm, fixture, driver)
        }
    }

    /**
     * `FolderGroupHeader`'s feed-zone insertion marker is always paired (half thickness, with its
     * own `LIST_ROW_GUIDE_CLEARANCE`) whether the folder is collapsed or expanded — the matching
     * half comes from whatever row follows it (`precedingFeedZoneBoundary`, on a collapsed/empty
     * folder's next sibling) or from the real first feed row (once expanded) — so this header's own
     * drawing never changes and the spring-loaded auto-expand this test holds out for never moves
     * the guide. Captures the same pixel strip straddling the header's bottom edge before and after
     * the folder actually expands mid-drag, and asserts they are pixel-identical. Also asserts the
     * guide itself is visually distinct from the clearance right next to it, so a regression back to
     * painting the full thickness flush against the header (no clearance) would be caught even
     * though that too stays pixel-identical across the transition.
     */
    @Test
    fun holdingADragOverACollapsedFolderThenAutoExpandingDoesNotMoveTheInsertionGuide() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("f1", folderId = "d1", sortOrder = 0L)
        db.insertFeed("a", sortOrder = 1L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        vm.toggleFolderCollapsed("d1")
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val folderBounds = onNodeWithTag(folderRowTestTag("d1"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)
            val target = localOf(folderBounds.center, hostBounds)
            val stripX = folderBounds.center.x.toInt()
            val edge = folderBounds.bottom.toInt()
            val halfGuidePx = with(density) { (LIST_ROW_GUIDE_THICKNESS / 2f).roundToPx() }

            fun edgeStrip() = onRoot().captureToImage().toPixelMap().let { pixels ->
                (edge - 4 until edge + 4).map { y -> pixels[stripX, y] }
            }

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(target)
            }
            waitForIdle()
            val beforeExpand = edgeStrip()

            val pixelsBeforeExpand = onRoot().captureToImage().toPixelMap()
            assertNotEquals(
                pixelsBeforeExpand[stripX, edge - 1],
                pixelsBeforeExpand[stripX, edge - halfGuidePx - 1],
                "the guide must sit LIST_ROW_GUIDE_CLEARANCE clear of the folder's own highlight, not flush against it",
            )

            waitUntil(timeoutMillis = 2000) { "d1" !in vm.collapsedFolderIds.value }
            waitForIdle()
            val afterExpand = edgeStrip()

            assertEquals(beforeExpand, afterExpand, "the insertion guide must not move when the collapsed folder auto-expands mid-drag")
        } finally {
            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput { release() }
            closeHomeViewModelFixture(vm, fixture, driver)
        }
    }

    /**
     * Regression test for a bug in the first implementation of `precedingFeedZoneBoundary`: it was
     * carried across the folder loop in `FeedListPane.kt` as a plain `var`, captured by each
     * `item { ... }` content lambda. Those lambdas don't run until LazyColumn actually composes that
     * row — *after* the whole loop has finished — so every row saw the *same*, final value of the
     * `var` rather than its own predecessor's. With two folders (the first collapsed, the second
     * expanded), that final value was `null` (assigned while processing the second, expanded
     * folder), so nothing ever painted the matching half of the first folder's own guide, and it
     * stayed 1dp thick instead of 2dp.
     */
    @Test
    fun aCollapsedFolderFollowedByAnExpandedOneStillGetsAFullThicknessGuide() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("f1", folderId = "d1", sortOrder = 0L)
        db.insertFolder("d2", "Folder Two", sortOrder = 1L)
        db.insertFeed("f2", folderId = "d2", sortOrder = 0L)
        db.insertFeed("a", sortOrder = 2L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        vm.toggleFolderCollapsed("d1")
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val d1Bounds = onNodeWithTag(folderRowTestTag("d1"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)
            val target = localOf(d1Bounds.center, hostBounds)
            val stripX = d1Bounds.center.x.toInt()
            val edge = d1Bounds.bottom.toInt()
            val halfGuidePx = with(density) { (LIST_ROW_GUIDE_THICKNESS / 2f).roundToPx() }

            val baseline = bandAround(edge, stripX)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(target)
            }
            waitForIdle()
            val duringDrag = bandAround(edge, stripX)

            assertEquals(
                (edge - halfGuidePx until edge + halfGuidePx).toList(),
                guideYs(edge, baseline, duringDrag),
                "the guide below a collapsed folder must be the full LIST_ROW_GUIDE_THICKNESS, centred on the boundary",
            )
        } finally {
            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput { release() }
            closeHomeViewModelFixture(vm, fixture, driver)
        }
    }

    /**
     * Sibling regression test to [aCollapsedFolderFollowedByAnExpandedOneStillGetsAFullThicknessGuide]
     * for the other symptom of the same `var`-capture bug: when the *last* folder is the one that's
     * collapsed, the final value every row's `item { ... }` lambda saw was that folder's own
     * feed-zone boundary — not `null`. Since a feed drag hovering that folder makes that exact
     * boundary the active one, *every* folder header (and the sticky "Folders" label above the very
     * first one) wrongly matched it too, each painting a spurious top-edge guide of its own.
     */
    @Test
    fun draggingOverACollapsedFolderPaintsNoGuideOnOtherRows() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("f1", folderId = "d1", sortOrder = 0L)
        db.insertFolder("d2", "Folder Two", sortOrder = 1L)
        db.insertFeed("f2", folderId = "d2", sortOrder = 0L)
        db.insertFeed("a", sortOrder = 2L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        vm.toggleFolderCollapsed("d2")
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val aBounds = onNodeWithText("Feed a", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val d1Bounds = onNodeWithTag(folderRowTestTag("d1"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val d2Bounds = onNodeWithTag(folderRowTestTag("d2"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(aBounds.center, hostBounds)
            val target = localOf(d2Bounds.center, hostBounds)
            val d1StripX = d1Bounds.center.x.toInt()
            val d1Top = d1Bounds.top.toInt()
            val d2StripX = d2Bounds.center.x.toInt()
            val d2Top = d2Bounds.top.toInt()
            val d2Bottom = d2Bounds.bottom.toInt()
            val halfGuidePx = with(density) { (LIST_ROW_GUIDE_THICKNESS / 2f).roundToPx() }

            val baselineD1Top = bandAround(d1Top, d1StripX)
            val baselineD2Top = bandAround(d2Top, d2StripX)
            val baselineD2Bottom = bandAround(d2Bottom, d2StripX)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(target)
            }
            waitForIdle()
            onNodeWithTag(FEED_DRAG_GHOST_TEST_TAG, useUnmergedTree = true).assertExists()

            assertTrue(
                guideYs(d1Top, baselineD1Top, bandAround(d1Top, d1StripX)).isEmpty(),
                "the first folder's own top edge (right below the sticky \"Folders\" label) must show no guide",
            )
            assertTrue(
                guideYs(d2Top, baselineD2Top, bandAround(d2Top, d2StripX)).isEmpty(),
                "the collapsed folder being hovered must not also paint a spurious guide at its own top edge",
            )
            assertEquals(
                (d2Bottom - halfGuidePx until d2Bottom + halfGuidePx).toList(),
                guideYs(d2Bottom, baselineD2Bottom, bandAround(d2Bottom, d2StripX)),
                "sanity check: the drag must actually be painting the real guide below the collapsed folder",
            )
        } finally {
            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput { release() }
            closeHomeViewModelFixture(vm, fixture, driver)
        }
    }

    /**
     * When the "no folder" section is empty, its own feed-zone marker is unpaired (no feed row
     * exists below it, and — being always the last feed/folder row — none ever will), so
     * `listRowSurface`'s `extraBottomMargin` reserves the other side's half on `NoFolderHeader`'s
     * own margin instead, keeping the guide the same 1dp-clearance line a non-empty group's paired
     * marker has (see the comment on `NoFolderHeader`'s `insertionMarkers` call).
     *
     * Checks two independent things: that the guide still actually appears (a pixel spot-check,
     * same style as the collapsed-folder tests above), and — since the extra margin itself is only
     * 1px wide here, too thin to reliably tell apart from screenshot anti-aliasing at the adjacent
     * highlight/border edges — that `NoFolderHeader`'s own measured height grows by exactly
     * `LIST_ROW_GUIDE_THICKNESS / 2` once its group is empty, which is a precise, deterministic
     * semantics-tree measurement rather than a pixel comparison. The drop this test itself performs
     * conveniently supplies the "non-empty" comparison point for free: releasing onto the "no
     * folder" section moves `f1` there, so measuring again right after `release()` needs no second
     * fixture.
     */
    @Test
    fun draggingOntoAnEmptyNoFolderSectionKeepsClearanceFromItsHighlight() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFolder("d1", "Folder One", sortOrder = 0L)
        db.insertFeed("f1", folderId = "d1", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        try {
            setFeedListDragContent(vm)
            waitForIdle()

            val hostBounds = onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val f1Bounds = onNodeWithText("Feed f1", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val noFolderBoundsWhenEmpty = onNodeWithTag(NO_FOLDER_HEADER_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val start = localOf(f1Bounds.center, hostBounds)
            val target = localOf(noFolderBoundsWhenEmpty.center, hostBounds)
            val stripX = noFolderBoundsWhenEmpty.center.x.toInt()
            val edge = noFolderBoundsWhenEmpty.bottom.toInt()

            fun pixelAt(y: Int) = onRoot().captureToImage().toPixelMap()[stripX, y]
            val baselineAtGuide = pixelAt(edge - 1)

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput {
                moveTo(start)
                press()
                moveTo(start + Offset(0f, dragThresholdCrossPx))
                moveTo(target)
            }
            waitForIdle()
            assertNotEquals(baselineAtGuide, pixelAt(edge - 1), "the guide must actually appear once hovering starts")

            onNodeWithTag(FEED_LIST_DRAG_HOST_TEST_TAG, useUnmergedTree = true).performMouseInput { release() }
            waitForIdle()
            assertEquals(null, db.feedsQueries.getById("f1").executeAsOne().folder_id, "sanity check: f1 must have actually moved into the \"no folder\" group")

            val noFolderBoundsWhenNonEmpty = onNodeWithTag(NO_FOLDER_HEADER_TEST_TAG, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
            val extraMarginPx = with(density) { (LIST_ROW_GUIDE_THICKNESS / 2f).roundToPx() }
            assertEquals(
                noFolderBoundsWhenNonEmpty.height + extraMarginPx,
                noFolderBoundsWhenEmpty.height,
                "an empty \"no folder\" section must reserve exactly LIST_ROW_GUIDE_THICKNESS / 2 more height " +
                    "than a non-empty one, to hold its unpaired marker's missing clearance",
            )
        } finally {
            // The drag was already released above (needed mid-test to compare against the
            // non-empty state) — the test input dispatcher rejects a second release with nothing
            // pressed, so cleanup here is just the fixture/database, unlike every other test in
            // this file.
            closeHomeViewModelFixture(vm, fixture, driver)
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
            closeHomeViewModelFixture(vm, fixture, driver)
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
            closeHomeViewModelFixture(vm, fixture, driver)
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
            closeHomeViewModelFixture(vm, fixture, driver)
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
            closeHomeViewModelFixture(vm, fixture, driver)
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
            closeHomeViewModelFixture(vm, fixture, driver)
        }
    }

    // Rename/delete opening the correct dialog for the current selection is covered by
    // resolveFeedListSelectionTarget's unit tests in HomeCommonTest.kt (commonTest), not here.
    // A prior version of this file drove F2/Delete end-to-end through the real FeedListPane and
    // asserted on the rename/unsubscribe dialog's rendered text — but those dialogs are genuine,
    // separate native DialogWindows (DesktopModalWindow, java.awt.Dialog.ModalityType.DOCUMENT_MODAL),
    // and asserting on one from inside a runDesktopComposeUiTest proved unreliable in CI: it timed
    // out deterministically on both the Ubuntu (Xvfb) and Windows runners while passing locally on
    // macOS, pointing at platform-specific native modal-dialog/focus behavior under a non-interactive
    // CI session rather than a fixable timing race. See resolveFeedListSelectionTarget in
    // HomeCommon.kt for the actual selection-resolution logic this used to protect end-to-end.

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
            closeHomeViewModelFixture(vm, fixture, driver)
        }
    }

    @Test
    fun sendingRenameOrUnsubscribeFeedCommandWithNoFeedSelectedStartsNothing() = runDesktopComposeUiTest {
        val (driver, db) = inMemoryDb()
        db.insertFeed("a", sortOrder = 0L)
        val fixture = newHomeViewModel(driver, db)
        val vm = fixture.vm
        val menuController = testMenuController
        try {
            setFeedListDragContent(vm, menuController = menuController)
            waitForIdle()
            vm.selectFilter(ArticleFilter.All)
            waitForIdle()

            menuController.send(MenuCommand.RenameFeed)
            menuController.send(MenuCommand.UnsubscribeFeed)
            waitForIdle()

            onNodeWithTag(INLINE_RENAME_FIELD_TEST_TAG, useUnmergedTree = true).assertDoesNotExist()
            onNodeWithText("「Feed a」の購読を削除しますか？").assertDoesNotExist()
        } finally {
            closeHomeViewModelFixture(vm, fixture, driver)
        }
    }
}

/**
 * One [MenuController] for the whole test JVM, shared by every feed-list UI test.
 *
 * koin-compose's `KoinApplication` does not isolate per composition across `runDesktopComposeUiTest`
 * runs in the same JVM: the first Koin application created keeps serving `koinInject<MenuController>()`
 * to every later test's `FeedListPane`. A per-test instance therefore leaves the pane collecting from
 * a different object than the test sends on — which silently makes menu-command assertions vacuous
 * rather than failing loudly. Sharing one instance makes that identity irrelevant. Commands carry no
 * replay and every collector dies with its composition, so nothing leaks between tests.
 */
internal val testMenuController = MenuController()

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
internal class HomeViewModelFixture(
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
internal fun newHomeViewModel(
    driver: SqlDriver,
    db: KeryxDatabase,
    syncScheduler: SyncScheduler = SyncScheduler {},
    clock: Clock = Clock { 0L },
    activityCenter: ActivityCenter = ActivityCenter(),
    tokenStorage: TokenStorage = FeedListDragTestTokenStorage(),
    appKey: String = "",
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
    val authClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
    val authManager = DropboxAuthManager(authClient, clock = clock)
    val cloudSession = singleProviderCloudSession(
        client = authClient,
        tokenStorage = tokenStorage,
        authManager = authManager,
        clientId = appKey,
        clock = clock,
    )
    val vm = HomeViewModel(
        feedRepository, articleRepository, tagRepository, folderRepository, settingsRepository,
        syncRepository, cloudSession, activityCenter, clock, NewArticleNotifier(), FeedListDragTestNotificationMessages(),
        Dispatchers.Unconfined, Dispatchers.Unconfined,
    )
    return HomeViewModelFixture(vm, syncScope, listOf(fetcherClient, faviconClient, authClient), settingsRepository)
}

/**
 * Tears down a [HomeViewModelFixture] for reuse across every drag/rename/hit-area test's `finally`
 * block. [HomeViewModel] observes SQLDelight query flows via `stateIn(viewModelScope, ...)`, and
 * `viewModelScope`'s dispatcher resolves to the real AWT EventDispatchThread on desktop — so a
 * query-change notification from a gesture performed just before teardown can already be queued as
 * a pending `InvocationEvent` when [vm]'s scope is cancelled. Cancellation is cooperative and does
 * not retract that queued event, so closing [driver] immediately after `cancel()` races it: the
 * queued event later resumes on the closed connection and throws `stmt pointer is closed`.
 * [ComposeUiTest.waitForIdle] drains the EDT queue first, so any such pending resumption runs
 * against the still-open [driver] instead.
 */
@OptIn(ExperimentalTestApi::class)
internal fun ComposeUiTest.closeHomeViewModelFixture(vm: HomeViewModel, fixture: HomeViewModelFixture, driver: SqlDriver) {
    vm.viewModelScope.cancel()
    try {
        fixture.close()
        waitForIdle()
    } finally {
        driver.close()
    }
}
