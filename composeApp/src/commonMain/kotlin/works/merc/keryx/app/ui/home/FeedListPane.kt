package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.toSize
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.VerticalScrollbarIfNeeded
import works.merc.keryx.app.platform.WindowChrome
import works.merc.keryx.app.platform.WindowDragArea
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_menu_item_with_shortcut
import works.merc.keryx.app.resources.home_add_feed
import works.merc.keryx.app.resources.home_add_folder
import works.merc.keryx.app.resources.home_add_tag
import works.merc.keryx.app.resources.home_all_feeds
import works.merc.keryx.app.resources.home_delete_tag_menu
import works.merc.keryx.app.resources.home_edit_tag_menu
import works.merc.keryx.app.resources.home_folders
import works.merc.keryx.app.resources.home_refresh
import works.merc.keryx.app.resources.home_refreshing
import works.merc.keryx.app.resources.home_remove_feed_from_tag_menu
import works.merc.keryx.app.resources.home_search
import works.merc.keryx.app.resources.home_search_clear
import works.merc.keryx.app.resources.home_search_placeholder
import works.merc.keryx.app.resources.home_starred
import works.merc.keryx.app.resources.home_sync
import works.merc.keryx.app.resources.home_syncing
import works.merc.keryx.app.resources.home_tags
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.KeryxTextField
import works.merc.keryx.app.ui.common.SmallSpinner
import works.merc.keryx.app.ui.common.ToolbarIconGroup
import works.merc.keryx.app.ui.common.TooltipIconButton

/** How close to the feed list's top/bottom edge a drag must come before the list auto-scrolls, so a
 * drop target outside the current viewport can still be reached without letting go of the drag. */
private const val AUTO_SCROLL_EDGE_ZONE_DP = 56

/** Auto-scroll speed reached when a drag sits exactly on the feed list's top/bottom edge, ramping
 * down to zero at the inner boundary of [AUTO_SCROLL_EDGE_ZONE_DP]. */
private const val AUTO_SCROLL_MAX_SPEED_DP_PER_SEC = 900

/** Auto-scroll tick length (~60fps). */
private const val AUTO_SCROLL_FRAME_MS = 16L

/**
 * Test tag on the pane's single non-virtualized drag-host `Box` (see its doc comment below),
 * letting `FeedListDragTest` compute press/drop positions in that `Box`'s local coordinate space
 * without depending on internal composition details (e.g. the `LazyListState` it owns).
 */
internal const val FEED_LIST_DRAG_HOST_TEST_TAG = "feed-list-drag-host"

/**
 * Renders the feed navigation pane with filters, folders, tags, search, synchronization controls, and feed management actions.
 *
 * @param vm The view model that provides feed state and handles navigation and management operations.
 * @param focused Whether the pane currently has focus.
 * @param dragOverlay Window-wide drag-ghost state, owned by `HomeScreen` (which renders the ghost).
 * @param onActivated Called when the pane becomes active.
 * @param modifier Modifier applied to the pane.
 * @param onAddFeedClick Called when the user requests to add a feed.
 * @param onSearchFieldFocusChange Called when the search field focus changes.
 * @param renameSelectedRequestId Bumped by the keyboard rename/edit shortcut (F2/Return); on change,
 *   opens the rename/edit dialog for whichever feed/folder/tag the current filter selects.
 * @param deleteSelectedRequestId Bumped by the keyboard delete shortcut (Delete/Backspace); on
 *   change, opens the unsubscribe/delete confirmation for whichever feed/folder/tag the current
 *   filter selects.
 */
@Composable
internal fun FeedListPane(
    vm: HomeViewModel,
    focused: Boolean,
    dragOverlay: FeedDragOverlayState,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier,
    onAddFeedClick: () -> Unit = {},
    onSearchFieldFocusChange: (Boolean) -> Unit = {},
    renameSelectedRequestId: Int = 0,
    deleteSelectedRequestId: Int = 0,
) {
    val feeds by vm.feeds.collectAsStateSafe(emptyList())
    val tags by vm.tags.collectAsStateSafe(emptyList())
    val feedTagMap by vm.feedTagMap.collectAsStateSafe(emptyMap())
    val folders by vm.folders.collectAsStateSafe(emptyList())
    val unreadByFeed by vm.unreadByFeed.collectAsStateSafe(emptyMap())
    val unreadByTag by vm.unreadByTag.collectAsStateSafe(emptyMap())
    val unreadByFolder by vm.unreadByFolder.collectAsStateSafe(emptyMap())
    val collapsedFolderIds by vm.collapsedFolderIds.collectAsStateSafe(emptySet())
    val expandedTagIds by vm.expandedTagIds.collectAsStateSafe(emptySet())
    val totalUnread by vm.totalUnread.collectAsStateSafe(0L)
    val starredUnread by vm.starredUnreadCount.collectAsStateSafe(0L)
    val searchUnread by vm.searchUnreadCount.collectAsStateSafe(0L)
    val filter by vm.filter.collectAsStateSafe(ArticleFilter.All)
    val searchQuery by vm.searchQuery.collectAsStateSafe("")
    val cloudConnected by vm.cloudConnected.collectAsStateSafe(false)
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.searchFocusRequests.collect { searchFocusRequester.requestFocus() }
    }

    var showAddTag by remember { mutableStateOf(false) }
    var editingTag by remember { mutableStateOf<Tags?>(null) }
    var confirmingDeleteTag by remember { mutableStateOf<Tags?>(null) }
    var showAddFolder by remember { mutableStateOf(false) }
    var editingFolder by remember { mutableStateOf<Folders?>(null) }
    var confirmingDeleteFolder by remember { mutableStateOf<Folders?>(null) }

    // Menu bar commands whose dialog state lives in this pane (see AppMenuBar / MenuController).
    val menuController = koinInject<MenuController>()
    LaunchedEffect(Unit) {
        menuController.commands.collect { command ->
            when (command) {
                MenuCommand.AddFolder -> showAddFolder = true
                MenuCommand.AddTag -> showAddTag = true
                else -> {}
            }
        }
    }
    var renamingFeed by remember { mutableStateOf<Feeds?>(null) }
    var confirmingUnsubscribeFeed by remember { mutableStateOf<Feeds?>(null) }
    // Driven by the feed-list keyboard shortcuts (KeyboardNav.kt's R/F2/Enter/Delete/Backspace,
    // wired through HomeScreen): resolve the currently selected filter against this pane's own
    // already-collected rows and open the same dialogs the context menu's Rename/Edit and
    // Unsubscribe/Delete items do. request id 0 is the initial/no-op sentinel.
    LaunchedEffect(renameSelectedRequestId) {
        if (renameSelectedRequestId == 0) return@LaunchedEffect
        when (val target = filter) {
            is ArticleFilter.Feed -> feeds.find { it.id == target.feedId }?.let { renamingFeed = it }
            is ArticleFilter.Folder -> folders.find { it.id == target.folderId }?.let { editingFolder = it }
            is ArticleFilter.Tag -> tags.find { it.id == target.tagId }?.let { editingTag = it }
            else -> {}
        }
    }
    LaunchedEffect(deleteSelectedRequestId) {
        if (deleteSelectedRequestId == 0) return@LaunchedEffect
        when (val target = filter) {
            is ArticleFilter.Feed -> feeds.find { it.id == target.feedId }?.let { confirmingUnsubscribeFeed = it }
            is ArticleFilter.Folder -> folders.find { it.id == target.folderId }?.let { confirmingDeleteFolder = it }
            is ArticleFilter.Tag -> tags.find { it.id == target.tagId }?.let { confirmingDeleteTag = it }
            else -> {}
        }
    }
    val activeBoundaryState = remember { mutableStateOf<DropBoundary?>(null) }
    var activeBoundary by activeBoundaryState
    val draggedFeedIdState = remember { mutableStateOf<String?>(null) }
    var draggedFeedId by draggedFeedIdState
    val hoveredAttachTagIdState = remember { mutableStateOf<String?>(null) }
    var hoveredAttachTagId by hoveredAttachTagIdState
    val draggedFeedFolderId by remember {
        derivedStateOf {
            draggedFeedId?.let { id -> feeds.find { it.id == id }?.folder_id }
        }
    }
    // A State (not a plain val) so the permanently-remembered drag controller below always reads the
    // current index instead of the one captured when it was first created.
    val dropIndexState = remember { derivedStateOf { buildFeedListDropIndex(feeds, folders) } }
    // Read only from the auto-scroll coroutine below (never in composition), so the per-move
    // pointer reports don't trigger a recomposition of the whole pane on every drag move.
    val dragPointerYState = remember { mutableStateOf<Float?>(null) }
    // The drag host's bounds in root coordinates: the drag gesture reports positions local to that
    // host, and both the auto-scroll edge zones and the window-wide ghost need them in root space.
    val hostBoundsState = remember { mutableStateOf(Rect.Zero) }

    val listState = rememberLazyListState()

    val dragController = rememberFeedListDragController(
        vm = vm,
        listState = listState,
        hostBoundsState = hostBoundsState,
        dropIndexState = dropIndexState,
        activeBoundaryState = activeBoundaryState,
        draggedFeedIdState = draggedFeedIdState,
        hoveredAttachTagIdState = hoveredAttachTagIdState,
        dragPointerYState = dragPointerYState,
        overlay = dragOverlay,
    ) { key ->
        when (key) {
            is FeedListDragSourceKey.Feed -> feeds.find { it.id == key.feedId }?.displayTitle().orEmpty()
            is FeedListDragSourceKey.Folder -> folders.find { it.id == key.folderId }?.name.orEmpty()
        }
    }

    // Alt-Tab, an OS dialog, or anything else that takes the window's focus mid-drag: there will be
    // no further pointer events for this gesture, so drop it rather than leave a ghost floating.
    val windowFocused = LocalWindowInfo.current.isWindowFocused
    LaunchedEffect(windowFocused) {
        if (!windowFocused) dragController.cancel()
    }

    LaunchedEffect(filter, feeds.isNotEmpty(), tags.isNotEmpty(), folders.isNotEmpty()) {
        val index = feedListItemIndex(filter, feeds, folders, tags, collapsedFolderIds, feedTagMap, expandedTagIds)
            ?: return@LaunchedEffect
        listState.scrollToIndexIfNeeded(index)
    }

    val autoScrollEdgeZonePx = with(LocalDensity.current) { AUTO_SCROLL_EDGE_ZONE_DP.dp.toPx() }
    val autoScrollMaxSpeedPxPerSec = with(LocalDensity.current) { AUTO_SCROLL_MAX_SPEED_DP_PER_SEC.dp.toPx() }
    LaunchedEffect(autoScrollEdgeZonePx, autoScrollMaxSpeedPxPerSec) {
        snapshotFlow { dragPointerYState.value != null }.collectLatest { dragging ->
            if (!dragging) return@collectLatest
            while (true) {
                delay(AUTO_SCROLL_FRAME_MS)
                val pointerY = dragPointerYState.value ?: continue
                val bounds = hostBoundsState.value
                val velocity = autoScrollVelocityPxPerSec(
                    pointerY = pointerY,
                    viewportTop = bounds.top,
                    viewportBottom = bounds.bottom,
                    edgeZonePx = autoScrollEdgeZonePx,
                    maxSpeedPxPerSec = autoScrollMaxSpeedPxPerSec,
                )
                if (velocity == 0f) continue
                listState.scrollBy(velocity * AUTO_SCROLL_FRAME_MS / 1000f)
                // The pointer hasn't moved, but the rows underneath it have — without this the
                // insertion line and drop highlight would stay pinned to the row that was there
                // when the last pointer event arrived.
                dragController.refreshHover()
            }
        }
    }

    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .fillMaxSize()
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onActivated)
            .nativeContextMenu(items = { emptyList() }, onOpen = onActivated),
    ) {
        WindowDragArea(Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(top = WindowChrome.titleBarInsetDp.dp, start = 4.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Spacer(Modifier.weight(1f))
            ToolbarIconGroup {
                val addFeedTooltip = stringResource(Res.string.home_add_feed)
                TooltipIconButton(tooltip = addFeedTooltip, onClick = onAddFeedClick) {
                    KeryxIcon(KeryxIcons.Add, addFeedTooltip)
                }
                val refreshing by vm.feedRefreshing.collectAsStateSafe(false)
                val refreshTooltip = stringResource(
                    if (refreshing) Res.string.home_refreshing else Res.string.home_refresh,
                )
                TooltipIconButton(tooltip = refreshTooltip, onClick = { vm.refreshAll() }) {
                    if (refreshing) {
                        SmallSpinner()
                    } else {
                        KeryxIcon(KeryxIcons.Refresh, refreshTooltip)
                    }
                }
                if (cloudConnected) {
                    val syncing by vm.syncing.collectAsStateSafe(false)
                    val syncTooltip = stringResource(
                        if (syncing) Res.string.home_syncing else Res.string.home_sync,
                    )
                    TooltipIconButton(tooltip = syncTooltip, onClick = { vm.sync() }) {
                        if (syncing) {
                            SmallSpinner()
                        } else {
                            KeryxIcon(KeryxIcons.Cloud, syncTooltip)
                        }
                    }
                }
            }
        }
        }

        KeryxTextField(
            value = searchQuery,
            onValueChange = { vm.setSearchQuery(it) },
            singleLine = true,
            placeholder = stringResource(Res.string.home_search_placeholder),
            leadingIcon = { KeryxIcon(KeryxIcons.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    val clearLabel = stringResource(Res.string.home_search_clear)
                    TooltipIconButton(tooltip = clearLabel, size = 32.dp, onClick = { vm.setSearchQuery("") }) {
                        KeryxIcon(KeryxIcons.CloseFilled, contentDescription = clearLabel)
                    }
                }
            },
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp)
                .focusRequester(searchFocusRequester)
                .onFocusChanged { onSearchFieldFocusChange(it.isFocused) },
        )

        // Kept outside the drag-host Box below (rather than as the LazyColumn's first item): these
        // three quick filters are never valid feed drop targets (FeedListRowKey.Other), and living
        // outside the host means a drag over them can never resolve to an insertion point and a
        // press on them can never start one either — no per-row opt-out needed.
        SidebarRow(
            icon = { KeryxIcon(KeryxIcons.Article, null) },
            label = stringResource(Res.string.home_all_feeds),
            count = totalUnread,
            selected = filter == ArticleFilter.All,
            focused = focused,
            onClick = { vm.selectFilter(ArticleFilter.All); onActivated() },
        )
        SidebarRow(
            icon = { KeryxIcon(KeryxIcons.Star, null) },
            label = stringResource(Res.string.home_starred),
            count = starredUnread,
            selected = filter == ArticleFilter.Starred,
            focused = focused,
            onClick = { vm.selectFilter(ArticleFilter.Starred); onActivated() },
        )
        SidebarRow(
            icon = { KeryxIcon(KeryxIcons.Search, null) },
            label = stringResource(Res.string.home_search),
            count = searchUnread,
            selected = filter == ArticleFilter.Search,
            focused = focused,
            onClick = { vm.selectFilter(ArticleFilter.Search); vm.requestSearchFocus(); onActivated() },
        )
        HorizontalDivider(Modifier.padding(vertical = 4.dp))

        Box(Modifier.weight(1f)) {
            // The whole reorder gesture — both its source and its target resolution — lives on this
            // one Box, which is never virtualized away. It cannot live on the rows: auto-scroll
            // exists precisely so a drag can travel from the top of the list to the tags section at
            // the bottom, which scrolls the *dragged* row out of the viewport, and LazyColumn then
            // disposes that row's composition along with any pointerInput coroutine it hosted —
            // killing the gesture mid-drag. Hosting it here instead, and resolving both which row
            // was grabbed and which row is being hovered by hit-testing LazyListState.layoutInfo,
            // makes the gesture independent of row recycling entirely.
            //
            // VerticalScrollbarIfNeeded is deliberately a *sibling* rather than a child: an
            // ancestor is always in a hit-tested descendant's path, so nested inside the drag host
            // a scrollbar-thumb press would turn into a feed drag once it passed the 4dp threshold.
            Box(
                Modifier.fillMaxSize()
                    .testTag(FEED_LIST_DRAG_HOST_TEST_TAG)
                    .onGloballyPositioned {
                        hostBoundsState.value = Rect(it.positionInRoot(), it.size.toSize())
                    }
                    .feedListReorderDrag(dragController),
            ) {
                // Every slot below carries an explicit key and contentType. This list interleaves
                // several structurally different row kinds, and an unkeyed `item {}` falls back to an
                // index-derived key — so collapsing a folder or expanding a tag shifts those indices
                // and hands a slot that held, say, a folder header to a feed row. Keying them pins
                // each slot to its identity, and the contentType keeps each kind in its own reuse
                // pool so a recycled slot is only ever refilled with the same kind of row.
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    item(key = "folders-header", contentType = "section-header") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(Res.string.home_folders),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                            )
                            val addFolderTooltip = stringResource(Res.string.home_add_folder)
                            TooltipIconButton(tooltip = addFolderTooltip, onClick = { showAddFolder = true }, size = 32.dp) {
                                KeryxIcon(KeryxIcons.CreateNewFolder, addFolderTooltip, modifier = Modifier.size(20.dp))
                            }
                        }
                    }

                    /**
                     * Adds feed rows for a folder or uncategorized feed group.
                     *
                     * @param feedsInFolder The feeds to display.
                     * @param indented Whether to indent the feed rows.
                     * @param folderId The containing folder's identifier, or `null` for feeds without a folder.
                     */
                    fun LazyListScope.feedItems(feedsInFolder: List<Feeds>, indented: Boolean, folderId: String?) {
                        itemsIndexed(
                            feedsInFolder,
                            key = { _, feed -> "feed-${feed.id}" },
                            contentType = { _, _ -> "feed" },
                        ) { index, feed ->
                            FeedRow(
                                feed = feed,
                                count = unreadByFeed[feed.id] ?: 0L,
                                selected = filter == ArticleFilter.Feed(feed.id),
                                focused = focused,
                                indented = indented,
                                nextFeedId = feedsInFolder.getOrNull(index + 1)?.id,
                                folderId = folderId,
                                activeBoundaryState = activeBoundaryState,
                                onClick = { vm.selectFilter(ArticleFilter.Feed(feed.id)); onActivated() },
                                onRename = { renamingFeed = feed },
                                onRefresh = { vm.refreshFeed(feed) },
                                tags = tags,
                                attachedTagIds = feedTagMap[feed.id] ?: emptySet(),
                                onToggleFeedTag = { tagId, attached -> vm.setFeedTag(feed.id, tagId, attached) },
                                folders = folders,
                                onMoveFeedToFolder = { moveFolderId -> vm.moveFeed(feed.id, moveFolderId) },
                                onUnsubscribe = { confirmingUnsubscribeFeed = feed },
                            )
                        }
                    }

                    groupFeedsByFolder(feeds, folders).forEach { (folder, feedsInFolder) ->
                        if (folder == null) {
                            if (folders.isNotEmpty()) {
                                item(key = "no-folder-header", contentType = "folder-header") {
                                    NoFolderHeader(
                                        firstFeedId = feedsInFolder.firstOrNull()?.id,
                                        feedIdsInNoFolder = feedsInFolder.mapTo(mutableSetOf()) { it.id },
                                        activeBoundaryState = activeBoundaryState,
                                    )
                                }
                            }
                            feedItems(feedsInFolder, indented = false, folderId = null)
                        } else {
                            val collapsed = folder.id in collapsedFolderIds
                            val nextFolderId = folders.getOrNull(folders.indexOf(folder) + 1)?.id
                            item(key = "folder-${folder.id}", contentType = "folder-header") {
                                FolderGroupHeader(
                                    folder = folder,
                                    count = unreadByFolder[folder.id] ?: 0L,
                                    collapsed = collapsed,
                                    selected = filter == ArticleFilter.Folder(folder.id),
                                    focused = focused,
                                    firstFeedId = feedsInFolder.firstOrNull()?.id,
                                    nextFolderId = nextFolderId,
                                    feedIdsInFolder = feedsInFolder.mapTo(mutableSetOf()) { it.id },
                                    activeBoundaryState = activeBoundaryState,
                                    onToggleCollapse = { vm.toggleFolderCollapsed(folder.id) },
                                    onClick = { vm.selectFilter(ArticleFilter.Folder(folder.id)); onActivated() },
                                    onEdit = { editingFolder = folder },
                                    onDelete = { confirmingDeleteFolder = folder },
                                    isDragSource = folder.id == draggedFeedFolderId,
                                )
                            }
                            if (!collapsed) {
                                feedItems(feedsInFolder, indented = true, folderId = folder.id)
                            }
                        }
                    }

                    item(key = "tags-divider", contentType = "divider") {
                        HorizontalDivider(Modifier.padding(vertical = 4.dp))
                    }

                    item(key = "tags-header", contentType = "section-header") {
                        Row(
                            Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                stringResource(Res.string.home_tags),
                                style = MaterialTheme.typography.labelMedium,
                                modifier = Modifier.weight(1f),
                            )
                            val addTagTooltip = stringResource(Res.string.home_add_tag)
                            TooltipIconButton(tooltip = addTagTooltip, onClick = { showAddTag = true }, size = 32.dp) {
                                KeryxIcon(KeryxIcons.NewLabel, addTagTooltip, modifier = Modifier.size(20.dp))
                            }
                        }
                    }
                    tags.forEach { tag ->
                        item(key = "tag-${tag.id}", contentType = "tag") {
                            TagRow(
                                tag = tag,
                                count = unreadByTag[tag.id] ?: 0L,
                                expanded = tag.id in expandedTagIds,
                                selected = filter == ArticleFilter.Tag(tag.id),
                                focused = focused,
                                isDropTarget = tag.id == hoveredAttachTagId,
                                onToggleExpanded = { vm.toggleTagExpanded(tag.id) },
                                onClick = { vm.selectFilter(ArticleFilter.Tag(tag.id)); onActivated() },
                                onEdit = { editingTag = tag },
                                onDelete = { confirmingDeleteTag = tag },
                            )
                        }
                        if (tag.id in expandedTagIds) {
                            // A feed carrying several tags legitimately appears once per expanded tag (plus
                            // once under its folder), so the key must be tag-scoped to stay unique.
                            items(
                                feedsForTag(feeds, feedTagMap, tag.id),
                                key = { "tag-${tag.id}-feed-${it.id}" },
                                contentType = { "tag-feed" },
                            ) { feed ->
                                TagFeedRow(
                                    feed = feed,
                                    count = unreadByFeed[feed.id] ?: 0L,
                                    selected = filter == ArticleFilter.Feed(feed.id),
                                    focused = focused,
                                    onClick = { vm.selectFilter(ArticleFilter.Feed(feed.id)); onActivated() },
                                    onRemoveFromTag = { vm.setFeedTag(feed.id, tag.id, false) },
                                )
                            }
                        }
                    }
                }
            }
            VerticalScrollbarIfNeeded(listState)
        }
    }

    FeedListDialogs(
        vm = vm,
        tags = tags,
        folders = folders,
        showAddTag = showAddTag,
        onShowAddTagChange = { showAddTag = it },
        editingTag = editingTag,
        onEditingTagChange = { editingTag = it },
        confirmingDeleteTag = confirmingDeleteTag,
        onConfirmingDeleteTagChange = { confirmingDeleteTag = it },
        showAddFolder = showAddFolder,
        onShowAddFolderChange = { showAddFolder = it },
        editingFolder = editingFolder,
        onEditingFolderChange = { editingFolder = it },
        confirmingDeleteFolder = confirmingDeleteFolder,
        onConfirmingDeleteFolderChange = { confirmingDeleteFolder = it },
        renamingFeed = renamingFeed,
        onRenamingFeedChange = { renamingFeed = it },
        confirmingUnsubscribeFeed = confirmingUnsubscribeFeed,
        onConfirmingUnsubscribeFeedChange = { confirmingUnsubscribeFeed = it },
    )
}

/**
 * Renders a clickable sidebar row with an icon, label, optional count badge, and selection styling.
 *
 * @param icon The icon displayed in the row.
 * @param label The row label.
 * @param count The optional count shown when greater than zero.
 * @param selected Whether the row is selected.
 * @param focused Whether the sidebar is focused.
 * @param onClick The action invoked when the row is clicked.
 */
@Composable
private fun SidebarRow(
    icon: @Composable () -> Unit,
    label: String,
    count: Long?,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(selectionBackground(selected, focused))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides (selectionContentColorOrNull(selected, focused) ?: LocalContentColor.current)) {
            icon()
            Spacer(Modifier.width(12.dp))
            Text(label, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (count != null && count > 0) CountBadge(count, selected, focused)
    }
}

/**
 * Renders a tag row: selection/filter target, expand toggle for its attached-feed list, and the
 * highlight for a feed drag currently hovering it (attach, handled by the pane's single centralized
 * drag host — see `FeedListPane`'s outer `Box`).
 *
 * The drop affordance is deliberately different from a folder's: attaching a tag is additive (a feed
 * may carry many tags) whereas dropping on a folder *moves* the feed, so this row tints itself and
 * its border `tertiary`/`tertiaryContainer` — not the `secondary`/`secondaryContainer` of
 * [FolderGroupHeader] — and additionally swaps its color dot for a filled "+" badge while hovered
 * (a folder gets no such badge, since a move has no equivalent "adding" semantics). Now that the
 * drag is Compose-drawn rather than OS-level, that "attach, not move" cue *could* live on the drag
 * ghost instead — it deliberately doesn't: an affordance drawn on the target it applies to reads
 * more clearly than one riding along with the pointer, and it keeps the ghost identical no matter
 * what is underneath it.
 *
 * @param tag The tag represented by the row.
 * @param count The number of unread articles associated with the tag.
 * @param expanded Whether attached feeds are displayed.
 * @param selected Whether the tag is the active filter.
 * @param focused Whether the sidebar currently has focus.
 * @param isDropTarget Whether a dragged feed is currently hovering this tag.
 * @param onToggleExpanded Toggles the attached-feed list.
 * @param onClick Selects the tag.
 * @param onEdit Opens tag editing.
 * @param onDelete Deletes the tag.
 */
@Composable
private fun TagRow(
    tag: Tags,
    count: Long,
    expanded: Boolean,
    selected: Boolean,
    focused: Boolean,
    isDropTarget: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val editLabel = stringResource(
        Res.string.common_menu_item_with_shortcut,
        stringResource(Res.string.home_edit_tag_menu),
        renameShortcutKeyLabel(),
    )
    val deleteLabel = stringResource(
        Res.string.common_menu_item_with_shortcut,
        stringResource(Res.string.home_delete_tag_menu),
        "Delete",
    )
    val contentColor = dropTargetContentColorOrNull(isDropTarget, selected, focused, MaterialTheme.colorScheme.onTertiaryContainer)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(dropTargetBackground(isDropTarget, selected, focused, MaterialTheme.colorScheme.tertiaryContainer))
            .then(dropTargetBorderModifier(isDropTarget, MaterialTheme.colorScheme.tertiary))
            .nativeContextMenu(
                items = {
                    listOf(
                        NativeMenuItem(editLabel) { onEdit() },
                        NativeMenuItem(deleteLabel) { onDelete() },
                    )
                },
                onOpen = { if (!selected) onClick() },
            )
            .padding(start = 8.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CompositionLocalProvider(LocalContentColor provides (contentColor ?: LocalContentColor.current)) {
            KeryxIcon(
                if (expanded) KeryxIcons.ExpandMore else KeryxIcons.ChevronRight,
                contentDescription = null,
                modifier = Modifier.size(20.dp).clickable(onClick = onToggleExpanded),
            )
            Spacer(Modifier.width(4.dp))
            Row(
                Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Fixed-size slot so swapping the dot for the "+" badge never shifts the tag name.
                Box(Modifier.size(TAG_MARKER_SIZE_DP.dp), contentAlignment = Alignment.Center) {
                    if (isDropTarget) {
                        Box(
                            Modifier.size(TAG_MARKER_SIZE_DP.dp).background(MaterialTheme.colorScheme.tertiary, CircleShape),
                            contentAlignment = Alignment.Center,
                        ) {
                            KeryxIcon(
                                KeryxIcons.Add,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onTertiary,
                                modifier = Modifier.size(12.dp),
                            )
                        }
                    } else {
                        Box(Modifier.size(10.dp).background(colorFromHex(tag.color), CircleShape))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(tag.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (count > 0) CountBadge(count, selected, focused, isDropTarget, onContainerColor = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

/** Size of a [TagRow]'s leading marker slot, holding either the tag color dot or the drop "+" glyph. */
private const val TAG_MARKER_SIZE_DP = 16

/**
 * Renders a feed attached to an expanded tag.
 *
 * @param feed The attached feed.
 * @param count The number of unread articles in the feed.
 * @param selected Whether the feed is the active filter.
 * @param focused Whether the sidebar has focus.
 * @param onClick Handles feed selection.
 * @param onRemoveFromTag Detaches the feed from the tag.
 */
@Composable
private fun TagFeedRow(
    feed: Feeds,
    count: Long,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    onRemoveFromTag: () -> Unit,
) {
    val removeLabel = stringResource(Res.string.home_remove_feed_from_tag_menu)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(selectionBackground(selected, focused))
            .clickable(onClick = onClick)
            .nativeContextMenu(
                items = { listOf(NativeMenuItem(removeLabel) { onRemoveFromTag() }) },
                onOpen = { if (!selected) onClick() },
            )
            .padding(start = 36.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedAvatar(feed.displayTitle(), feed.favicon_url)
        Spacer(Modifier.width(12.dp))
        CompositionLocalProvider(LocalContentColor provides (selectionContentColorOrNull(selected, focused) ?: LocalContentColor.current)) {
            Text(feed.displayTitle(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (count > 0) CountBadge(count, selected, focused)
    }
}
