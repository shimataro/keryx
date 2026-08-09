package works.merc.keryx.app.ui.home

import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import androidx.compose.ui.input.key.Key
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.platform.NativeMenuShortcut
import works.merc.keryx.app.platform.isMacOs

/** [collectAsState] for a [StateFlow] — the `initial` documents the value type. */
@Composable
fun <T> StateFlow<T>.collectAsStateSafe(@Suppress("UNUSED_PARAMETER") initial: T): State<T> = collectAsState()

/**
 * The bare-key context-menu shortcut for rename/edit-type actions (feed/folder/tag), matching each
 * OS's own file-manager rename convention (Explorer/Nautilus/Dolphin use F2, Finder uses Return).
 * Renders as a real native accelerator on Linux; AWT's `MenuShortcut` can't represent a bare key at
 * all, so macOS/Windows show no hint for it — see `NativeMenuShortcut`'s doc comment.
 */
internal val renameNativeShortcut = NativeMenuShortcut(if (isMacOs) Key.Enter else Key.F2)

/**
 * The bare-key context-menu shortcut for unsubscribe/delete-type actions (feed/folder/tag). Same
 * platform-display caveat as [renameNativeShortcut].
 */
internal val deleteNativeShortcut = NativeMenuShortcut(Key.Delete)

/**
 * Background for a selectable row: full-strength when its pane is focused, dimmed when the
 * item is selected but its pane isn't the logically-focused one, transparent otherwise. Matches
 * the "on" color of [works.merc.keryx.app.ui.common.ToggleChip]/`SegmentedControl` so selection
 * highlighting reads consistently across the app.
 */
@Composable
fun selectionBackground(selected: Boolean, focused: Boolean): Color = when {
    selected && focused -> MaterialTheme.colorScheme.primary
    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    else -> Color.Transparent
}

/**
 * Content color to pair with an opaque [selectionBackground] (`selected && focused` only) — null
 * otherwise, so callers fall back to each element's normal color (the 0.4-alpha background still
 * has enough contrast with the default text/icon colors).
 */
@Composable
fun selectionContentColorOrNull(selected: Boolean, focused: Boolean): Color? =
    if (selected && focused) MaterialTheme.colorScheme.onPrimary else null

/** Shared tint for the starred-article indicator (article list row badge, detail-view toggle). */
val StarredColor = Color(0xFFFFC107)

/**
 * Highlighter-pen span for matched search terms (bold + yellow marker background + a dark text
 * color). Theme-independent and constant regardless of row selection, so a match stays legible both
 * on the normal surface and on the teal `primary` background of a selected row (where the row's
 * `onPrimary` text would otherwise wash out over the yellow). Bold alone was invisible on unread
 * titles (already fully bold), which is why the background marker was added.
 */
val SearchHighlightSpanStyle = SpanStyle(
    fontWeight = FontWeight.Bold,
    background = Color(0xFFFFE082), // amber 200-ish marker
    color = Color(0xFF3E2723),     // near-black, readable on the yellow marker
)

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
 * Builds the visual filter order used by the feed pane.
 *
 * Collapsed folders include only their folder filter; expanded folders include their feed filters,
 * followed by tag filters.
 *
 * @param tags The tags to include at the end of the order.
 * @param folders The folders used to organize feed filters.
 * @param feeds The feeds to include in folder or unassigned groups.
 * @param collapsedFolderIds The IDs of folders whose feed filters should be omitted.
 * @return The filters in visual top-to-bottom order.
 */
fun buildOrderedFilters(
    tags: List<Tags>,
    folders: List<Folders>,
    feeds: List<Feeds>,
    collapsedFolderIds: Set<String>,
): List<ArticleFilter> =
    listOf(ArticleFilter.All, ArticleFilter.Starred, ArticleFilter.Search) +
        groupFeedsByFolder(feeds, folders).flatMap { (folder, feedsInFolder) ->
            if (folder == null) {
                feedsInFolder.map { ArticleFilter.Feed(it.id) }
            } else if (folder.id in collapsedFolderIds) {
                listOf(ArticleFilter.Folder(folder.id))
            } else {
                listOf(ArticleFilter.Folder(folder.id)) + feedsInFolder.map { ArticleFilter.Feed(it.id) }
            }
        } +
        tags.map { ArticleFilter.Tag(it.id) }

/**
 * The filter to move to from [current] by [delta] positions in [orderedFilters]. Returns null
 * when the move would land back on [current] (e.g. already at a boundary) — callers must treat
 * null as a no-op rather than reselecting the same filter, since `HomeViewModel.selectFilter`
 * unconditionally clears the selected article as a side effect.
 */
fun nextFeedFilter(current: ArticleFilter, orderedFilters: List<ArticleFilter>, delta: Int): ArticleFilter? {
    val index = orderedFilters.indexOf(current).let { if (it < 0) 0 else it }
    val next = (index + delta).coerceIn(0, orderedFilters.lastIndex)
    val target = orderedFilters.getOrNull(next) ?: return null
    return target.takeIf { it != current }
}

/**
 * Whether a keyboard shortcut that acts on the selected feed-list item (rename/edit,
 * unsubscribe/delete) should fire while [pane] has keyboard focus. These mirror the feed/folder/tag
 * row context-menu items, so they only make sense while the feed list itself is focused. (Toggle
 * read/star, open in browser, copy URL, and refresh-selected-feed have no bare-key equivalent
 * scoped this way — they are Ctrl+Shift+<letter> app-menu accelerators instead, gated by
 * `MenuUiState.articleActionsEnabled`/`urlActionsEnabled`/`feedActionsEnabled`.)
 */
fun feedListActionAllowed(pane: HomePane): Boolean = pane == HomePane.FeedList

/**
 * Finds the rendered list index for a feed, folder, or tag filter.
 *
 * Expanded tags account for their attached feed rows when calculating subsequent indices.
 *
 * @param filter The filter whose list index to find.
 * @param collapsedFolderIds Folder IDs whose feed rows are hidden.
 * @param feedTagMap Mapping of tag IDs to associated feed IDs.
 * @param expandedTagIds Tag IDs whose attached feed rows are rendered.
 * @return The filter's list index, or `null` if it is not currently rendered.
 */
fun feedListItemIndex(
    filter: ArticleFilter,
    feeds: List<Feeds>,
    folders: List<Folders>,
    tags: List<Tags>,
    collapsedFolderIds: Set<String>,
    feedTagMap: Map<String, Set<String>> = emptyMap(),
    expandedTagIds: Set<String> = emptySet(),
): Int? {
    // All, Starred, and Search are rendered outside the LazyColumn entirely (as fixed SidebarRows
    // above it), so they never correspond to a LazyColumn item and selecting them must not scroll it.
    if (filter is ArticleFilter.Starred || filter is ArticleFilter.All || filter is ArticleFilter.Search) return null

    var index = 1 // 0: "Folders" header
    for ((folder, feedsInFolder) in groupFeedsByFolder(feeds, folders)) {
        if (folder == null) {
            if (folders.isNotEmpty()) index++ // NoFolderHeader
            feedsInFolder.forEachIndexed { i, feed ->
                if (filter is ArticleFilter.Feed && filter.feedId == feed.id) return index + i
            }
            index += feedsInFolder.size
        } else {
            if (filter is ArticleFilter.Folder && filter.folderId == folder.id) return index
            index++ // FolderGroupHeader
            if (folder.id !in collapsedFolderIds) {
                feedsInFolder.forEachIndexed { i, feed ->
                    if (filter is ArticleFilter.Feed && filter.feedId == feed.id) return index + i
                }
                index += feedsInFolder.size
            }
        }
    }
    index++ // divider
    index++ // "Tags" header
    for (tag in tags) {
        if (filter is ArticleFilter.Tag && filter.tagId == tag.id) return index
        index++ // TagRow
        if (tag.id in expandedTagIds) {
            index += feedsForTag(feeds, feedTagMap, tag.id).size
        }
    }
    return null
}

/**
 * Turns FTS5 highlight/snippet markup (matched spans wrapped in
 * [FtsSearch.MARK_START]/[FtsSearch.MARK_END]) into an [AnnotatedString] whose matched spans get the
 * [SearchHighlightSpanStyle] highlighter (bold + yellow marker background). The sentinel chars are
 * consumed, never rendered. Unbalanced markup is handled defensively: a redundant start or a stray
 * end is ignored, and an unclosed start extends the highlight to the end.
 */
fun markedToAnnotatedString(marked: String): AnnotatedString = buildAnnotatedString {
    var marking = false
    for (ch in marked) {
        when (ch) {
            FtsSearch.MARK_START -> if (!marking) { pushStyle(SearchHighlightSpanStyle); marking = true }
            FtsSearch.MARK_END -> if (marking) { pop(); marking = false }
            else -> append(ch)
        }
    }
}

/**
 * Scrolls [index] into view: jumps directly there if it isn't currently rendered, otherwise nudges
 * just enough to bring it fully within the viewport when it's only partially visible at an edge.
 */
suspend fun LazyListState.scrollToIndexIfNeeded(index: Int) {
    val info = layoutInfo
    val itemInfo = info.visibleItemsInfo.find { it.index == index }

    if (itemInfo == null) {
        animateScrollToItem(index)
    } else {
        val viewportStart = info.viewportStartOffset
        val viewportEnd = info.viewportEndOffset
        val itemStart = itemInfo.offset
        val itemEnd = itemInfo.offset + itemInfo.size

        when {
            itemStart < viewportStart -> animateScrollBy((itemStart - viewportStart).toFloat())
            itemEnd > viewportEnd -> animateScrollBy((itemEnd - viewportEnd).toFloat())
        }
    }
}

/**
 * Auto-scroll speed for a drag hovering near a scrollable list's edge, in pixels per second.
 *
 * The sign matches [LazyListState.scrollBy]'s: negative scrolls back toward the start of the list
 * (pointer near [viewportTop]), positive scrolls on toward its end (pointer near [viewportBottom]).
 * Returns 0 in the dead zone between the two edge zones. Inside an edge zone the speed ramps
 * linearly from 0 at the zone's inner boundary ([edgeZonePx] in from the edge) to
 * [maxSpeedPxPerSec] at the edge itself, and stays at [maxSpeedPxPerSec] for a [pointerY] outside
 * the viewport entirely.
 *
 * A viewport shorter than `2 * edgeZonePx` shrinks both zones to half its height rather than
 * letting them overlap, so every position still resolves to exactly one zone (or the dead zone).
 */
fun autoScrollVelocityPxPerSec(
    pointerY: Float,
    viewportTop: Float,
    viewportBottom: Float,
    edgeZonePx: Float,
    maxSpeedPxPerSec: Float,
): Float {
    val height = viewportBottom - viewportTop
    if (height <= 0f || edgeZonePx <= 0f) return 0f
    val zone = edgeZonePx.coerceAtMost(height / 2f)
    val distanceFromTop = pointerY - viewportTop
    val distanceFromBottom = viewportBottom - pointerY
    return when {
        distanceFromTop < zone -> -maxSpeedPxPerSec * ((zone - distanceFromTop) / zone).coerceIn(0f, 1f)
        distanceFromBottom < zone -> maxSpeedPxPerSec * ((zone - distanceFromBottom) / zone).coerceIn(0f, 1f)
        else -> 0f
    }
}

/**
 * Precomputed lookup tables for resolving feed/folder drag-and-drop insertion points by id, built
 * once per [feeds]/[folders] change (see [buildFeedListDropIndex]) rather than re-deriving grouping
 * ad hoc for every drag event.
 */
internal data class FeedListDropIndex(
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
internal fun buildFeedListDropIndex(feeds: List<Feeds>, folders: List<Folders>): FeedListDropIndex {
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
private const val NO_FOLDER_HEADER_KEY = "no-folder-header"

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
        stringKey.startsWith(TAG_KEY_PREFIX) && "-feed-" !in stringKey ->
            FeedListRowKey.Tag(stringKey.removePrefix(TAG_KEY_PREFIX))
        else -> FeedListRowKey.Other
    }
}

/** What a feed-list `LazyColumn` row can be dragged *as*, derived from its `key`. */
internal sealed interface FeedListDragSourceKey {
    data class Feed(val feedId: String) : FeedListDragSourceKey
    data class Folder(val folderId: String) : FeedListDragSourceKey
}

/** Separator between a tag-attached feed row's tag id and its feed id (`"tag-$tagId-feed-$feedId"`). */
private const val TAG_FEED_INFIX = "-feed-"

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

/**
     * Formats an epoch-millisecond timestamp as `yyyy-MM-dd HH:mm` in the system default time zone.
     *
     * @param epochMillis The timestamp to format, or `null`.
     * @return The formatted timestamp, or an empty string when `epochMillis` is `null`.
     */
fun formatTimestamp(epochMillis: Long?): String =
    formatTimestamp(epochMillis, TimeZone.currentSystemDefault())

/**
 * Formats an epoch-millis timestamp as `yyyy-MM-dd HH:mm` in [zone].
 *
 * Callers that format many timestamps in a row (the article list) resolve the zone once and pass it
 * here: `TimeZone.currentSystemDefault()` clones the JVM default zone on every call, which is the
 * bulk of the cost when this runs per visible row.
 */
@OptIn(ExperimentalTime::class)
/**
 * Formats an epoch timestamp as `yyyy-MM-dd HH:mm` in the specified time zone.
 *
 * @param epochMillis The epoch timestamp in milliseconds, or `null`.
 * @param zone The time zone used for formatting.
 * @return The formatted timestamp, or an empty string when `epochMillis` is `null`.
 */
fun formatTimestamp(epochMillis: Long?, zone: TimeZone): String {
    if (epochMillis == null) return ""
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    // Hand-rolled padding rather than padStart: same output, without a StringBuilder and an
    // intermediate String per field.
    return buildString(16) {
        appendFourDigits(dt.year)
        append('-')
        appendTwoDigits(dt.month.number)
        append('-')
        appendTwoDigits(dt.day)
        append(' ')
        appendTwoDigits(dt.hour)
        append(':')
        appendTwoDigits(dt.minute)
    }
}

/** Appends [value] zero-padded to at least two digits. */
private fun StringBuilder.appendTwoDigits(value: Int) {
    if (value < 10) append('0')
    append(value)
}

/**
 * Appends [value] zero-padded to at least four digits, so the year keeps the documented `yyyy`
 * width. A negative value is appended as-is: the format has no representation for one anyway.
 */
private fun StringBuilder.appendFourDigits(value: Int) {
    when (value) {
        in 0..9 -> append("000")
        in 10..99 -> append("00")
        in 100..999 -> append('0')
    }
    append(value)
}
