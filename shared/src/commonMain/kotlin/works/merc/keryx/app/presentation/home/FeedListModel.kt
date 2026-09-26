package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.displayTitle

/**
 * One specific *rendered row instance* of the feed list, as opposed to [ArticleFilter], which only
 * says what the article list shows. A feed renders once under its folder group and again under each
 * expanded tag it is attached to, so `ArticleFilter.Feed(id)` alone cannot say which of those rows
 * the user actually clicked or navigated to. This UI-only type does — driving keyboard-navigation
 * order, exact scroll-into-view targeting, and which duplicate paints the [RowSelectionTone.PRIMARY]
 * highlight.
 */
sealed interface FeedListRowSelection {
    /** The filter this row selects when activated. */
    val filter: ArticleFilter

    data object All : FeedListRowSelection {
        override val filter: ArticleFilter get() = ArticleFilter.All
    }

    data object Starred : FeedListRowSelection {
        override val filter: ArticleFilter get() = ArticleFilter.Starred
    }

    /** A feed's row under its folder group (or the unassigned group) — its canonical row. */
    data class FeedInFolderGroup(val feedId: String) : FeedListRowSelection {
        override val filter: ArticleFilter get() = ArticleFilter.Feed(feedId)
    }

    /** A feed's row nested under an expanded tag. */
    data class FeedInTag(val feedId: String, val tagId: String) : FeedListRowSelection {
        override val filter: ArticleFilter get() = ArticleFilter.Feed(feedId)
    }

    data class Folder(val folderId: String) : FeedListRowSelection {
        override val filter: ArticleFilter get() = ArticleFilter.Folder(folderId)
    }

    data class Tag(val tagId: String) : FeedListRowSelection {
        override val filter: ArticleFilter get() = ArticleFilter.Tag(tagId)
    }

    companion object {
        /**
         * Canonical instance for a bare [ArticleFilter] change (notification-center "show feed
         * detail", any caller with no specific row in mind) — always the folder-group instance for
         * a feed.
         */
        fun canonicalFor(filter: ArticleFilter): FeedListRowSelection = when (filter) {
            ArticleFilter.All -> All
            ArticleFilter.Starred -> Starred
            is ArticleFilter.Feed -> FeedInFolderGroup(filter.feedId)
            is ArticleFilter.Folder -> Folder(filter.folderId)
            is ArticleFilter.Tag -> Tag(filter.tagId)
        }
    }
}

/**
 * Groups [feeds] by [folders], preserving [feeds]' order within each group.
 * Returns one `(folder, feedsInFolder)` pair per element of [folders] (in
 * [folders]' order, even if empty), followed by a final `(null, unassignedFeeds)`
 * pair for feeds whose `folder_id` is null or doesn't match any live folder id.
 */
fun groupFeedsByFolder(feeds: List<Feeds>, folders: List<Folders>): List<Pair<Folders?, List<Feeds>>> {
    val folderIds = folders.map { it.id }.toSet()
    val byFolderId = feeds.filter { it.folder_id != null && it.folder_id in folderIds }.groupBy { it.folder_id }
    val unassigned = feeds.filter { it.folder_id == null || it.folder_id !in folderIds }
    return folders.map { folder -> folder to (byFolderId[folder.id] ?: emptyList()) } +
        (null as Folders? to unassigned)
}

/**
 * Selects feeds associated with the specified tag while preserving their input order.
 *
 * @param feeds The feeds to filter.
 * @param feedTagMap A mapping from feed IDs to their associated tag IDs.
 * @param tagId The tag ID to match.
 * @return The feeds associated with [tagId].
 */
fun feedsForTag(feeds: List<Feeds>, feedTagMap: Map<String, Set<String>>, tagId: String): List<Feeds> =
    feeds.filter { tagId in (feedTagMap[it.id] ?: emptySet()) }

/**
 * The display title for the article list pane's current [filter] — shown in its top bar only when
 * the pane is rendered at a narrow [PaneLayout] (see `ArticleListTopBar`'s `onNavigateUp`/
 * `title` parameters), since the feed list pane's own selection already conveys this at
 * [PaneLayout.Triple]. Falls back to [allLabel] for a feed/tag/folder id that no
 * longer exists (e.g. deleted on another device and not yet synced here), matching
 * `groupFeedsByFolder`'s own defensive "no folder" treatment.
 *
 * Search has no title of its own here — its own query field replaces this title row entirely
 * while it's expanded (see `ArticleListPane`'s own KDoc) — so [filter] alone (never displaced by
 * search) is what this always reflects.
 */
fun articleListTitle(
    filter: ArticleFilter,
    feeds: List<Feeds>,
    folders: List<Folders>,
    tags: List<Tags>,
    allLabel: String,
    starredLabel: String,
): String = when (filter) {
    ArticleFilter.All -> allLabel
    ArticleFilter.Starred -> starredLabel
    is ArticleFilter.Feed -> feeds.find { it.id == filter.feedId }?.displayTitle() ?: allLabel
    is ArticleFilter.Folder -> folders.find { it.id == filter.folderId }?.name ?: allLabel
    is ArticleFilter.Tag -> tags.find { it.id == filter.tagId }?.name ?: allLabel
}

/**
 * Builds the visual row order used by the feed pane's keyboard navigation.
 *
 * Collapsed folders contribute only their own row; expanded folders contribute their feed rows too.
 * Tags follow, and an expanded tag likewise contributes the feed rows nested under it (which are
 * distinct rows from the same feeds' folder-group rows — see [FeedListRowSelection]).
 *
 * @param tags The tags to include at the end of the order.
 * @param folders The folders used to organize feed rows.
 * @param feeds The feeds to include in folder or unassigned groups.
 * @param collapsedFolderIds The IDs of folders whose feed rows are hidden.
 * @param expandedTagIds The IDs of tags whose attached feed rows are rendered.
 * @param feedTagMap Mapping of feed IDs to their attached tag IDs.
 * @return The rows in visual top-to-bottom order.
 */
fun buildOrderedFeedListRows(
    tags: List<Tags>,
    folders: List<Folders>,
    feeds: List<Feeds>,
    collapsedFolderIds: Set<String>,
    expandedTagIds: Set<String>,
    feedTagMap: Map<String, Set<String>>,
): List<FeedListRowSelection> =
    listOf(
        FeedListRowSelection.All,
        FeedListRowSelection.Starred,
    ) +
        groupFeedsByFolder(feeds, folders).flatMap { (folder, feedsInFolder) ->
            if (folder == null) {
                feedsInFolder.map { FeedListRowSelection.FeedInFolderGroup(it.id) }
            } else if (folder.id in collapsedFolderIds) {
                listOf(FeedListRowSelection.Folder(folder.id))
            } else {
                listOf(FeedListRowSelection.Folder(folder.id)) +
                    feedsInFolder.map { FeedListRowSelection.FeedInFolderGroup(it.id) }
            }
        } +
        tags.flatMap { tag ->
            if (tag.id in expandedTagIds) {
                listOf(FeedListRowSelection.Tag(tag.id)) +
                    feedsForTag(feeds, feedTagMap, tag.id).map { FeedListRowSelection.FeedInTag(it.id, tag.id) }
            } else {
                listOf(FeedListRowSelection.Tag(tag.id))
            }
        }

/**
 * The row to move to from [current] by [delta] positions in [orderedRows]. Returns null
 * when the move would land back on [current] (e.g. already at a boundary) — callers must treat
 * null as a no-op rather than reselecting the same row, since `HomeViewModel.selectFilter`
 * clears the selected article as a side effect whenever the filter itself changes.
 */
fun nextFeedListRow(
    current: FeedListRowSelection,
    orderedRows: List<FeedListRowSelection>,
    delta: Int,
): FeedListRowSelection? {
    val index = orderedRows.indexOf(current).let { if (it < 0) 0 else it }
    val next = (index + delta).coerceIn(0, orderedRows.lastIndex)
    val target = orderedRows.getOrNull(next) ?: return null
    return target.takeIf { it != current }
}

/**
 * Where a moved feed-list row lands, expressed exactly the way the drag-and-drop path already
 * expresses a resolved drop: the id to insert it *before*, or `null` to append it at the end of its
 * scope (see `reorderIds`, and `HomeViewModel.moveFeed`/`reorderFolders`, whose target parameters
 * this is passed straight to).
 */
data class ReorderTarget(val insertBeforeId: String?)

/**
 * The [ReorderTarget] for moving the row at [index] of [orderedIds] by [delta] positions **within
 * its own reorder scope** — the sibling feeds of one folder group, or the top-level folder order.
 * This is the scope-bounded counterpart of [nextFeedListRow], which walks the *visual* row order
 * ([buildOrderedFeedListRows]) across scopes and so can't answer "what would moving this one
 * position do".
 *
 * Returns `null` — as opposed to a [ReorderTarget] holding `null`, which means "append at the end"
 * — when the move isn't possible at all: already at the first/last position in scope, or [index]
 * outside [orderedIds]. Call sites turn that into an omitted accessibility action.
 */
fun reorderTargetWithinScope(orderedIds: List<String>, index: Int, delta: Int): ReorderTarget? {
    if (index !in orderedIds.indices) return null
    val landsAt = index + delta
    if (landsAt !in orderedIds.indices) return null
    // Moving up, the row goes immediately before whatever now sits at `landsAt`; moving down, it
    // goes after it — i.e. before that row's own successor, or at the very end when there is none.
    return if (delta < 0) ReorderTarget(orderedIds[landsAt]) else ReorderTarget(orderedIds.getOrNull(landsAt + 1))
}

/** The feed/folder/tag resolved by [resolveFeedListSelectionTarget] for the current filter. */
sealed interface FeedListSelectionTarget {
    data class Feed(val feed: Feeds) : FeedListSelectionTarget
    data class Folder(val folder: Folders) : FeedListSelectionTarget
    data class Tag(val tag: Tags) : FeedListSelectionTarget
}

/**
 * Resolves [filter] against the current feed/folder/tag lists, for the rename/delete keyboard
 * shortcuts and the equivalent Feed-menu commands (both need "what is currently selected" without
 * duplicating this lookup). Returns `null` for `All`/`Starred`, or if the selected item no longer
 * exists in its list (e.g. unsubscribed between selection and the shortcut firing).
 */
fun resolveFeedListSelectionTarget(
    filter: ArticleFilter,
    feeds: List<Feeds>,
    folders: List<Folders>,
    tags: List<Tags>,
): FeedListSelectionTarget? = when (filter) {
    is ArticleFilter.Feed -> feeds.find { it.id == filter.feedId }?.let(FeedListSelectionTarget::Feed)
    is ArticleFilter.Folder -> folders.find { it.id == filter.folderId }?.let(FeedListSelectionTarget::Folder)
    is ArticleFilter.Tag -> tags.find { it.id == filter.tagId }?.let(FeedListSelectionTarget::Tag)
    else -> null
}
