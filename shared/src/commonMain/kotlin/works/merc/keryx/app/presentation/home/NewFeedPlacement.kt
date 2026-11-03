package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Feeds

/**
 * Where a newly subscribed feed goes, derived from the feed list's current selection — what
 * [HomeViewModel.subscribeFeeds] hands to the subscribe path.
 *
 * @property folderId The folder to file it into: the selected folder itself, or the folder of the
 *   selected feed. Any other selection (all/starred/tag) yields `null` (no folder).
 * @property afterFeedId The feed to insert it directly after: the selected feed itself, whether it's
 *   filed in a folder or unfiled. Any other selection (folder/all/starred/tag) yields `null`, which
 *   appends the new feed to the end of its target group instead.
 * @property beforeFeedId The feed to insert it directly before: the current first feed in the target
 *   group [folderId] (the "no folder" group when [folderId] is `null`), or `null` if that group is
 *   empty. Only consulted when [afterFeedId] is null or its target isn't in the group (see
 *   `FeedRepository.insertionSortOrderForNewFeed`), so this doesn't affect the "insert directly
 *   after the selected feed" behavior.
 * @property tagId The tag to tag it with: the currently selected tag, whether selected directly or via
 *   a feed selected within an expanded tag's feed sub-list. `null` for any other selection (folder,
 *   feed outside a tag, starred, or all) — no tag is applied.
 */
internal data class NewFeedPlacement(
    val folderId: String?,
    val afterFeedId: String?,
    val beforeFeedId: String?,
    val tagId: String?,
)

/**
 * Resolves [NewFeedPlacement] for a feed subscribed while [filter] and [selection] are selected.
 *
 * @param filter The selected article filter.
 * @param selection The selected feed-list row (which also tells a feed selected under a tag apart).
 * @param feeds The current subscription list.
 */
internal fun newFeedPlacement(
    filter: ArticleFilter,
    selection: FeedListRowSelection,
    feeds: List<Feeds>,
): NewFeedPlacement {
    val folderId = when (filter) {
        is ArticleFilter.Folder -> filter.folderId
        is ArticleFilter.Feed -> feeds.firstOrNull { it.id == filter.feedId }?.folder_id
        ArticleFilter.All, ArticleFilter.Starred, is ArticleFilter.Tag -> null
    }
    val afterFeedId = when (filter) {
        is ArticleFilter.Feed -> filter.feedId
        ArticleFilter.All, ArticleFilter.Starred, is ArticleFilter.Folder, is ArticleFilter.Tag -> null
    }
    val beforeFeedId = feeds.filter { it.folder_id == folderId }.minByOrNull { it.sort_order }?.id
    val tagId = when (selection) {
        is FeedListRowSelection.Tag -> selection.tagId
        is FeedListRowSelection.FeedInTag -> selection.tagId
        FeedListRowSelection.All, FeedListRowSelection.Starred,
        is FeedListRowSelection.FeedInFolderGroup, is FeedListRowSelection.Folder,
        -> null
    }
    return NewFeedPlacement(folderId, afterFeedId, beforeFeedId, tagId)
}
