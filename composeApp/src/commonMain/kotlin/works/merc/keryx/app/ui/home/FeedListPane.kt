package works.merc.keryx.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.draganddrop.dragAndDropSource
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipAnchorPosition
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.FEED_ERROR_REASON_GONE
import works.merc.keryx.app.data.local.db.Feeds
import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.platform.NativeCheckMenuItem
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.NativeSubMenu
import works.merc.keryx.app.platform.VerticalScrollbarIfNeeded
import works.merc.keryx.app.platform.WindowChrome
import works.merc.keryx.app.platform.WindowDragArea
import works.merc.keryx.app.platform.draggedFeedId
import works.merc.keryx.app.platform.draggedFolderId
import works.merc.keryx.app.platform.feedDragTransferData
import works.merc.keryx.app.platform.folderDragTransferData
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.platform.positionYInRoot
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.common_delete
import works.merc.keryx.app.resources.home_add_feed
import works.merc.keryx.app.resources.home_add_folder
import works.merc.keryx.app.resources.home_add_tag
import works.merc.keryx.app.resources.home_all_feeds
import works.merc.keryx.app.resources.home_assign_tags
import works.merc.keryx.app.resources.home_delete_folder_confirm
import works.merc.keryx.app.resources.home_delete_folder_menu
import works.merc.keryx.app.resources.home_delete_tag_confirm
import works.merc.keryx.app.resources.home_delete_tag_menu
import works.merc.keryx.app.resources.home_edit_folder
import works.merc.keryx.app.resources.home_edit_folder_menu
import works.merc.keryx.app.resources.home_edit_tag
import works.merc.keryx.app.resources.home_edit_tag_menu
import works.merc.keryx.app.resources.home_feed_gone
import works.merc.keryx.app.resources.home_folders
import works.merc.keryx.app.resources.home_move_to_folder
import works.merc.keryx.app.resources.home_new_folder_hint
import works.merc.keryx.app.resources.home_new_tag_hint
import works.merc.keryx.app.resources.home_no_folder
import works.merc.keryx.app.resources.home_folder_name_duplicate
import works.merc.keryx.app.resources.home_refresh
import works.merc.keryx.app.resources.home_refreshing
import works.merc.keryx.app.resources.home_rename_feed
import works.merc.keryx.app.resources.home_rename_feed_hint
import works.merc.keryx.app.resources.home_search
import works.merc.keryx.app.resources.home_search_clear
import works.merc.keryx.app.resources.home_search_placeholder
import works.merc.keryx.app.resources.home_starred
import works.merc.keryx.app.resources.home_sync
import works.merc.keryx.app.resources.home_syncing
import works.merc.keryx.app.resources.home_tag_name_duplicate
import works.merc.keryx.app.resources.home_tags
import works.merc.keryx.app.resources.home_unsubscribe_body
import works.merc.keryx.app.resources.home_unsubscribe_menu
import works.merc.keryx.app.resources.home_unsubscribe_title
import works.merc.keryx.app.ui.common.FlatTooltipContent
import works.merc.keryx.app.ui.common.KeryxAlertDialog
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
    val totalUnread by vm.totalUnread.collectAsStateSafe(0L)
    val starredUnread by vm.starredUnreadCount.collectAsStateSafe(0L)
    val searchUnread by vm.searchUnreadCount.collectAsStateSafe(0L)
    val filter by vm.filter.collectAsStateSafe(ArticleFilter.All)
    val searchQuery by vm.searchQuery.collectAsStateSafe("")
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
    val boundaryClearScope = rememberCoroutineScope()
    var pendingBoundaryClearJob by remember { mutableStateOf<Job?>(null) }
    val onBoundaryChange: (DropBoundary?) -> Unit = { boundary ->
        pendingBoundaryClearJob?.cancel()
        if (boundary != null) {
            pendingBoundaryClearJob = null
            activeBoundary = boundary
        } else {
            pendingBoundaryClearJob = boundaryClearScope.launch {
                delay(BOUNDARY_CLEAR_DEBOUNCE_MS)
                activeBoundary = null
            }
        }
    }

    val listState = rememberLazyListState()

    LaunchedEffect(filter, feeds.isNotEmpty(), tags.isNotEmpty(), folders.isNotEmpty()) {
        val index = feedListItemIndex(filter, feeds, folders, tags, collapsedFolderIds) ?: return@LaunchedEffect
        val layoutInfo = listState.layoutInfo
        val itemInfo = layoutInfo.visibleItemsInfo.find { it.index == index }

        if (itemInfo == null) {
            listState.animateScrollToItem(index)
        } else {
            val viewportStart = layoutInfo.viewportStartOffset
            val viewportEnd = layoutInfo.viewportEndOffset
            val itemStart = itemInfo.offset
            val itemEnd = itemInfo.offset + itemInfo.size

            when {
                itemStart < viewportStart -> listState.animateScrollBy((itemStart - viewportStart).toFloat())
                itemEnd > viewportEnd -> listState.animateScrollBy((itemEnd - viewportEnd).toFloat())
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
                        CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    } else {
                        KeryxIcon(KeryxIcons.Refresh, refreshTooltip)
                    }
                }
                if (vm.cloudConnected) {
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
                                    onDrop = { feedId, insertBeforeId -> vm.moveFeed(feedId, null, insertBeforeId) },
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
                                onToggleCollapse = { vm.toggleFolderCollapsed(folder.id) },
                                onClick = { vm.selectFilter(ArticleFilter.Folder(folder.id)); onActivated() },
                                onEdit = { editingFolder = folder },
                                onDelete = { confirmingDeleteFolder = folder },
                                onDropFeed = { feedId, insertBeforeId -> vm.moveFeed(feedId, folder.id, insertBeforeId) },
                                onReorderFolder = { draggedFolderId, insertBeforeId -> vm.reorderFolders(draggedFolderId, insertBeforeId) },
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
                items(tags, key = { "tag-${it.id}" }) { tag ->
                    TagRow(
                        tag = tag,
                        count = unreadByTag[tag.id] ?: 0L,
                        selected = filter == ArticleFilter.Tag(tag.id),
                        focused = focused,
                        onClick = { vm.selectFilter(ArticleFilter.Tag(tag.id)); onActivated() },
                        onEdit = { editingTag = tag },
                        onDelete = { confirmingDeleteTag = tag },
                    )
                }
            }
            VerticalScrollbarIfNeeded(listState)
        }
    }

    if (showAddTag) {
        val duplicateError = stringResource(Res.string.home_tag_name_duplicate)
        var color by remember { mutableStateOf<String?>(null) }
        TextPromptDialog(
            title = stringResource(Res.string.home_add_tag),
            hint = stringResource(Res.string.home_new_tag_hint),
            initial = "",
            blockingError = { name -> if (tags.any { it.name == name }) duplicateError else null },
            extraContent = { TagColorPicker(selected = color, onSelect = { color = it }) },
            onConfirm = { vm.createTag(it, color); showAddTag = false },
            onDismiss = { showAddTag = false },
        )
    }
    editingTag?.let { tag ->
        val duplicateError = stringResource(Res.string.home_tag_name_duplicate)
        var color by remember { mutableStateOf(tag.color) }
        TextPromptDialog(
            title = stringResource(Res.string.home_edit_tag_menu),
            hint = stringResource(Res.string.home_new_tag_hint),
            initial = tag.name,
            blockingError = { name -> if (tags.any { it.id != tag.id && it.deleted_at == null && it.name == name }) duplicateError else null },
            extraContent = { TagColorPicker(selected = color, onSelect = { color = it }) },
            onConfirm = { vm.updateTag(tag.id, it, color); editingTag = null },
            onDismiss = { editingTag = null },
        )
    }
    renamingFeed?.let { feed ->
        val resetHint = stringResource(Res.string.home_rename_feed_hint)
        TextPromptDialog(
            title = stringResource(Res.string.home_rename_feed),
            hint = feed.title,
            initial = feed.custom_title ?: feed.title,
            allowBlank = true,
            infoHint = { name -> if (name.isEmpty()) resetHint else null },
            onConfirm = { vm.renameFeed(feed.id, it); renamingFeed = null },
            onDismiss = { renamingFeed = null },
        )
    }
    confirmingDeleteTag?.let { tag ->
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { confirmingDeleteTag = null },
            title = stringResource(Res.string.home_delete_tag_menu),
            text = { Text(stringResource(Res.string.home_delete_tag_confirm, tag.name)) },
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = { vm.deleteTag(tag.id); confirmingDeleteTag = null },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
    if (showAddFolder) {
        val duplicateError = stringResource(Res.string.home_folder_name_duplicate)
        TextPromptDialog(
            title = stringResource(Res.string.home_add_folder),
            hint = stringResource(Res.string.home_new_folder_hint),
            initial = "",
            blockingError = { name -> if (folders.any { it.name == name }) duplicateError else null },
            onConfirm = { vm.createFolder(it); showAddFolder = false },
            onDismiss = { showAddFolder = false },
        )
    }
    editingFolder?.let { folder ->
        val duplicateError = stringResource(Res.string.home_folder_name_duplicate)
        TextPromptDialog(
            title = stringResource(Res.string.home_edit_folder),
            hint = stringResource(Res.string.home_new_folder_hint),
            initial = folder.name,
            blockingError = { name ->
                if (folders.any { it.id != folder.id && it.name == name }) duplicateError else null
            },
            onConfirm = { vm.updateFolder(folder.id, it); editingFolder = null },
            onDismiss = { editingFolder = null },
        )
    }
    confirmingDeleteFolder?.let { folder ->
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { confirmingDeleteFolder = null },
            title = stringResource(Res.string.home_delete_folder_menu),
            text = { Text(stringResource(Res.string.home_delete_folder_confirm, folder.name)) },
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = { vm.deleteFolder(folder.id); confirmingDeleteFolder = null },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
    confirmingUnsubscribeFeed?.let { feed ->
        val displayName = feed.displayTitle()
        KeryxAlertDialog(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            tonalElevation = 0.dp,
            onDismissRequest = { confirmingUnsubscribeFeed = null },
            title = stringResource(Res.string.home_unsubscribe_title, displayName),
            text = { Text(stringResource(Res.string.home_unsubscribe_body)) },
            confirmText = stringResource(Res.string.common_delete),
            onConfirm = { vm.unsubscribeFeed(feed.id); confirmingUnsubscribeFeed = null },
            dismissText = stringResource(Res.string.common_cancel),
        )
    }
}

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

@Composable
private fun TagRow(
    tag: Tags,
    count: Long,
    selected: Boolean,
    focused: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val editLabel = stringResource(Res.string.home_edit_tag_menu)
    val deleteLabel = stringResource(Res.string.home_delete_tag_menu)
    Row(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp, vertical = 2.dp)
            .clip(MaterialTheme.shapes.small)
            .background(selectionBackground(selected, focused))
            .clickable(onClick = onClick)
            .nativeContextMenu(
                items = {
                    listOf(
                        NativeMenuItem(editLabel) { onEdit() },
                        NativeMenuItem(deleteLabel) { onDelete() },
                    )
                },
                onOpen = { if (!selected) onClick() },
            )
            .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(colorFromHex(tag.color), CircleShape))
        Spacer(Modifier.width(12.dp))
        CompositionLocalProvider(LocalContentColor provides (selectionContentColorOrNull(selected, focused) ?: LocalContentColor.current)) {
            Text(tag.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (count > 0) CountBadge(count, selected, focused)
    }
}

/** Background for a folder-drop row: highlighted while a feed is being dragged over it. */
@Composable
private fun dropTargetBackground(isDropTarget: Boolean, selected: Boolean, focused: Boolean): Color =
    if (isDropTarget) MaterialTheme.colorScheme.primary.copy(alpha = 0.25f) else selectionBackground(selected, focused)

/** Which half of a row/header a drag is currently hovering over, used to render an insertion line
 * exactly at the boundary the drop would land on. */
private enum class RowHalf { TOP, BOTTOM }

/** How long to hold off clearing the active drop boundary to `null`, so a momentary "no target
 * matched" hit-test gap while crossing between adjacent rows/headers doesn't flicker the
 * indicator/highlight off before the next row's `onMoved` sets it again. */
private const val BOUNDARY_CLEAR_DEBOUNCE_MS = 50L

/**
 * A single drop-and-reorder insertion point, shared (lifted) across all rows/headers in the pane
 * so that hovering the bottom half of one item and the top half of the next item — which are the
 * same logical boundary — light up exactly one indicator rather than two independent ones.
 */
private sealed interface DropBoundary {
    data class BeforeFeed(val feedId: String) : DropBoundary
    data class AppendFeeds(val folderId: String?) : DropBoundary
    data class BeforeFolder(val folderId: String) : DropBoundary
    data object AppendFolders : DropBoundary
}

/**
 * Resolves which half of [coordinates] [event]'s pointer is currently over. Falls back to BOTTOM
 * if the row's layout position isn't known yet (e.g. the very first callback after entering).
 */
private fun resolveHalf(event: DragAndDropEvent, coordinates: LayoutCoordinates?): RowHalf {
    val c = coordinates ?: return RowHalf.BOTTOM
    val localY = event.positionYInRoot() - c.positionInRoot().y
    return if (localY < c.size.height / 2f) RowHalf.TOP else RowHalf.BOTTOM
}

/**
 * A thin horizontal line marking a drag-and-drop insertion point (macOS "Notes"-style), aligned
 * to the same left indent as the row content it's next to — the indent communicates which group
 * (folder vs "no folder") the item will land in.
 */
@Composable
private fun InsertionLine(indented: Boolean, visible: Boolean) {
    Box(
        Modifier.fillMaxWidth()
            .padding(horizontal = 8.dp)
            .padding(start = if (indented) 36.dp else 0.dp)
            .height(2.dp)
            .background(if (visible) MaterialTheme.colorScheme.primary else Color.Transparent),
    )
}

@Composable
private fun FolderGroupHeader(
    folder: Folders,
    count: Long,
    collapsed: Boolean,
    selected: Boolean,
    focused: Boolean,
    firstFeedId: String?,
    nextFolderId: String?,
    feedIdsInFolder: Set<String>,
    activeBoundaryState: State<DropBoundary?>,
    onBoundaryChange: (DropBoundary?) -> Unit,
    onToggleCollapse: () -> Unit,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onDropFeed: (feedId: String, insertBeforeId: String?) -> Unit,
    onReorderFolder: (draggedFolderId: String, insertBeforeId: String?) -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val editFolderLabel = stringResource(Res.string.home_edit_folder_menu)
    val deleteFolderLabel = stringResource(Res.string.home_delete_folder_menu)
    val isEmpty = firstFeedId == null
    val feedZoneBoundary = if (isEmpty) DropBoundary.AppendFeeds(folder.id) else firstFeedId.let(DropBoundary::BeforeFeed)
    val belowFolderBoundary = nextFolderId?.let(DropBoundary::BeforeFolder) ?: DropBoundary.AppendFolders
    val isFeedDragHighlight = when (val boundary = activeBoundaryState.value) {
        is DropBoundary.BeforeFeed -> boundary.feedId in feedIdsInFolder
        is DropBoundary.AppendFeeds -> boundary.folderId == folder.id
        else -> false
    }

    val target = remember(folder.id, feedZoneBoundary, belowFolderBoundary) {
        object : DragAndDropTarget {
            override fun onMoved(event: DragAndDropEvent) {
                when {
                    event.draggedFeedId() != null -> onBoundaryChange(feedZoneBoundary)
                    event.draggedFolderId() == folder.id -> Unit
                    event.draggedFolderId() != null -> {
                        val half = resolveHalf(event, coordinates)
                        onBoundaryChange(if (half == RowHalf.TOP) DropBoundary.BeforeFolder(folder.id) else belowFolderBoundary)
                    }
                }
            }

            override fun onExited(event: DragAndDropEvent) {
                val boundary = activeBoundaryState.value
                if (boundary == DropBoundary.BeforeFolder(folder.id) ||
                    boundary == belowFolderBoundary ||
                    boundary == feedZoneBoundary
                ) {
                    onBoundaryChange(null)
                }
            }

            override fun onDrop(event: DragAndDropEvent): Boolean {
                onBoundaryChange(null)
                event.draggedFeedId()?.let { feedId ->
                    onDropFeed(feedId, if (isEmpty) null else firstFeedId)
                    return true
                }
                val draggedFolderId = event.draggedFolderId() ?: return false
                if (draggedFolderId == folder.id) return false
                val half = resolveHalf(event, coordinates)
                val insertBeforeId = if (half == RowHalf.TOP) folder.id else nextFolderId
                onReorderFolder(draggedFolderId, insertBeforeId)
                return true
            }
        }
    }

    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { coordinates = it }
            .dragAndDropTarget(shouldStartDragAndDrop = { true }, target = target),
    ) {
        InsertionLine(indented = false, visible = activeBoundaryState.value == DropBoundary.BeforeFolder(folder.id))
        Row(
            Modifier.fillMaxWidth()
                .padding(start = 8.dp, top = 2.dp, end = 8.dp, bottom = 2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(dropTargetBackground(isFeedDragHighlight, selected, focused))
                .dragAndDropSource { folderDragTransferData(folder.id) }
                .nativeContextMenu(
                    items = {
                        listOf(
                            NativeMenuItem(editFolderLabel) { onEdit() },
                            NativeMenuItem(deleteFolderLabel) { onDelete() },
                        )
                    },
                    onOpen = { if (!selected) onClick() },
                )
                .padding(end = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            CompositionLocalProvider(LocalContentColor provides (selectionContentColorOrNull(selected, focused) ?: LocalContentColor.current)) {
                KeryxIcon(
                    if (collapsed) KeryxIcons.ChevronRight else KeryxIcons.ExpandMore,
                    contentDescription = null,
                    modifier = Modifier.size(20.dp).clickable(onClick = onToggleCollapse),
                )
                Spacer(Modifier.width(4.dp))
                Row(
                    Modifier.weight(1f).clickable(onClick = onClick).padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    KeryxIcon(
                        KeryxIcons.Folder,
                        contentDescription = null,
                        tint = selectionContentColorOrNull(selected, focused) ?: MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(folder.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (count > 0) CountBadge(count, selected, focused)
        }
        if (collapsed || isEmpty) {
            InsertionLine(indented = true, visible = activeBoundaryState.value == feedZoneBoundary)
        }
        if (nextFolderId == null) {
            InsertionLine(indented = false, visible = activeBoundaryState.value == DropBoundary.AppendFolders)
        }
    }
}

/** The "no folder" section label — also a drop target for feeds dropped into (or reordered
 * within) the "no folder" group. */
@Composable
private fun NoFolderHeader(
    firstFeedId: String?,
    activeBoundaryState: State<DropBoundary?>,
    onBoundaryChange: (DropBoundary?) -> Unit,
    onDrop: (feedId: String, insertBeforeId: String?) -> Unit,
) {
    val isEmpty = firstFeedId == null
    val feedZoneBoundary = if (isEmpty) DropBoundary.AppendFeeds(null) else firstFeedId.let(DropBoundary::BeforeFeed)
    Column(
        Modifier.fillMaxWidth()
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = remember(feedZoneBoundary) {
                    object : DragAndDropTarget {
                        override fun onEntered(event: DragAndDropEvent) {
                            if (event.draggedFeedId() != null) onBoundaryChange(feedZoneBoundary)
                        }

                        override fun onMoved(event: DragAndDropEvent) {
                            if (event.draggedFeedId() != null) onBoundaryChange(feedZoneBoundary)
                        }

                        override fun onExited(event: DragAndDropEvent) {
                            if (activeBoundaryState.value == feedZoneBoundary) onBoundaryChange(null)
                        }

                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            onBoundaryChange(null)
                            val feedId = event.draggedFeedId() ?: return false
                            onDrop(feedId, if (isEmpty) null else firstFeedId)
                            return true
                        }
                    }
                },
            ),
    ) {
        Text(
            stringResource(Res.string.home_no_folder),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(dropTargetBackground(activeBoundaryState.value == feedZoneBoundary, selected = false, focused = false))
                .padding(start = 8.dp, top = 8.dp, bottom = 4.dp),
        )
        if (isEmpty) InsertionLine(indented = false, visible = activeBoundaryState.value == feedZoneBoundary)
    }
}

/**
 * Displays a feed row with selection styling, feed actions, unread count, and drag-and-drop support.
 *
 * @param feed The feed represented by the row.
 * @param count The number of unread articles.
 * @param indented Whether to indent the row within a folder.
 * @param nextFeedId The ID of the following feed, or `null` when this is the last feed.
 * @param folderId The containing folder's ID, or `null` for feeds without a folder.
 * @param folderBelowBoundary The drop boundary used when a folder is dragged below this row.
 */
@Composable
private fun FeedRow(
    feed: Feeds,
    count: Long,
    selected: Boolean,
    focused: Boolean,
    indented: Boolean,
    nextFeedId: String?,
    folderId: String?,
    folderBelowBoundary: DropBoundary?,
    activeBoundaryState: State<DropBoundary?>,
    onBoundaryChange: (DropBoundary?) -> Unit,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onRefresh: () -> Unit,
    tags: List<Tags>,
    attachedTagIds: Set<String>,
    onToggleFeedTag: (tagId: String, attached: Boolean) -> Unit,
    folders: List<Folders>,
    onMoveFeedToFolder: (folderId: String?) -> Unit,
    onUnsubscribe: () -> Unit,
    onReorderFeed: (draggedFeedId: String, insertBeforeId: String?) -> Unit,
    onReorderFolder: (draggedFolderId: String, insertBeforeId: String?) -> Unit,
) {
    var coordinates by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val refreshLabel = stringResource(Res.string.home_refresh)
    val assignTagsLabel = stringResource(Res.string.home_assign_tags)
    val renameFeedLabel = stringResource(Res.string.home_rename_feed)
    val moveToFolderLabel = stringResource(Res.string.home_move_to_folder)
    val noFolderLabel = stringResource(Res.string.home_no_folder)
    val unsubscribeLabel = stringResource(Res.string.home_unsubscribe_menu)
    val belowBoundary = nextFeedId?.let(DropBoundary::BeforeFeed) ?: DropBoundary.AppendFeeds(folderId)

    Column(
        Modifier.fillMaxWidth()
            .onGloballyPositioned { coordinates = it }
            .dragAndDropTarget(
                shouldStartDragAndDrop = { true },
                target = remember(feed.id, belowBoundary, folderBelowBoundary) {
                    object : DragAndDropTarget {
                        override fun onMoved(event: DragAndDropEvent) {
                            if (event.draggedFeedId() != null) {
                                val half = resolveHalf(event, coordinates)
                                onBoundaryChange(if (half == RowHalf.TOP) DropBoundary.BeforeFeed(feed.id) else belowBoundary)
                            } else if (folderBelowBoundary != null && event.draggedFolderId() != null) {
                                onBoundaryChange(folderBelowBoundary)
                            }
                        }

                        override fun onExited(event: DragAndDropEvent) {
                            val boundary = activeBoundaryState.value
                            if (boundary == DropBoundary.BeforeFeed(feed.id) ||
                                boundary == belowBoundary ||
                                (folderBelowBoundary != null && boundary == folderBelowBoundary)
                            ) {
                                onBoundaryChange(null)
                            }
                        }

                        override fun onDrop(event: DragAndDropEvent): Boolean {
                            onBoundaryChange(null)
                            event.draggedFeedId()?.let { draggedFeedId ->
                                val half = resolveHalf(event, coordinates)
                                val insertBeforeId = if (half == RowHalf.TOP) feed.id else nextFeedId
                                onReorderFeed(draggedFeedId, insertBeforeId)
                                return true
                            }
                            if (folderBelowBoundary == null) return false
                            val draggedFolderId = event.draggedFolderId() ?: return false
                            onReorderFolder(draggedFolderId, (folderBelowBoundary as? DropBoundary.BeforeFolder)?.folderId)
                            return true
                        }
                    }
                },
            ),
    ) {
        InsertionLine(indented, visible = activeBoundaryState.value == DropBoundary.BeforeFeed(feed.id))
        Row(
            Modifier.fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 2.dp)
                .clip(MaterialTheme.shapes.small)
                .background(selectionBackground(selected, focused))
                .clickable(onClick = onClick)
                .dragAndDropSource { feedDragTransferData(feed.id) }
                .nativeContextMenu(
                    items = {
                        listOf(
                            NativeMenuItem(refreshLabel) { onRefresh() },
                            NativeSubMenu(
                                label = assignTagsLabel,
                                items = tags.map { tag ->
                                    NativeCheckMenuItem(tag.name, checked = tag.id in attachedTagIds) {
                                        onToggleFeedTag(tag.id, tag.id !in attachedTagIds)
                                    }
                                },
                            ),
                            NativeSubMenu(
                                label = moveToFolderLabel,
                                items = buildList {
                                    add(
                                        NativeCheckMenuItem(noFolderLabel, checked = feed.folder_id == null) {
                                            onMoveFeedToFolder(null)
                                        },
                                    )
                                    folders.forEach { folder ->
                                        add(
                                            NativeCheckMenuItem(folder.name, checked = feed.folder_id == folder.id) {
                                                onMoveFeedToFolder(folder.id)
                                            },
                                        )
                                    }
                                },
                            ),
                            NativeMenuItem(renameFeedLabel) { onRename() },
                            NativeMenuItem(unsubscribeLabel) { onUnsubscribe() },
                        )
                    },
                    onOpen = { if (!selected) onClick() },
                )
                .padding(start = if (indented) 36.dp else 8.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            FeedAvatar(feed.displayTitle(), feed.favicon_url)
            Spacer(Modifier.width(12.dp))
            CompositionLocalProvider(LocalContentColor provides (selectionContentColorOrNull(selected, focused) ?: LocalContentColor.current)) {
                Text(
                    feed.displayTitle(),
                    Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            // A 410-Gone feed deliberately keeps error_count at 0 (it is permanent, not a retry
            // candidate), so it is recognized by its last_error marker instead — otherwise a
            // disappeared feed would look completely normal here once its notification is dismissed.
            val gone = feed.last_error == FEED_ERROR_REASON_GONE
            if (feed.error_count > 0 || gone) {
                FeedErrorIndicator(gone)
                Spacer(Modifier.width(4.dp))
            }
            if (count > 0) CountBadge(count, selected, focused)
        }
        if (nextFeedId == null) {
            InsertionLine(indented, visible = activeBoundaryState.value == belowBoundary)
        }
    }
}

/**
 * Displays an error indicator for a feed, with an explanatory tooltip when the feed is gone.
 *
 * @param gone Whether the feed responded with HTTP 410 Gone.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FeedErrorIndicator(gone: Boolean) {
    val icon = @Composable {
        KeryxIcon(
            KeryxIcons.ErrorFilled,
            contentDescription = if (gone) stringResource(Res.string.home_feed_gone) else null,
            tint = MaterialTheme.colorScheme.error,
            modifier = Modifier.size(16.dp),
        )
    }
    if (!gone) {
        icon()
        return
    }
    TooltipBox(
        positionProvider = TooltipDefaults.rememberTooltipPositionProvider(TooltipAnchorPosition.Above),
        tooltip = { FlatTooltipContent(stringResource(Res.string.home_feed_gone)) },
        state = rememberTooltipState(),
    ) {
        icon()
    }
}
