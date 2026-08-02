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
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags

/** [collectAsState] for a [StateFlow] — the `initial` documents the value type. */
@Composable
fun <T> StateFlow<T>.collectAsStateSafe(@Suppress("UNUSED_PARAMETER") initial: T): State<T> = collectAsState()

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
    // All, Starred and Search all live in the single first LazyColumn item (index 0).
    if (filter is ArticleFilter.Starred || filter is ArticleFilter.All || filter is ArticleFilter.Search) return 0

    var index = 2 // 0: All/Starred/Search, 1: "Folders" header
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

/** Formats an epoch-millis timestamp as `yyyy-MM-dd HH:mm` in local time. */
@OptIn(ExperimentalTime::class)
fun formatTimestamp(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${dt.year}-${two(dt.month.number)}-${two(dt.day)} ${two(dt.hour)}:${two(dt.minute)}"
}
