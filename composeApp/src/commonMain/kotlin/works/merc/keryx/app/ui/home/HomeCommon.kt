package works.merc.keryx.app.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
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
import works.merc.keryx.app.domain.displayTitle
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
 * Whether a row's selection highlight should be visibly painted at all — see [LocalRowSelectionVisible].
 */
internal val LocalRowSelectionVisible = staticCompositionLocalOf { true }

/**
 * Click-to-focus for a pane's background — on a mouse+keyboard platform there is no OS-level
 * click-to-focus for the panes inside this one window, so a plain click anywhere in a pane's
 * empty background is this app's only way to move keyboard focus onto it.
 *
 * Dropped entirely on a touch-primary platform ([isTouchPrimary]): touch has no keyboard focus to
 * move in the first place, so keeping this modifier there would only add an unlabeled, full-size
 * accessibility click node sitting behind every other control in the pane.
 *
 * @param isTouchPrimary Overridable for tests only (mirrors `feedListReorderDrag`'s own
 *   `isTouchPrimary` parameter) — production call sites always use the platform default from
 *   `platform/PlatformOs.kt`.
 */
@Composable
internal fun Modifier.paneActivation(
    onActivated: () -> Unit,
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
): Modifier = if (isTouchPrimary) {
    this
} else {
    this.clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onActivated)
}

/**
 * Background for a selectable row: full-strength when its pane is focused, dimmed when the
 * item is selected but its pane isn't the logically-focused one, transparent otherwise. Matches
 * the "on" color of [works.merc.keryx.app.ui.common.ToggleChip]/`SegmentedControl` so selection
 * highlighting reads consistently across the app.
 *
 * Returns [Color.Transparent] whenever [LocalRowSelectionVisible] reads `false` — set by `HomeScreen`
 * at [PaneLayout.Single], where "selected" doesn't mean "on screen" the way it does at [PaneLayout.Dual]/
 * [PaneLayout.Triple]: tapping a row navigates away from it, so a lingering highlight on a row the
 * user can no longer see would read as stale rather than as "your place."
 */
@Composable
fun selectionBackground(selected: Boolean, focused: Boolean): Color = when {
    !LocalRowSelectionVisible.current -> Color.Transparent
    selected && focused -> MaterialTheme.colorScheme.primary
    selected -> MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    else -> Color.Transparent
}

/**
 * Content color to pair with an opaque [selectionBackground] (`selected && focused` only) — null
 * otherwise, so callers fall back to each element's normal color (the 0.4-alpha background still
 * has enough contrast with the default text/icon colors). Also `null` whenever
 * [LocalRowSelectionVisible] reads `false`, matching [selectionBackground] never painting an opaque
 * background there either.
 */
@Composable
fun selectionContentColorOrNull(selected: Boolean, focused: Boolean): Color? =
    if (LocalRowSelectionVisible.current && selected && focused) MaterialTheme.colorScheme.onPrimary else null

/**
 * Alpha of the [RowSelectionTone.SECONDARY] tint — deliberately well below the 0.4 alpha of an
 * unfocused [RowSelectionTone.PRIMARY] row, so a duplicate row of the selected feed reads as
 * "same feed, not the row you're on" rather than as a second selection.
 */
internal const val SECONDARY_SELECTION_ALPHA = 0.15f

/**
 * How strongly a feed-list row paints its selection. A feed renders once under its folder group and
 * again under every expanded tag it carries, so "selected" is not a single row: exactly one rendered
 * instance is the one actually clicked/keyboard-navigated to ([PRIMARY]), and every other instance of
 * the same selected feed is a [SECONDARY] echo of it. [NONE] is an unselected row.
 */
enum class RowSelectionTone { NONE, SECONDARY, PRIMARY }

/**
 * Background for a row that can render as more than one instance (see [RowSelectionTone]). [PRIMARY]
 * matches the boolean [selectionBackground] exactly, so a feed with no duplicates looks unchanged.
 * Gated on [LocalRowSelectionVisible] the same way the boolean overload is — see that overload's
 * own KDoc.
 */
@Composable
fun selectionBackground(tone: RowSelectionTone, focused: Boolean): Color = when {
    !LocalRowSelectionVisible.current -> Color.Transparent
    tone == RowSelectionTone.PRIMARY ->
        if (focused) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primary.copy(alpha = 0.4f)
    tone == RowSelectionTone.SECONDARY -> MaterialTheme.colorScheme.primary.copy(alpha = SECONDARY_SELECTION_ALPHA)
    else -> Color.Transparent
}

/**
 * Content color to pair with the tone-aware [selectionBackground] — only the opaque
 * `PRIMARY && focused` background needs one, exactly as in the boolean overload. Also gated on
 * [LocalRowSelectionVisible].
 */
@Composable
fun selectionContentColorOrNull(tone: RowSelectionTone, focused: Boolean): Color? =
    if (LocalRowSelectionVisible.current && tone == RowSelectionTone.PRIMARY && focused) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        null
    }

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

    data object Search : FeedListRowSelection {
        override val filter: ArticleFilter get() = ArticleFilter.Search
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
         * Canonical instance for a bare [ArticleFilter] change (search jump, notification-center
         * "show feed detail", any caller with no specific row in mind) — always the folder-group
         * instance for a feed.
         */
        fun canonicalFor(filter: ArticleFilter): FeedListRowSelection = when (filter) {
            ArticleFilter.All -> All
            ArticleFilter.Starred -> Starred
            ArticleFilter.Search -> Search
            is ArticleFilter.Feed -> FeedInFolderGroup(filter.feedId)
            is ArticleFilter.Folder -> Folder(filter.folderId)
            is ArticleFilter.Tag -> Tag(filter.tagId)
        }
    }
}

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
 * The display title for the article list pane's current [filter] — shown in its top bar only when
 * the pane is rendered at a narrow [PaneLayout] (see `ArticleListTopBar`'s `onNavigateUp`/
 * `title` parameters), since the feed list pane's own selection already conveys this at
 * [PaneLayout.Triple]. Falls back to [allLabel] for a feed/tag/folder id that no
 * longer exists (e.g. deleted on another device and not yet synced here), matching
 * `groupFeedsByFolder`'s own defensive "no folder" treatment.
 */
fun articleListTitle(
    filter: ArticleFilter,
    feeds: List<Feeds>,
    folders: List<Folders>,
    tags: List<Tags>,
    allLabel: String,
    starredLabel: String,
    searchLabel: String,
): String = when (filter) {
    ArticleFilter.All -> allLabel
    ArticleFilter.Starred -> starredLabel
    ArticleFilter.Search -> searchLabel
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
    listOf(FeedListRowSelection.All, FeedListRowSelection.Starred, FeedListRowSelection.Search) +
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
internal data class ReorderTarget(val insertBeforeId: String?)

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
internal fun reorderTargetWithinScope(orderedIds: List<String>, index: Int, delta: Int): ReorderTarget? {
    if (index !in orderedIds.indices) return null
    val landsAt = index + delta
    if (landsAt !in orderedIds.indices) return null
    // Moving up, the row goes immediately before whatever now sits at `landsAt`; moving down, it
    // goes after it — i.e. before that row's own successor, or at the very end when there is none.
    return if (delta < 0) ReorderTarget(orderedIds[landsAt]) else ReorderTarget(orderedIds.getOrNull(landsAt + 1))
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

/** Whether the refresh-all / sync actions (toolbar buttons and app-menu items alike) are
 * available — each is blocked while the other operation is in flight, since running both at
 * once isn't supported. */
internal fun feedOperationsAvailable(feedRefreshing: Boolean, syncing: Boolean): Boolean =
    !feedRefreshing && !syncing

/** Whether [url] is present and non-blank — the single rule for when URL-dependent actions
 * (open in browser, copy URL) are available, for an article's URL or a feed's site URL alike. */
internal fun hasUsableUrl(url: String?): Boolean = !url.isNullOrBlank()

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
    // All, Starred, and Search are rendered outside the LazyColumn entirely (as fixed SidebarRows
    // above it), so they never correspond to a LazyColumn item and selecting them must not scroll it.
    if (instance is FeedListRowSelection.All ||
        instance is FeedListRowSelection.Starred ||
        instance is FeedListRowSelection.Search
    ) {
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

/** The feed/folder/tag resolved by [resolveFeedListSelectionTarget] for the current filter. */
internal sealed interface FeedListSelectionTarget {
    data class Feed(val feed: Feeds) : FeedListSelectionTarget
    data class Folder(val folder: Folders) : FeedListSelectionTarget
    data class Tag(val tag: Tags) : FeedListSelectionTarget
}

/**
 * Resolves [filter] against the current feed/folder/tag lists, for the rename/delete keyboard
 * shortcuts and the equivalent Feed-menu commands (both need "what is currently selected" without
 * duplicating this lookup). Returns `null` for `All`/`Starred`/`Search`, or if the selected item no
 * longer exists in its list (e.g. unsubscribed between selection and the shortcut firing).
 */
internal fun resolveFeedListSelectionTarget(
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

/**
 * Which feed-list row currently has its name open for inline editing (`FeedListPane`'s single
 * edit-mode state — there is never more than one). Kept as an id + kind rather than as the row's
 * whole `Feeds`/`Folders`/`Tags` value, so a row that recomposes with fresh data mid-edit still
 * matches, and so the two id spaces (a feed and a folder can never be edited at once anyway) can't
 * be confused for each other.
 */
internal sealed interface InlineEditTarget {
    /** The exact rendered row this edit is on — a feed edits on its folder-group row unless
     * [Feed.tagId] names the specific tag-nested row it was started from instead. */
    val rowInstance: FeedListRowSelection

    /** The filter that selects this row, so the pane can scroll it into view before editing starts. */
    val filter: ArticleFilter get() = rowInstance.filter

    /** @param tagId Non-null when editing started on the row nested under that tag, rather than the
     *   feed's canonical folder-group row (see [FeedListRowSelection]). */
    data class Feed(val id: String, val tagId: String? = null) : InlineEditTarget {
        override val rowInstance: FeedListRowSelection
            get() = tagId?.let { FeedListRowSelection.FeedInTag(id, it) }
                ?: FeedListRowSelection.FeedInFolderGroup(id)
    }

    data class Folder(val id: String) : InlineEditTarget {
        override val rowInstance: FeedListRowSelection get() = FeedListRowSelection.Folder(id)
    }

    data class Tag(val id: String) : InlineEditTarget {
        override val rowInstance: FeedListRowSelection get() = FeedListRowSelection.Tag(id)
    }
}

/** The inline-edit target for a selection resolved by [resolveFeedListSelectionTarget] — the bridge
 * between "what is selected" (keyboard shortcut / menu command) and "what row is being edited".
 * [rowInstance] carries which rendered row instance (folder-group vs. a specific tag-nested copy)
 * the selection was actually made on, so a feed selected via its tag-nested row edits there too. */
internal fun FeedListSelectionTarget.toInlineEditTarget(rowInstance: FeedListRowSelection): InlineEditTarget = when (this) {
    is FeedListSelectionTarget.Feed -> InlineEditTarget.Feed(
        id = feed.id,
        tagId = (rowInstance as? FeedListRowSelection.FeedInTag)?.takeIf { it.feedId == feed.id }?.tagId,
    )
    is FeedListSelectionTarget.Folder -> InlineEditTarget.Folder(folder.id)
    is FeedListSelectionTarget.Tag -> InlineEditTarget.Tag(tag.id)
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
