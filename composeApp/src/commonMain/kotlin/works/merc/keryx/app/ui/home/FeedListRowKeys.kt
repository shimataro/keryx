package works.merc.keryx.app.ui.home

import androidx.compose.foundation.layout.size
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.presentation.home.FeedListRowSelection
import works.merc.keryx.app.presentation.home.feedsForTag
import works.merc.keryx.app.presentation.home.groupFeedsByFolder

/**
 * The rendered `LazyColumn` index for one specific feed-list row instance, or `null` if it isn't
 * currently rendered (its folder is collapsed, its tag isn't expanded, or the item no longer
 * exists). Because [instance] names exactly one rendered row — a feed's folder-group row and its
 * row under a given expanded tag are different instances — no "pick among several" heuristic is
 * needed: the answer is exact.
 *
 * @param instance The row instance whose list index to find.
 * @param collapsedFolderIds Folder IDs whose feed rows are hidden.
 * @param feedTagMap Mapping of feed IDs to their attached tag IDs.
 * @param expandedTagIds Tag IDs whose attached feed rows are rendered.
 */
fun feedListRowIndex(
    instance: FeedListRowSelection,
    feeds: List<Feeds>,
    folders: List<Folders>,
    tags: List<Tags>,
    collapsedFolderIds: Set<String>,
    feedTagMap: Map<String, Set<String>> = emptyMap(),
    expandedTagIds: Set<String> = emptySet(),
): Int? {
    // All and Starred are rendered outside the LazyColumn entirely (as fixed SidebarRows above
    // it), so they never correspond to a LazyColumn item and selecting them must not scroll it.
    if (instance is FeedListRowSelection.All || instance is FeedListRowSelection.Starred) {
        return null
    }

    var index = 1 // 0: "Folders" header
    for ((folder, feedsInFolder) in groupFeedsByFolder(feeds, folders)) {
        if (folder == null) {
            if (folders.isNotEmpty()) index++ // NoFolderHeader
            feedsInFolder.forEachIndexed { i, feed ->
                if (instance is FeedListRowSelection.FeedInFolderGroup && instance.feedId == feed.id) return index + i
            }
            index += feedsInFolder.size
        } else {
            if (instance is FeedListRowSelection.Folder && instance.folderId == folder.id) return index
            index++ // FolderGroupHeader
            if (folder.id !in collapsedFolderIds) {
                feedsInFolder.forEachIndexed { i, feed ->
                    if (instance is FeedListRowSelection.FeedInFolderGroup && instance.feedId == feed.id) return index + i
                }
                index += feedsInFolder.size
            }
        }
    }
    index++ // divider
    index++ // "Tags" header
    for (tag in tags) {
        if (instance is FeedListRowSelection.Tag && instance.tagId == tag.id) return index
        index++ // TagRow
        if (tag.id in expandedTagIds) {
            val feedsInTag = feedsForTag(feeds, feedTagMap, tag.id)
            if (instance is FeedListRowSelection.FeedInTag && instance.tagId == tag.id) {
                feedsInTag.forEachIndexed { i, feed -> if (instance.feedId == feed.id) return index + i }
            }
            index += feedsInTag.size
        }
    }
    return null
}

/** Parsed identity of a feed-list `LazyColumn` row, derived from its `key`. */
internal sealed interface FeedListRowKey {
    data class Folder(val folderId: String) : FeedListRowKey
    data class Feed(val feedId: String) : FeedListRowKey
    data class Tag(val tagId: String) : FeedListRowKey
    data object NoFolderHeader : FeedListRowKey
    data object Other : FeedListRowKey
}

private const val FOLDER_KEY_PREFIX = "folder-"

private const val FEED_KEY_PREFIX = "feed-"

private const val TAG_KEY_PREFIX = "tag-"

/** Separator between a tag-attached feed row's tag id and its feed id (`"tag-$tagId-feed-$feedId"`). */
private const val TAG_FEED_INFIX = "-feed-"

/** `LazyColumn` key of the feed list's "no folder" group header. */
internal const val NO_FOLDER_HEADER_KEY = "no-folder-header"

// The `LazyColumn` keys FeedListPane gives its rows — built here, beside the parsers below that
// read them back, so the two can't drift apart.

/** `LazyColumn` key of a feed row in the folder section. */
internal fun feedRowKey(feedId: String): String = "$FEED_KEY_PREFIX$feedId"

/** `LazyColumn` key of a folder header row. */
internal fun folderRowKey(folderId: String): String = "$FOLDER_KEY_PREFIX$folderId"

/** `LazyColumn` key of a tag row. */
internal fun tagRowKey(tagId: String): String = "$TAG_KEY_PREFIX$tagId"

/** `LazyColumn` key of a feed row listed under an expanded tag. */
internal fun tagFeedRowKey(tagId: String, feedId: String): String = "$TAG_KEY_PREFIX$tagId$TAG_FEED_INFIX$feedId"

/**
 * Identifies the feed-list row represented by a `LazyColumn` item key.
 *
 * Tag-attached feed keys are classified as feed rows rather than tag rows.
 *
 * @param key The item key assigned to the feed-list row.
 * @return The parsed row identity, or `FeedListRowKey.Other` for unrecognized keys.
 */
internal fun parseFeedListRowKey(key: Any?): FeedListRowKey {
    val stringKey = key as? String ?: return FeedListRowKey.Other
    return when {
        stringKey == NO_FOLDER_HEADER_KEY -> FeedListRowKey.NoFolderHeader
        stringKey.startsWith(FOLDER_KEY_PREFIX) -> FeedListRowKey.Folder(stringKey.removePrefix(FOLDER_KEY_PREFIX))
        stringKey.startsWith(FEED_KEY_PREFIX) -> FeedListRowKey.Feed(stringKey.removePrefix(FEED_KEY_PREFIX))
        stringKey.startsWith(TAG_KEY_PREFIX) && TAG_FEED_INFIX !in stringKey ->
            FeedListRowKey.Tag(stringKey.removePrefix(TAG_KEY_PREFIX))
        else -> FeedListRowKey.Other
    }
}

/** What a feed-list `LazyColumn` row can be dragged *as*, derived from its `key`. */
internal sealed interface FeedListDragSourceKey {
    data class Feed(val feedId: String) : FeedListDragSourceKey
    data class Folder(val folderId: String) : FeedListDragSourceKey
}

/**
 * Parses a feed-list `LazyColumn` item's `key` into what dragging that row would drag, or `null`
 * when the row isn't draggable at all (a tag row, a section header, the divider).
 *
 * Deliberately separate from [parseFeedListRowKey], which answers the *drop target* question: a
 * feed listed under an expanded tag (`"tag-$tagId-feed-$feedId"`) is a perfectly good drag source
 * — it drags the feed itself — while remaining a non-target ([FeedListRowKey.Other]) there, since
 * dropping onto it means nothing. The two must not be collapsed into one parser.
 */
internal fun parseFeedListDragSourceKey(key: Any?): FeedListDragSourceKey? {
    val stringKey = key as? String ?: return null
    return when {
        stringKey.startsWith(FOLDER_KEY_PREFIX) ->
            FeedListDragSourceKey.Folder(stringKey.removePrefix(FOLDER_KEY_PREFIX))
        stringKey.startsWith(FEED_KEY_PREFIX) ->
            FeedListDragSourceKey.Feed(stringKey.removePrefix(FEED_KEY_PREFIX))
        stringKey.startsWith(TAG_KEY_PREFIX) && TAG_FEED_INFIX in stringKey ->
            FeedListDragSourceKey.Feed(stringKey.substringAfter(TAG_FEED_INFIX))
        else -> null
    }
}

/**
 * A `LazyColumn` item's key and vertical bounds, in the same "distance from the viewport's leading
 * edge" space as `LazyListItemInfo.offset`/`.size` — deliberately decoupled from that real Compose
 * Foundation type so hit-testing stays trivially unit-testable.
 */
internal data class FeedListRowBand(val key: Any?, val offsetPx: Int, val sizePx: Int)

/** The [FeedListRowBand] in [bands] containing [localY] (already `pointerY - viewportTop`), or `null`
 * if none does (e.g. [localY] falls outside every band, or [bands] is empty). */
internal fun resolveHitBand(localY: Float, bands: List<FeedListRowBand>): FeedListRowBand? =
    bands.find { localY >= it.offsetPx && localY < it.offsetPx + it.sizePx }

/** Which half of a matched row/header a drag is currently hovering over, used to decide whether an
 * insertion point lands before or after it. */
internal enum class RowHalf { TOP, BOTTOM }

/** Resolves which half of [band] [localY] falls in. */
internal fun resolveRowHalf(localY: Float, band: FeedListRowBand): RowHalf =
    if (localY - band.offsetPx < band.sizePx / 2f) RowHalf.TOP else RowHalf.BOTTOM
