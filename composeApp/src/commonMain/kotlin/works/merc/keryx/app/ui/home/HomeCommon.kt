package works.merc.keryx.app.ui.home

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

/** Flattened feed-pane order, matching `FeedListPane`'s visual top-to-bottom order. */
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
 * Returns the real LazyColumn index of [filter]'s row in [FeedListPane]'s list.
 * Returns null if that row is not currently rendered (e.g. a feed under a collapsed folder).
 * This mirrors FeedListPane.kt's LazyColumn item structure — if that structure changes,
 * update both together.
 */
fun feedListItemIndex(
    filter: ArticleFilter,
    feeds: List<Feeds>,
    folders: List<Folders>,
    tags: List<Tags>,
    collapsedFolderIds: Set<String>,
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
    tags.forEachIndexed { i, tag ->
        if (filter is ArticleFilter.Tag && filter.tagId == tag.id) return index + i
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

/** Formats an epoch-millis timestamp as `yyyy-MM-dd HH:mm` in local time. */
@OptIn(ExperimentalTime::class)
fun formatTimestamp(epochMillis: Long?): String {
    if (epochMillis == null) return ""
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.currentSystemDefault())
    fun two(n: Int) = n.toString().padStart(2, '0')
    return "${dt.year}-${two(dt.month.number)}-${two(dt.day)} ${two(dt.hour)}:${two(dt.minute)}"
}
