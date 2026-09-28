package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders

/**
 * A single drop-and-reorder insertion point, shared (lifted) across all rows/headers in the pane
 * so that hovering the bottom half of one item and the top half of the next item — which are the
 * same logical boundary — light up exactly one indicator rather than two independent ones.
 */
sealed interface DropBoundary {
    data class BeforeFeed(val feedId: String) : DropBoundary
    data class AppendFeeds(val folderId: String?) : DropBoundary
    data class BeforeFolder(val folderId: String) : DropBoundary
    data object AppendFolders : DropBoundary
}

/**
 * Precomputed lookup tables for resolving feed/folder drag-and-drop insertion points by id, built
 * once per feeds/folders change (see [buildFeedListDropIndex]) rather than re-deriving grouping ad
 * hoc for every drag event.
 */
data class FeedListDropIndex(
    val folderIdOfFeed: Map<String, String?>,
    val nextFeedInGroup: Map<String, String?>,
    val firstFeedIdOfGroup: Map<String?, String?>,
    val nextFolderId: Map<String, String?>,
) {
    /** Where a feed dropped into [folderId] (or the unassigned group when `null`) would land. */
    fun feedZoneBoundaryFor(folderId: String?): DropBoundary =
        firstFeedIdOfGroup[folderId]?.let(DropBoundary::BeforeFeed) ?: DropBoundary.AppendFeeds(folderId)

    /** Where a feed dropped just below [feedId], within its own group, would land. */
    fun belowBoundaryForFeed(feedId: String): DropBoundary =
        nextFeedInGroup[feedId]?.let(DropBoundary::BeforeFeed) ?: DropBoundary.AppendFeeds(folderIdOfFeed[feedId])

    /** Where a folder dropped just below [folderId] would land. */
    fun belowBoundaryForFolder(folderId: String): DropBoundary =
        nextFolderId[folderId]?.let(DropBoundary::BeforeFolder) ?: DropBoundary.AppendFolders
}

/** Builds a [FeedListDropIndex] from [feeds]/[folders], reusing [groupFeedsByFolder]'s grouping. */
fun buildFeedListDropIndex(feeds: List<Feeds>, folders: List<Folders>): FeedListDropIndex {
    val folderIdOfFeed = mutableMapOf<String, String?>()
    val nextFeedInGroup = mutableMapOf<String, String?>()
    val firstFeedIdOfGroup = mutableMapOf<String?, String?>()
    for ((folder, feedsInGroup) in groupFeedsByFolder(feeds, folders)) {
        val groupKey = folder?.id
        firstFeedIdOfGroup[groupKey] = feedsInGroup.firstOrNull()?.id
        feedsInGroup.forEachIndexed { index, feed ->
            folderIdOfFeed[feed.id] = groupKey
            nextFeedInGroup[feed.id] = feedsInGroup.getOrNull(index + 1)?.id
        }
    }
    val nextFolderId = folders.indices.associate { i -> folders[i].id to folders.getOrNull(i + 1)?.id }
    return FeedListDropIndex(folderIdOfFeed, nextFeedInGroup, firstFeedIdOfGroup, nextFolderId)
}

/** What a feed-list drag is currently carrying — UI-framework-free mirror of Compose's own
 * `DraggedItem` (`FeedListDragController.kt`), which also carries a display title Compose's drag
 * ghost needs and this doesn't. */
sealed interface FeedListDraggedItem {
    data class Feed(val feedId: String) : FeedListDraggedItem
    data class Folder(val folderId: String) : FeedListDraggedItem
}

/** Which half of a row's own band the pointer/drop point is over — top inserts before the row,
 * bottom inserts after it. */
enum class FeedListRowHalf { TOP, BOTTOM }

/**
 * What a feed-list row or header currently under the pointer represents, independent of how each
 * UI hit-tests its own rows (Compose's `LazyColumn` item keys parsed by `parseFeedListRowKey`,
 * SwiftUI's own per-row `DropDelegate`). A row rendered as a feed's *copy* under an expanded tag is
 * deliberately not a variant here — see [TagHeader]'s own doc — matching
 * `FeedListRowKey.Tag`/`Other`'s existing "not a drop target" treatment.
 */
sealed interface FeedListDropTarget {
    data class FolderHeader(val folderId: String) : FeedListDropTarget
    data object NoFolderHeader : FeedListDropTarget
    data class FeedRow(val feedId: String) : FeedListDropTarget
    /** A tag's own header row — a feed dropped here attaches the tag; [FeedListRowHalf] is
     * ignored, and dragging a folder here never resolves to anything. */
    data class TagHeader(val tagId: String) : FeedListDropTarget
    /** Anything else: a feed's copy nested under a tag, a section divider, blank space. Never a
     * valid drop target, matching Compose's own `FeedListRowKey.Other`. */
    data object Other : FeedListDropTarget
}

/** What a completed drop applies — the caller pattern-matches this onto its own
 * `HomeViewModel.moveFeed`/`setFeedTag`/`reorderFolders` call. */
sealed interface FeedListDropAction {
    data class MoveFeed(val feedId: String, val folderId: String?, val targetFeedId: String?) : FeedListDropAction
    data class AttachTag(val feedId: String, val tagId: String) : FeedListDropAction
    data class ReorderFolder(val draggedFolderId: String, val targetFolderId: String?) : FeedListDropAction
}

/**
 * Resolves the [DropBoundary] to highlight (or the tag id to highlight for attachment) while
 * [item] hovers [target] — `(null, null)` for an invalid target, where nothing would happen on
 * release. Pure port of Compose's own `FeedListDragController.updateHover`'s `when` block
 * (`FeedListDragController.kt`), shared so the Apple app's own `DropDelegate` resolves the exact
 * same highlight without re-deriving the rules.
 *
 * @param half Which half of [target]'s own band the pointer is over — irrelevant for
 *   [FeedListDropTarget.NoFolderHeader]/[FeedListDropTarget.TagHeader], which always resolve the
 *   same way regardless.
 */
fun resolveFeedListDropHighlight(
    item: FeedListDraggedItem,
    target: FeedListDropTarget,
    half: FeedListRowHalf,
    index: FeedListDropIndex,
): Pair<DropBoundary?, String?> = when (item) {
    is FeedListDraggedItem.Feed -> when (target) {
        is FeedListDropTarget.FolderHeader -> index.feedZoneBoundaryFor(target.folderId) to null
        FeedListDropTarget.NoFolderHeader -> index.feedZoneBoundaryFor(null) to null
        is FeedListDropTarget.FeedRow -> {
            val boundary = if (half == FeedListRowHalf.TOP) {
                DropBoundary.BeforeFeed(target.feedId)
            } else {
                index.belowBoundaryForFeed(target.feedId)
            }
            boundary to null
        }
        is FeedListDropTarget.TagHeader -> null to target.tagId
        FeedListDropTarget.Other -> null to null
    }
    is FeedListDraggedItem.Folder -> when (target) {
        is FeedListDropTarget.FolderHeader -> when {
            target.folderId == item.folderId -> null to null
            half == FeedListRowHalf.TOP -> DropBoundary.BeforeFolder(target.folderId) to null
            else -> index.belowBoundaryForFolder(target.folderId) to null
        }
        is FeedListDropTarget.FeedRow -> {
            val boundary = index.folderIdOfFeed[target.feedId]
                ?.takeIf { it != item.folderId }
                ?.let(index::belowBoundaryForFolder)
            boundary to null
        }
        else -> null to null
    }
}

/**
 * Resolves the mutation dropping [item] onto [target] applies, or `null` if the drop is invalid
 * there (dropped outside any row, a folder dropped onto itself, or onto a feed with no live
 * folder). Pure port of Compose's own `FeedListDragController.end`'s `when` block
 * (`FeedListDragController.kt`).
 */
fun resolveFeedListDropAction(
    item: FeedListDraggedItem,
    target: FeedListDropTarget,
    half: FeedListRowHalf,
    index: FeedListDropIndex,
): FeedListDropAction? = when (item) {
    is FeedListDraggedItem.Feed -> when (target) {
        is FeedListDropTarget.FolderHeader ->
            FeedListDropAction.MoveFeed(item.feedId, target.folderId, index.firstFeedIdOfGroup[target.folderId])
        FeedListDropTarget.NoFolderHeader ->
            FeedListDropAction.MoveFeed(item.feedId, null, index.firstFeedIdOfGroup[null])
        is FeedListDropTarget.FeedRow -> {
            val insertBeforeId = if (half == FeedListRowHalf.TOP) {
                target.feedId
            } else {
                index.nextFeedInGroup[target.feedId]
            }
            FeedListDropAction.MoveFeed(item.feedId, index.folderIdOfFeed[target.feedId], insertBeforeId)
        }
        is FeedListDropTarget.TagHeader -> FeedListDropAction.AttachTag(item.feedId, target.tagId)
        FeedListDropTarget.Other -> null
    }
    is FeedListDraggedItem.Folder -> when (target) {
        is FeedListDropTarget.FolderHeader -> {
            if (target.folderId == item.folderId) {
                null
            } else {
                val insertBeforeId = if (half == FeedListRowHalf.TOP) {
                    target.folderId
                } else {
                    index.nextFolderId[target.folderId]
                }
                FeedListDropAction.ReorderFolder(item.folderId, insertBeforeId)
            }
        }
        is FeedListDropTarget.FeedRow -> {
            val ownerFolderId = index.folderIdOfFeed[target.feedId]
            if (ownerFolderId == null || ownerFolderId == item.folderId) {
                null
            } else {
                FeedListDropAction.ReorderFolder(item.folderId, index.nextFolderId[ownerFolderId])
            }
        }
        else -> null
    }
}
