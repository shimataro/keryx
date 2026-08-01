package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
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
import works.merc.keryx.app.platform.draggedFeedId
import works.merc.keryx.app.platform.feedDragTransferData
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.resources.Res
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
import works.merc.keryx.app.ui.common.ToolbarIconGroup
import works.merc.keryx.app.ui.common.TooltipIconButton

/**
 * Renders the feed list sidebar with feed filters, folders, tags, search, and item management actions.
 *
 * @param vm The view model that provides sidebar state and handles feed, folder, and tag operations.
 * @param focused Whether the sidebar is currently focused.
 * @param onActivated Called when the sidebar becomes active.
 * @param modifier Modifier applied to the sidebar.
 * @param onAddFeedClick Called when the add-feed action is selected.
 * @param onSearchFieldFocusChange Called when the search field focus changes.
 */
/**
 * Displays the feed sidebar with filters, folders, feeds, tags, search, and management actions.
 *
 * @param vm The view model providing sidebar state and feed, folder, and tag operations.
 * @param focused Whether the sidebar currently has focus.
 * @param onActivated Called when the sidebar is activated.
 * @param modifier Modifier applied to the sidebar.
 * @param onAddFeedClick Called when the add-feed action is selected.
 * @param onSearchFieldFocusChange Called when the search field focus changes.
 */
/**
 * Displays the feed sidebar with filters, folders, feeds, tags, search, and management actions.
 *
 * @param vm The view model that provides sidebar state and handles user actions.
 * @param focused Whether the sidebar has focus.
 * @param onActivated Called when the sidebar becomes active.
 * @param onAddFeedClick Called when the add-feed action is selected.
 * @param onSearchFieldFocusChange Called when the search field focus changes.
 */
/**
 * Renders the feed navigation pane with filters, folders, tags, search, synchronization controls, and feed management actions.
 *
 * @param vm The view model that provides feed state and handles navigation and management operations.
 * @param focused Whether the pane currently has focus.
 * @param onActivated Called when the pane becomes active.
 * @param modifier Modifier applied to the pane.
 * @param onAddFeedClick Called when the user requests to add a feed.
 * @param onSearchFieldFocusChange Called when the search field focus changes.
 */
@Composable
fun FeedListPane(
    vm: HomeViewModel,
    focused: Boolean,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier,
    onAddFeedClick: () -> Unit = {},
    onSearchFieldFocusChange: (Boolean) -> Unit = {},
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
    val activeBoundaryState = remember { mutableStateOf<DropBoundary?>(null) }
    var activeBoundary by activeBoundaryState
    var draggedFeedId by remember { mutableStateOf<String?>(null) }
    val draggedFeedFolderId by remember {
        derivedStateOf {
            draggedFeedId?.let { id -> feeds.find { it.id == id }?.folder_id }
        }
    }
    val dragAndDropScope = rememberCoroutineScope()
    var pendingBoundaryClearJob by remember { mutableStateOf<Job?>(null) }
    var pendingDragSourceClearJob by remember { mutableStateOf<Job?>(null) }
    val onBoundaryChange: (DropBoundary?) -> Unit = { boundary ->
        pendingBoundaryClearJob?.cancel()
        if (boundary != null) {
            pendingBoundaryClearJob = null
            activeBoundary = boundary
        } else {
            pendingBoundaryClearJob = dragAndDropScope.launch {
                delay(BOUNDARY_CLEAR_DEBOUNCE_MS)
                activeBoundary = null
            }
        }
    }
    val onDraggedFeedIdChange: (String?) -> Unit = { feedId ->
        pendingDragSourceClearJob?.cancel()
        if (feedId != null) {
            pendingDragSourceClearJob = null
            draggedFeedId = feedId
        } else {
            pendingDragSourceClearJob = dragAndDropScope.launch {
                delay(BOUNDARY_CLEAR_DEBOUNCE_MS)
                draggedFeedId = null
            }
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(filter, feeds.isNotEmpty(), tags.isNotEmpty(), folders.isNotEmpty()) {
        val index = feedListItemIndex(filter, feeds, folders, tags, collapsedFolderIds, feedTagMap, expandedTagIds)
            ?: return@LaunchedEffect
        listState.scrollToIndexIfNeeded(index)
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
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
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
                            CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
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

        Box(Modifier.weight(1f)) {
            LazyColumn(Modifier.fillMaxSize(), state = listState) {
                item {
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
                }

                item {
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
                        TooltipIconButton(tooltip = addFolderTooltip, onClick = { showAddFolder = true }) {
                            KeryxIcon(KeryxIcons.CreateNewFolder, addFolderTooltip)
                        }
                    }
                }

                groupFeedsByFolder(feeds, folders).forEach { (folder, feedsInFolder) ->
                    if (folder == null) {
                        if (folders.isNotEmpty()) {
                            item {
                                NoFolderHeader(
                                    firstFeedId = feedsInFolder.firstOrNull()?.id,
                                    activeBoundaryState = activeBoundaryState,
                                    onBoundaryChange = onBoundaryChange,
                                    onDraggedFeedIdChange = onDraggedFeedIdChange,
                                    onDropFeed = { feedId, insertBeforeId -> vm.moveFeed(feedId, null, insertBeforeId) },
                                )
                            }
                        }
                        itemsIndexed(feedsInFolder, key = { _, feed -> "feed-${feed.id}" }) { index, feed ->
                            FeedRow(
                                feed = feed,
                                count = unreadByFeed[feed.id] ?: 0L,
                                selected = filter == ArticleFilter.Feed(feed.id),
                                focused = focused,
                                indented = false,
                                nextFeedId = feedsInFolder.getOrNull(index + 1)?.id,
                                folderId = null,
                                folderBelowBoundary = null,
                                activeBoundaryState = activeBoundaryState,
                                onBoundaryChange = onBoundaryChange,
                                onDraggedFeedIdChange = onDraggedFeedIdChange,
                                onClick = { vm.selectFilter(ArticleFilter.Feed(feed.id)); onActivated() },
                                onRename = { renamingFeed = feed },
                                onRefresh = { vm.refreshFeed(feed) },
                                tags = tags,
                                attachedTagIds = feedTagMap[feed.id] ?: emptySet(),
                                onToggleFeedTag = { tagId, attached -> vm.setFeedTag(feed.id, tagId, attached) },
                                folders = folders,
                                onMoveFeedToFolder = { folderId -> vm.moveFeed(feed.id, folderId) },
                                onUnsubscribe = { confirmingUnsubscribeFeed = feed },
                                onReorderFeed = { draggedFeedId, insertBeforeId -> vm.moveFeed(draggedFeedId, null, insertBeforeId) },
                                onReorderFolder = { draggedFolderId, insertBeforeId -> vm.reorderFolders(draggedFolderId, insertBeforeId) },
                            )
                        }
                    } else {
                        val collapsed = folder.id in collapsedFolderIds
                        val nextFolderId = folders.getOrNull(folders.indexOf(folder) + 1)?.id
                        item {
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
                                onBoundaryChange = onBoundaryChange,
                                onDraggedFeedIdChange = onDraggedFeedIdChange,
                                onToggleCollapse = { vm.toggleFolderCollapsed(folder.id) },
                                onClick = { vm.selectFilter(ArticleFilter.Folder(folder.id)); onActivated() },
                                onEdit = { editingFolder = folder },
                                onDelete = { confirmingDeleteFolder = folder },
                                onDropFeed = { feedId, insertBeforeId -> vm.moveFeed(feedId, folder.id, insertBeforeId) },
                                onReorderFolder = { draggedFolderId, insertBeforeId -> vm.reorderFolders(draggedFolderId, insertBeforeId) },
                                isDragSource = folder.id == draggedFeedFolderId,
                            )
                        }
                        if (!collapsed) {
                            itemsIndexed(feedsInFolder, key = { _, feed -> "feed-${feed.id}" }) { index, feed ->
                                FeedRow(
                                    feed = feed,
                                    count = unreadByFeed[feed.id] ?: 0L,
                                    selected = filter == ArticleFilter.Feed(feed.id),
                                    focused = focused,
                                    indented = true,
                                    nextFeedId = feedsInFolder.getOrNull(index + 1)?.id,
                                    folderId = folder.id,
                                    folderBelowBoundary = nextFolderId?.let(DropBoundary::BeforeFolder) ?: DropBoundary.AppendFolders,
                                    activeBoundaryState = activeBoundaryState,
                                    onBoundaryChange = onBoundaryChange,
                                    onDraggedFeedIdChange = onDraggedFeedIdChange,
                                    onClick = { vm.selectFilter(ArticleFilter.Feed(feed.id)); onActivated() },
                                    onRename = { renamingFeed = feed },
                                    onRefresh = { vm.refreshFeed(feed) },
                                    tags = tags,
                                    attachedTagIds = feedTagMap[feed.id] ?: emptySet(),
                                    onToggleFeedTag = { tagId, attached -> vm.setFeedTag(feed.id, tagId, attached) },
                                    folders = folders,
                                    onMoveFeedToFolder = { folderId -> vm.moveFeed(feed.id, folderId) },
                                    onUnsubscribe = { confirmingUnsubscribeFeed = feed },
                                    onReorderFeed = { draggedFeedId, insertBeforeId -> vm.moveFeed(draggedFeedId, folder.id, insertBeforeId) },
                                    onReorderFolder = { draggedFolderId, insertBeforeId -> vm.reorderFolders(draggedFolderId, insertBeforeId) },
                                )
                            }
                        }
                    }
                }

                item { HorizontalDivider(Modifier.padding(vertical = 4.dp)) }

                item {
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
                        TooltipIconButton(tooltip = addTagTooltip, onClick = { showAddTag = true }) {
                            KeryxIcon(KeryxIcons.NewLabel, addTagTooltip)
                        }
                    }
                }
                tags.forEach { tag ->
                    item(key = "tag-${tag.id}") {
                        TagRow(
                            tag = tag,
                            count = unreadByTag[tag.id] ?: 0L,
                            expanded = tag.id in expandedTagIds,
                            selected = filter == ArticleFilter.Tag(tag.id),
                            focused = focused,
                            onToggleExpanded = { vm.toggleTagExpanded(tag.id) },
                            onClick = { vm.selectFilter(ArticleFilter.Tag(tag.id)); onActivated() },
                            onEdit = { editingTag = tag },
                            onDelete = { confirmingDeleteTag = tag },
                            onAttachFeed = { feedId -> vm.setFeedTag(feedId, tag.id, true) },
                        )
                    }
                    if (tag.id in expandedTagIds) {
                        // A feed carrying several tags legitimately appears once per expanded tag (plus
                        // once under its folder), so the key must be tag-scoped to stay unique.
                        items(feedsForTag(feeds, feedTagMap, tag.id), key = { "tag-${tag.id}-feed-${it.id}" }) { feed ->
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
 * Renders a tag filter row with expansion, selection, editing, deletion, and feed attachment support.
 *
 * @param tag The tag represented by the row.
 * @param count The number of unread articles associated with the tag.
 * @param expanded Whether attached feeds are displayed.
 * @param selected Whether the tag is the active filter.
 * @param focused Whether the sidebar currently has focus.
 * @param onToggleExpanded Toggles the attached-feed list.
 * @param onClick Selects the tag.
 * @param onEdit Opens tag editing.
 * @param onDelete Deletes the tag.
 * @param onAttachFeed Attaches a dropped feed to the tag.
 */
@Composable
private fun TagRow(
    tag: Tags,
    count: Long,
    expanded: Boolean,
    selected: Boolean,
    focused: Boolean,
    onToggleExpanded: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAttachFeed: (feedId: String) -> Unit,
) {
    val editLabel = stringResource(Res.string.home_edit_tag_menu)
    val deleteLabel = stringResource(Res.string.home_delete_tag_menu)
    // A tag is an isolated attach target rather than an insertion point, so it keeps its own hover
    // state instead of joining the shared DropBoundary system used for folder/feed reordering.
    var isDropTarget by remember { mutableStateOf(false) }
    val target = remember(tag.id) {
        object : DragAndDropTarget {
            /**
             * Updates the drop-target state when a drag enters the target area.
             *
             * @param event The drag-and-drop event containing the dragged item.
             */
            override fun onEntered(event: DragAndDropEvent) {
                isDropTarget = event.draggedFeedId() != null
            }

            override fun onMoved(event: DragAndDropEvent) {
                isDropTarget = event.draggedFeedId() != null
            }

            override fun onExited(event: DragAndDropEvent) {
                isDropTarget = false
            }

            override fun onEnded(event: DragAndDropEvent) {
                isDropTarget = false
            }

            /**
             * Attaches the dropped feed to this tag.
             *
             * @return `true` if the dropped payload contains a feed, `false` otherwise.
             */
            override fun onDrop(event: DragAndDropEvent): Boolean {
                isDropTarget = false
                val feedId = event.draggedFeedId() ?: return false
                onAttachFeed(feedId)
                return true
            }
        }
    }
    val contentColor = tagDropTargetContentColorOrNull(isDropTarget, selected, focused)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(tagDropTargetBackground(isDropTarget, selected, focused))
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target)
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
                // Fixed-size slot so swapping the dot for the "+" glyph never shifts the tag name.
                Box(Modifier.size(TAG_MARKER_SIZE_DP.dp), contentAlignment = Alignment.Center) {
                    if (isDropTarget) {
                        KeryxIcon(
                            KeryxIcons.Add,
                            contentDescription = null,
                            modifier = Modifier.size(TAG_MARKER_SIZE_DP.dp),
                        )
                    } else {
                        Box(Modifier.size(10.dp).background(colorFromHex(tag.color), CircleShape))
                    }
                }
                Spacer(Modifier.width(8.dp))
                Text(tag.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (count > 0) CountBadge(count, selected, focused, isDropTarget)
    }
}

/** Size of a [TagRow]'s leading marker slot, holding either the tag color dot or the drop "+" glyph. */
private const val TAG_MARKER_SIZE_DP = 16

/** Background for a [TagRow]: `tertiaryContainer` while a feed hovers it, so the additive "attach"
 * drop reads differently from a folder's `secondaryContainer` "move" drop. */
@Composable
private fun tagDropTargetBackground(isDropTarget: Boolean, selected: Boolean, focused: Boolean): Color =
    if (isDropTarget) MaterialTheme.colorScheme.tertiaryContainer else selectionBackground(selected, focused)

/** Content color paired with [tagDropTargetBackground]. */
@Composable
private fun tagDropTargetContentColorOrNull(isDropTarget: Boolean, selected: Boolean, focused: Boolean): Color? =
    if (isDropTarget) MaterialTheme.colorScheme.onTertiaryContainer else selectionContentColorOrNull(selected, focused)

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
            .dragAndDropSource { feedDragTransferData(feed.id) }
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
