package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pure-logic cases for [resolveFeedListDropHighlight]/[resolveFeedListDropAction] — the drop rules
 * every UI's own drag-and-drop shares (Compose's `FeedListDragController.kt`, the Apple app's
 * `FeedListView.swift`). Each case names the Compose behavior it pins so a future change to either
 * function stays intentional.
 */
class FeedListDragTest {

    private val folders = listOf(folder("d1"), folder("d2"))
    private val feeds = listOf(
        feed("f1", folderId = "d1"),
        feed("f2", folderId = "d1"),
        feed("f3", folderId = null),
    )
    private val index = buildFeedListDropIndex(feeds, folders)

    // --- resolveFeedListDropHighlight: dragging a feed ---

    @Test
    fun draggingAFeedOntoAFolderHeaderHighlightsItsFirstFeed() {
        val (boundary, tag) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Feed("f3"),
            FeedListDropTarget.FolderHeader("d1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(DropBoundary.BeforeFeed("f1"), boundary)
        assertNull(tag)
    }

    @Test
    fun draggingAFeedOntoTheNoFolderHeaderHighlightsItsFirstFeedOrAppendsWhenEmpty() {
        val (boundary, _) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Feed("f1"),
            FeedListDropTarget.NoFolderHeader,
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(DropBoundary.BeforeFeed("f3"), boundary)
    }

    @Test
    fun draggingAFeedOverTheTopHalfOfAFeedRowInsertsBeforeIt() {
        val (boundary, _) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Feed("f3"),
            FeedListDropTarget.FeedRow("f2"),
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(DropBoundary.BeforeFeed("f2"), boundary)
    }

    @Test
    fun draggingAFeedOverTheBottomHalfOfTheLastFeedInAGroupAppendsToThatGroup() {
        val (boundary, _) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Feed("f3"),
            FeedListDropTarget.FeedRow("f2"),
            FeedListRowHalf.BOTTOM,
            index,
        )
        assertEquals(DropBoundary.AppendFeeds("d1"), boundary)
    }

    @Test
    fun draggingAFeedOverATagHeaderHighlightsThatTagForAttachmentInsteadOfABoundary() {
        val (boundary, tag) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Feed("f1"),
            FeedListDropTarget.TagHeader("t1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertNull(boundary)
        assertEquals("t1", tag)
    }

    @Test
    fun draggingAFeedOverAnythingElseHighlightsNothing() {
        val (boundary, tag) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Feed("f1"),
            FeedListDropTarget.Other,
            FeedListRowHalf.TOP,
            index,
        )
        assertNull(boundary)
        assertNull(tag)
    }

    // --- resolveFeedListDropHighlight: dragging a folder ---

    @Test
    fun draggingAFolderOverItsOwnHeaderHighlightsNothing() {
        val (boundary, _) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Folder("d1"),
            FeedListDropTarget.FolderHeader("d1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertNull(boundary)
    }

    @Test
    fun draggingAFolderOverAnotherFoldersTopOrBottomHalfInsertsBeforeOrAfterIt() {
        val (before, _) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Folder("d2"),
            FeedListDropTarget.FolderHeader("d1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(DropBoundary.BeforeFolder("d1"), before)

        val (after, _) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Folder("d2"),
            FeedListDropTarget.FolderHeader("d1"),
            FeedListRowHalf.BOTTOM,
            index,
        )
        // d1 is the first folder, so "below d1" is "before d2" (the next folder in order).
        assertEquals(DropBoundary.BeforeFolder("d2"), after)
    }

    @Test
    fun draggingAFolderOverAFeedRowHighlightsThatFeedsOwnFolderPosition() {
        val (boundary, _) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Folder("d2"),
            FeedListDropTarget.FeedRow("f1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(index.belowBoundaryForFolder("d1"), boundary)
    }

    @Test
    fun draggingAFolderOverAFeedAlreadyInItHighlightsNothing() {
        val (boundary, _) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Folder("d1"),
            FeedListDropTarget.FeedRow("f1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertNull(boundary)
    }

    @Test
    fun draggingAFolderOverAnUnfolderedFeedHighlightsNothing() {
        val (boundary, _) = resolveFeedListDropHighlight(
            FeedListDraggedItem.Folder("d2"),
            FeedListDropTarget.FeedRow("f3"),
            FeedListRowHalf.TOP,
            index,
        )
        assertNull(boundary)
    }

    // --- resolveFeedListDropAction: dropping a feed ---

    @Test
    fun droppingAFeedOnAFolderHeaderMovesItToTheFrontOfThatFolder() {
        val action = resolveFeedListDropAction(
            FeedListDraggedItem.Feed("f3"),
            FeedListDropTarget.FolderHeader("d1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(FeedListDropAction.MoveFeed("f3", "d1", "f1"), action)
    }

    @Test
    fun droppingAFeedOnTheNoFolderHeaderMovesItOutOfAnyFolder() {
        val action = resolveFeedListDropAction(
            FeedListDraggedItem.Feed("f1"),
            FeedListDropTarget.NoFolderHeader,
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(FeedListDropAction.MoveFeed("f1", null, "f3"), action)
    }

    @Test
    fun droppingAFeedOnTheTopHalfOfAFeedRowInsertsBeforeIt() {
        val action = resolveFeedListDropAction(
            FeedListDraggedItem.Feed("f3"),
            FeedListDropTarget.FeedRow("f2"),
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(FeedListDropAction.MoveFeed("f3", "d1", "f2"), action)
    }

    @Test
    fun droppingAFeedOnTheBottomHalfOfTheLastFeedInAGroupAppendsToThatGroup() {
        val action = resolveFeedListDropAction(
            FeedListDraggedItem.Feed("f3"),
            FeedListDropTarget.FeedRow("f2"),
            FeedListRowHalf.BOTTOM,
            index,
        )
        assertEquals(FeedListDropAction.MoveFeed("f3", "d1", null), action)
    }

    @Test
    fun droppingAFeedOnATagHeaderAttachesThatTag() {
        val action = resolveFeedListDropAction(
            FeedListDraggedItem.Feed("f1"),
            FeedListDropTarget.TagHeader("t1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(FeedListDropAction.AttachTag("f1", "t1"), action)
    }

    @Test
    fun droppingAFeedOnAnythingElseDoesNothing() {
        val action = resolveFeedListDropAction(
            FeedListDraggedItem.Feed("f1"),
            FeedListDropTarget.Other,
            FeedListRowHalf.TOP,
            index,
        )
        assertNull(action)
    }

    // --- resolveFeedListDropAction: dropping a folder ---

    @Test
    fun droppingAFolderOnItsOwnHeaderDoesNothing() {
        val action = resolveFeedListDropAction(
            FeedListDraggedItem.Folder("d1"),
            FeedListDropTarget.FolderHeader("d1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertNull(action)
    }

    @Test
    fun droppingAFolderOnAnotherFoldersTopOrBottomHalfReordersBeforeOrAfterIt() {
        val before = resolveFeedListDropAction(
            FeedListDraggedItem.Folder("d2"),
            FeedListDropTarget.FolderHeader("d1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(FeedListDropAction.ReorderFolder("d2", "d1"), before)

        val after = resolveFeedListDropAction(
            FeedListDraggedItem.Folder("d2"),
            FeedListDropTarget.FolderHeader("d1"),
            FeedListRowHalf.BOTTOM,
            index,
        )
        // The bottom half of d1 is the same boundary as "before d2" (the next folder in order) —
        // d2 dragged there reorders to before itself, a no-op position, which is exactly what
        // Compose's own `end()` would also resolve here (it applies no special-case guard for this).
        assertEquals(FeedListDropAction.ReorderFolder("d2", "d2"), after)
    }

    @Test
    fun droppingAFolderOnAFeedRowReordersAfterThatFeedsOwnFolder() {
        val action = resolveFeedListDropAction(
            FeedListDraggedItem.Folder("d2"),
            FeedListDropTarget.FeedRow("f1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertEquals(FeedListDropAction.ReorderFolder("d2", index.nextFolderId["d1"]), action)
    }

    @Test
    fun droppingAFolderOnAFeedAlreadyInItDoesNothing() {
        val action = resolveFeedListDropAction(
            FeedListDraggedItem.Folder("d1"),
            FeedListDropTarget.FeedRow("f1"),
            FeedListRowHalf.TOP,
            index,
        )
        assertNull(action)
    }

    @Test
    fun droppingAFolderOnAnUnfolderedFeedDoesNothing() {
        val action = resolveFeedListDropAction(
            FeedListDraggedItem.Folder("d2"),
            FeedListDropTarget.FeedRow("f3"),
            FeedListRowHalf.TOP,
            index,
        )
        assertNull(action)
    }
}

private fun folder(id: String): Folders = Folders(
    id = id,
    name = "Folder $id",
    sort_order = 0L,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
)

private fun feed(id: String, folderId: String?): Feeds = Feeds(
    id = id,
    url = "https://example.com/$id",
    site_url = null,
    title = "Feed $id",
    description = null,
    favicon_url = null,
    etag = null,
    last_modified = null,
    error_count = 0L,
    last_error = null,
    custom_title = null,
    folder_id = folderId,
    deleted_at = null,
    updated_at = 0L,
    created_at = 0L,
    sort_order = 0L,
    folder_updated_at = null,
    sort_order_updated_at = null,
    custom_title_updated_at = null,
    deleted_updated_at = null,
)
