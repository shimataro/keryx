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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
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
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.platform.NativeMenuItem
import works.merc.keryx.app.platform.NativeMenuSeparator
import works.merc.keryx.app.platform.VerticalScrollbarIfNeeded
import works.merc.keryx.app.platform.WindowChrome
import works.merc.keryx.app.platform.WindowDragArea
import works.merc.keryx.app.platform.hasNativeAppMenu
import works.merc.keryx.app.platform.nativeContextMenu
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.home_add_feed
import works.merc.keryx.app.resources.home_add_folder
import works.merc.keryx.app.resources.home_add_tag
import works.merc.keryx.app.resources.home_all_feeds
import works.merc.keryx.app.resources.home_copy_feed_url
import works.merc.keryx.app.resources.home_copy_site_url
import works.merc.keryx.app.resources.home_delete_tag_menu
import works.merc.keryx.app.resources.home_edit_tag_menu
import works.merc.keryx.app.resources.home_folder_name_duplicate
import works.merc.keryx.app.resources.home_folders
import works.merc.keryx.app.resources.home_open_site
import works.merc.keryx.app.resources.home_refresh
import works.merc.keryx.app.resources.home_refreshing
import works.merc.keryx.app.resources.home_remove_feed_from_tag_menu
import works.merc.keryx.app.resources.home_rename_feed
import works.merc.keryx.app.resources.home_search
import works.merc.keryx.app.resources.home_search_clear
import works.merc.keryx.app.resources.home_search_placeholder
import works.merc.keryx.app.resources.home_starred
import works.merc.keryx.app.resources.home_sync
import works.merc.keryx.app.resources.home_syncing
import works.merc.keryx.app.resources.home_tag_color
import works.merc.keryx.app.resources.home_tag_name_duplicate
import works.merc.keryx.app.resources.home_tags
import works.merc.keryx.app.resources.menu_settings
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.KeryxPaneTopBar
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
 * @param onTextInputFocusChange Called when this pane starts or stops holding text-entry focus —
 *   the search field or a row's inline name editor. Bare-key shortcuts (J/K, arrows, F2, Delete)
 *   must stand aside for both, so they report through one channel.
 * @param renameSelectedRequestId Bumped by the keyboard rename/edit shortcut (F2/Return); on change,
 *   starts inline name editing on whichever feed/folder/tag the current filter selects.
 * @param deleteSelectedRequestId Bumped by the keyboard delete shortcut (Delete/Backspace); on
 *   change, opens the unsubscribe/delete confirmation for whichever feed/folder/tag the current
 *   filter selects.
 * @param onSelectionAdvance Called after a filter selection (a quick filter, feed, folder, or
 *   tag row), in addition to [onActivated] — see `HomeScreen`'s pane-layout wiring. No-op at
 *   [PaneLayout.Triple], where every pane is already visible and there is nowhere to advance to.
 * @param isTouchPrimary Overridable for tests only — see `feedListReorderDrag`'s own KDoc.
 */
@Composable
internal fun FeedListPane(
    vm: HomeViewModel,
    focused: Boolean,
    dragOverlay: FeedDragOverlayState,
    onActivated: () -> Unit,
    modifier: Modifier = Modifier,
    onAddFeedClick: () -> Unit = {},
    onTextInputFocusChange: (Boolean) -> Unit = {},
    renameSelectedRequestId: Int = 0,
    deleteSelectedRequestId: Int = 0,
    onSelectionAdvance: () -> Unit = {},
    isTouchPrimary: Boolean = works.merc.keryx.app.platform.isTouchPrimary,
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
    val selectedRowInstance by vm.selectedRowInstance.collectAsStateSafe(FeedListRowSelection.All)
    val searchQuery by vm.searchQuery.collectAsStateSafe("")
    val cloudConnected by vm.cloudConnected.collectAsStateSafe(false)
    val searchFocusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        vm.searchFocusRequests.collect { searchFocusRequester.requestFocus() }
    }
    // Shared by every feed row's "copy feed URL"/"copy site URL" context-menu item, mirroring
    // ArticleListPane's rememberCopyUrlAction() for article rows.
    val copyUrl = rememberCopyUrlAction()

    var showAddTag by remember { mutableStateOf(false) }
    var confirmingDeleteTag by remember { mutableStateOf<Tags?>(null) }
    var showAddFolder by remember { mutableStateOf(false) }
    var confirmingDeleteFolder by remember { mutableStateOf<Folders?>(null) }
    var confirmingUnsubscribeFeed by remember { mutableStateOf<Feeds?>(null) }
    // The single row (if any) whose name is currently being edited in place. Renaming a feed,
    // folder, or tag happens in the row itself rather than in a dialog — see InlineRename.kt.
    var inlineEdit by remember { mutableStateOf<InlineEditTarget?>(null) }
    var searchFieldFocused by remember { mutableStateOf(false) }
    val folderNameDuplicateError = stringResource(Res.string.home_folder_name_duplicate)
    val tagNameDuplicateError = stringResource(Res.string.home_tag_name_duplicate)

    // Typed characters must reach an open editor rather than the root's bare-key shortcuts, and the
    // menu bar's own F2/Delete accelerators must stand down too (see MenuController).
    LaunchedEffect(searchFieldFocused, inlineEdit != null) {
        onTextInputFocusChange(searchFieldFocused || inlineEdit != null)
    }

    // Shared by the keyboard shortcuts (via the request-id effects below) and the Feed menu bar
    // items (via MenuCommand.RenameFeed/UnsubscribeFeed): resolve the currently selected filter
    // against this pane's own already-collected rows and start the same inline edit (or open the
    // same confirmation dialog) the context menu's Rename/Edit and Unsubscribe/Delete items do.
    fun startInlineRenameForSelection() {
        inlineEdit = resolveFeedListSelectionTarget(filter, feeds, folders, tags)
            ?.toInlineEditTarget(selectedRowInstance)
    }
    fun openDeleteDialogForSelection() {
        when (val target = resolveFeedListSelectionTarget(filter, feeds, folders, tags)) {
            is FeedListSelectionTarget.Feed -> confirmingUnsubscribeFeed = target.feed
            is FeedListSelectionTarget.Folder -> confirmingDeleteFolder = target.folder
            is FeedListSelectionTarget.Tag -> confirmingDeleteTag = target.tag
            null -> {}
        }
    }

    // A feed renders once under its folder group and again under every expanded tag it carries, so
    // "selected" isn't one row: the instance the user actually clicked/navigated to is PRIMARY, and
    // every other rendered copy of the same selection is a dimmer SECONDARY echo.
    fun toneFor(instance: FeedListRowSelection): RowSelectionTone = when {
        selectedRowInstance == instance -> RowSelectionTone.PRIMARY
        filter == instance.filter -> RowSelectionTone.SECONDARY
        else -> RowSelectionTone.NONE
    }

    // Menu bar commands whose dialog state lives in this pane (see AppMenuBar / MenuController).
    val menuController = koinInject<MenuController>()
    LaunchedEffect(Unit) {
        menuController.commands.collect { command ->
            when (command) {
                MenuCommand.AddFolder -> showAddFolder = true
                MenuCommand.AddTag -> showAddTag = true
                MenuCommand.RenameFeed -> startInlineRenameForSelection()
                MenuCommand.UnsubscribeFeed -> openDeleteDialogForSelection()
                else -> {}
            }
        }
    }
    // Driven by the feed-list keyboard shortcuts (KeyboardNav.kt's F2/Enter/Delete/Backspace,
    // wired through HomeScreen). request id 0 is the initial/no-op sentinel.
    LaunchedEffect(renameSelectedRequestId) {
        if (renameSelectedRequestId == 0) return@LaunchedEffect
        startInlineRenameForSelection()
    }
    LaunchedEffect(deleteSelectedRequestId) {
        if (deleteSelectedRequestId == 0) return@LaunchedEffect
        openDeleteDialogForSelection()
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

    // Scrolls to exactly the row instance that was selected — the tag-nested copy when that's the
    // one clicked/navigated to, not whichever copy of the same feed happens to already be visible.
    LaunchedEffect(selectedRowInstance, feeds.isNotEmpty(), tags.isNotEmpty(), folders.isNotEmpty()) {
        val index = feedListRowIndex(selectedRowInstance, feeds, folders, tags, collapsedFolderIds, feedTagMap, expandedTagIds)
        if (index != null) listState.scrollToIndexIfNeeded(index)
    }

    // An edit can be started from the menu bar (or a shortcut) while its row is scrolled out of
    // view, and an editor the user cannot see would swallow every keystroke with nothing to show.
    // target.rowInstance is the exact rendered row the edit is on (a feed's folder-group row, or the
    // specific tag-nested copy it was selected through — see InlineEditTarget), so this never scrolls
    // to (or expands the folder behind) the wrong copy of the same feed.
    LaunchedEffect(inlineEdit) {
        val target = inlineEdit ?: return@LaunchedEffect
        val index = feedListRowIndex(
            target.rowInstance,
            feeds,
            folders,
            tags,
            collapsedFolderIds,
            feedTagMap,
            expandedTagIds,
        ) ?: return@LaunchedEffect
        listState.scrollToIndexIfNeeded(index)
    }

    // An in-progress edit's row can vanish out from under it — its tag collapses, the feed is
    // detached from the tag, or the feed/folder/tag itself is deleted — leaving no row composing
    // InlineRenameField to ever call onRenameCommit/onRenameCancel. Without this, inlineEdit would
    // stay stuck, permanently suppressing bare-key shortcuts and drag-reordering. Deliberately does
    // not scroll — only clears the stranded state — so it never fights a user who scrolled away for
    // unrelated reasons.
    LaunchedEffect(inlineEdit, feeds, folders, tags, collapsedFolderIds, feedTagMap, expandedTagIds) {
        val target = inlineEdit ?: return@LaunchedEffect
        val stillRendered = feedListRowIndex(
            target.rowInstance,
            feeds,
            folders,
            tags,
            collapsedFolderIds,
            feedTagMap,
            expandedTagIds,
        ) != null
        if (!stillRendered) inlineEdit = null
    }

    FeedListAutoScrollEffect(dragPointerYState, hostBoundsState, listState, dragController)

    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceContainerLow)
            .fillMaxSize()
            .paneActivation(onActivated)
            .nativeContextMenu(items = { emptyList() }, onOpen = onActivated),
    ) {
        FeedListToolbarRow(
            vm = vm,
            cloudConnected = cloudConnected,
            onAddFeedClick = onAddFeedClick,
        )

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
                .onFocusChanged { searchFieldFocused = it.isFocused },
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
            onClick = { vm.selectFilter(ArticleFilter.All); onActivated(); onSelectionAdvance() },
        )
        SidebarRow(
            icon = { KeryxIcon(KeryxIcons.Star, null) },
            label = stringResource(Res.string.home_starred),
            count = starredUnread,
            selected = filter == ArticleFilter.Starred,
            focused = focused,
            onClick = { vm.selectFilter(ArticleFilter.Starred); onActivated(); onSelectionAdvance() },
        )
        SidebarRow(
            icon = { KeryxIcon(KeryxIcons.Search, null) },
            label = stringResource(Res.string.home_search),
            count = searchUnread,
            selected = filter == ArticleFilter.Search,
            focused = focused,
            onClick = { vm.selectFilter(ArticleFilter.Search); vm.requestSearchFocus(); onActivated(); onSelectionAdvance() },
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
                    // The drag gesture watches the *Initial* pointer pass on this ancestor Box, so
                    // without this gate a press-and-sweep to select text inside an open inline
                    // editor would be stolen from the field and turned into a row drag.
                    .feedListReorderDrag(dragController, enabled = inlineEdit == null, isTouchPrimary = isTouchPrimary),
            ) {
                // Every slot below carries an explicit key and contentType. This list interleaves
                // several structurally different row kinds, and an unkeyed `item {}` falls back to an
                // index-derived key — so collapsing a folder or expanding a tag shifts those indices
                // and hands a slot that held, say, a folder header to a feed row. Keying them pins
                // each slot to its identity, and the contentType keeps each kind in its own reuse
                // pool so a recycled slot is only ever refilled with the same kind of row.
                LazyColumn(Modifier.fillMaxSize(), state = listState) {
                    stickyHeader(key = "folders-header", contentType = "section-header") {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(start = 16.dp, end = 8.dp),
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
                     * @param isFirstInList Whether this group's first feed could be the very first row of the
                     *   entire feed list (only true for the "no folder" group when there are no folders at all).
                     */
                    fun LazyListScope.feedItems(
                        feedsInFolder: List<Feeds>,
                        indented: Boolean,
                        folderId: String?,
                        isFirstInList: Boolean = false,
                    ) {
                        // This group *is* the reorder scope for every feed in it (the same one
                        // `FeedListDropIndex` resolves a drop within), so the accessibility
                        // move-up/move-down actions below need no ordering of their own.
                        val feedIdsInGroup = feedsInFolder.map { it.id }
                        itemsIndexed(
                            feedsInFolder,
                            key = { _, feed -> "feed-${feed.id}" },
                            contentType = { _, _ -> "feed" },
                        ) { index, feed ->
                            val instance = FeedListRowSelection.FeedInFolderGroup(feed.id)
                            FeedRow(
                                feed = feed,
                                count = unreadByFeed[feed.id] ?: 0L,
                                selectionTone = toneFor(instance),
                                focused = focused,
                                indented = indented,
                                nextFeedId = feedsInFolder.getOrNull(index + 1)?.id,
                                folderId = folderId,
                                isFirstInList = isFirstInList && index == 0,
                                activeBoundaryState = activeBoundaryState,
                                onClick = { vm.selectFilter(ArticleFilter.Feed(feed.id), instance); onActivated(); onSelectionAdvance() },
                                onRename = { inlineEdit = InlineEditTarget.Feed(feed.id) },
                                editingName = inlineEdit == InlineEditTarget.Feed(feed.id),
                                onRenameCommit = { vm.renameFeed(feed.id, it); inlineEdit = null },
                                onRenameCancel = { inlineEdit = null },
                                onRefresh = { vm.refreshFeed(feed) },
                                tags = tags,
                                attachedTagIds = feedTagMap[feed.id] ?: emptySet(),
                                onToggleFeedTag = { tagId, attached -> vm.setFeedTag(feed.id, tagId, attached) },
                                folders = folders,
                                onMoveFeedToFolder = { moveFolderId -> vm.moveFeed(feed.id, moveFolderId) },
                                onUnsubscribe = { confirmingUnsubscribeFeed = feed },
                                onCopyFeedUrl = { copyUrl(feed.url) },
                                onCopySiteUrl = { feed.site_url?.let(copyUrl) },
                                onOpenSite = { feed.site_url?.let(BrowserOpener::open) },
                                isTouchPrimary = isTouchPrimary,
                                // Same mutation the drop of a real drag applies (see
                                // FeedListDragController.end), just with the landing position
                                // resolved from the group's own order instead of a pointer.
                                onMoveUp = reorderTargetWithinScope(feedIdsInGroup, index, -1)?.let { target ->
                                    { vm.moveFeed(feed.id, folderId, target.insertBeforeId) }
                                },
                                onMoveDown = reorderTargetWithinScope(feedIdsInGroup, index, 1)?.let { target ->
                                    { vm.moveFeed(feed.id, folderId, target.insertBeforeId) }
                                },
                            )
                        }
                    }

                    val folderIds = folders.map { it.id }
                    val folderGroups = groupFeedsByFolder(feeds, folders)
                    folderGroups.forEachIndexed { index, (folder, feedsInFolder) ->
                        // The feed-zone boundary of the immediately preceding group's folder, only
                        // when that folder is collapsed or empty and therefore has no feed row of
                        // its own to paint the matching half of its own marker (see
                        // `FolderGroupHeader`'s `precedingFeedZoneBoundary` parameter). Derived
                        // fresh per iteration rather than carried in a `var` across the `forEach`:
                        // every `item { ... }` content lambda below is deferred until LazyColumn
                        // actually composes that row, which happens after this whole loop has
                        // finished running — a captured `var` would have already reached its final
                        // value by then, so every row would see the *same* (usually wrong) boundary.
                        val previousFolder = folderGroups.getOrNull(index - 1)?.first
                        val previousFeeds = folderGroups.getOrNull(index - 1)?.second.orEmpty()
                        val precedingFeedZoneBoundary = previousFolder
                            ?.takeIf { it.id in collapsedFolderIds || previousFeeds.isEmpty() }
                            ?.let { dropIndexState.value.feedZoneBoundaryFor(it.id) }
                        if (folder == null) {
                            if (folders.isNotEmpty()) {
                                item(key = "no-folder-header", contentType = "folder-header") {
                                    NoFolderHeader(
                                        firstFeedId = feedsInFolder.firstOrNull()?.id,
                                        feedIdsInNoFolder = feedsInFolder.mapTo(mutableSetOf()) { it.id },
                                        precedingFeedZoneBoundary = precedingFeedZoneBoundary,
                                        activeBoundaryState = activeBoundaryState,
                                    )
                                }
                            }
                            feedItems(feedsInFolder, indented = false, folderId = null, isFirstInList = folders.isEmpty())
                        } else {
                            val collapsed = folder.id in collapsedFolderIds
                            val folderIndex = folders.indexOf(folder)
                            val nextFolderId = folders.getOrNull(folderIndex + 1)?.id
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
                                    precedingFeedZoneBoundary = precedingFeedZoneBoundary,
                                    activeBoundaryState = activeBoundaryState,
                                    onToggleCollapse = { vm.toggleFolderCollapsed(folder.id) },
                                    onClick = { vm.selectFilter(ArticleFilter.Folder(folder.id)); onActivated(); onSelectionAdvance() },
                                    onEdit = { inlineEdit = InlineEditTarget.Folder(folder.id) },
                                    onDelete = { confirmingDeleteFolder = folder },
                                    editingName = inlineEdit == InlineEditTarget.Folder(folder.id),
                                    onRenameCommit = { vm.updateFolder(folder.id, it); inlineEdit = null },
                                    onRenameCancel = { inlineEdit = null },
                                    nameError = { name ->
                                        if (folders.any { it.id != folder.id && it.name == name }) folderNameDuplicateError else null
                                    },
                                    isDragSource = folder.id == draggedFeedFolderId,
                                    isTouchPrimary = isTouchPrimary,
                                    // A folder's reorder scope is the top-level folder order, so
                                    // these resolve against `folders` — the same list
                                    // FeedListDropIndex.nextFolderId is built from.
                                    onMoveUp = reorderTargetWithinScope(folderIds, folderIndex, -1)?.let { target ->
                                        { vm.reorderFolders(folder.id, target.insertBeforeId) }
                                    },
                                    onMoveDown = reorderTargetWithinScope(folderIds, folderIndex, 1)?.let { target ->
                                        { vm.reorderFolders(folder.id, target.insertBeforeId) }
                                    },
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

                    stickyHeader(key = "tags-header", contentType = "section-header") {
                        Row(
                            Modifier.fillMaxWidth()
                                .background(MaterialTheme.colorScheme.surfaceContainerLow)
                                .padding(start = 16.dp, end = 8.dp),
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
                                onClick = { vm.selectFilter(ArticleFilter.Tag(tag.id)); onActivated(); onSelectionAdvance() },
                                onEdit = { inlineEdit = InlineEditTarget.Tag(tag.id) },
                                onDelete = { confirmingDeleteTag = tag },
                                editingName = inlineEdit == InlineEditTarget.Tag(tag.id),
                                onRenameCommit = { vm.updateTag(tag.id, it, tag.color); inlineEdit = null },
                                onRenameCancel = { inlineEdit = null },
                                nameError = { name ->
                                    if (tags.any { it.id != tag.id && it.deleted_at == null && it.name == name }) {
                                        tagNameDuplicateError
                                    } else {
                                        null
                                    }
                                },
                                onSelectColor = { vm.updateTag(tag.id, tag.name, it) },
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
                                val instance = FeedListRowSelection.FeedInTag(feed.id, tag.id)
                                TagFeedRow(
                                    feed = feed,
                                    count = unreadByFeed[feed.id] ?: 0L,
                                    selectionTone = toneFor(instance),
                                    focused = focused,
                                    onClick = { vm.selectFilter(ArticleFilter.Feed(feed.id), instance); onActivated(); onSelectionAdvance() },
                                    onRename = { inlineEdit = InlineEditTarget.Feed(feed.id, tag.id) },
                                    editingName = inlineEdit == InlineEditTarget.Feed(feed.id, tag.id),
                                    onRenameCommit = { vm.renameFeed(feed.id, it); inlineEdit = null },
                                    onRenameCancel = { inlineEdit = null },
                                    onRemoveFromTag = { vm.setFeedTag(feed.id, tag.id, false) },
                                    onCopyFeedUrl = { copyUrl(feed.url) },
                                    onCopySiteUrl = { feed.site_url?.let(copyUrl) },
                                    onOpenSite = { feed.site_url?.let(BrowserOpener::open) },
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
        confirmingDeleteTag = confirmingDeleteTag,
        onConfirmingDeleteTagChange = { confirmingDeleteTag = it },
        showAddFolder = showAddFolder,
        onShowAddFolderChange = { showAddFolder = it },
        confirmingDeleteFolder = confirmingDeleteFolder,
        onConfirmingDeleteFolderChange = { confirmingDeleteFolder = it },
        confirmingUnsubscribeFeed = confirmingUnsubscribeFeed,
        onConfirmingUnsubscribeFeedChange = { confirmingUnsubscribeFeed = it },
    )
}

/**
 * Drives feed-list auto-scroll while a drag's pointer sits in an edge zone: while
 * [dragPointerYState] is non-null, scrolls [listState] toward whichever edge of [hostBoundsState]
 * the pointer is near, faster the closer it is, and refreshes [dragController]'s hover state each
 * frame so the insertion line tracks the rows sliding underneath a motionless pointer.
 */
@Composable
private fun FeedListAutoScrollEffect(
    dragPointerYState: State<Float?>,
    hostBoundsState: State<Rect>,
    listState: LazyListState,
    dragController: FeedListDragController,
) {
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
}

/**
 * [FeedListPane]'s top toolbar row: add feed / refresh all / cloud sync (when [cloudConnected]).
 * Reads [vm]'s refreshing/syncing state itself (rather than taking it as a parameter) so a
 * refresh/sync toggle only invalidates this row's own restart scope, not the whole pane.
 */
@Composable
private fun FeedListToolbarRow(
    vm: HomeViewModel,
    cloudConnected: Boolean,
    onAddFeedClick: () -> Unit,
) {
    val refreshing by vm.feedRefreshing.collectAsStateSafe(false)
    val syncing by vm.syncing.collectAsStateSafe(false)
    WindowDragArea(Modifier.fillMaxWidth()) {
        KeryxPaneTopBar(
            modifier = Modifier.padding(top = WindowChrome.titleBarInsetDp.dp, start = 4.dp, end = 4.dp),
            // Desktop's only entry point to Settings is the native application menu bar
            // (AppMenuBar / macOS Preferences… / KDE Global Menu). Android has none of those, so
            // this pane needs its own button — see `platform/PlatformOs.kt`'s `hasNativeAppMenu` KDoc.
            navigationIcon = if (hasNativeAppMenu) {
                null
            } else {
                val menuController = koinInject<MenuController>()
                val settingsTooltip = stringResource(Res.string.menu_settings)
                val icon: @Composable () -> Unit = {
                    TooltipIconButton(tooltip = settingsTooltip, onClick = { menuController.send(MenuCommand.OpenSettings) }) {
                        KeryxIcon(KeryxIcons.Tune, settingsTooltip)
                    }
                }
                icon
            },
        ) {
            ToolbarIconGroup {
                val addFeedTooltip = stringResource(Res.string.home_add_feed)
                TooltipIconButton(tooltip = addFeedTooltip, onClick = onAddFeedClick) {
                    KeryxIcon(KeryxIcons.Add, addFeedTooltip)
                }
                val refreshTooltip = stringResource(
                    if (refreshing) Res.string.home_refreshing else Res.string.home_refresh,
                )
                TooltipIconButton(tooltip = refreshTooltip, onClick = { vm.refreshAll() }, enabled = feedOperationsAvailable(refreshing, syncing)) {
                    if (refreshing) {
                        SmallSpinner()
                    } else {
                        KeryxIcon(KeryxIcons.Refresh, refreshTooltip)
                    }
                }
                if (cloudConnected) {
                    val syncTooltip = stringResource(
                        if (syncing) Res.string.home_syncing else Res.string.home_sync,
                    )
                    TooltipIconButton(tooltip = syncTooltip, onClick = { vm.sync() }, enabled = feedOperationsAvailable(refreshing, syncing)) {
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
    val rowInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .listRowClickable(rowInteraction, onClick)
            .listRowSurface(selectionBackground(selected, focused), ListRowKind.NavItem, rowInteraction)
            .padding(horizontal = 8.dp, vertical = 8.dp)
            .heightIn(min = listRowMinHeight()),
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
 * @param onEdit Starts inline editing of the tag's name.
 * @param onDelete Deletes the tag.
 * @param editingName Whether the name is currently open for inline editing (see [InlineRenameField]).
 * @param onRenameCommit Applies an edited tag name.
 * @param onRenameCancel Abandons an in-progress name edit.
 * @param nameError Produces a validation message for an edited name, or `null` when valid.
 * @param onSelectColor Applies a color picked from the color dot's popover. Independent of name
 *   editing: the dot is clickable whether or not the row is currently being renamed.
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
    editingName: Boolean = false,
    onRenameCommit: (String) -> Unit = {},
    onRenameCancel: () -> Unit = {},
    nameError: (String) -> String? = { null },
    onSelectColor: (String?) -> Unit = {},
) {
    val editLabel = stringResource(Res.string.home_edit_tag_menu)
    val deleteLabel = stringResource(Res.string.home_delete_tag_menu)
    val colorLabel = stringResource(Res.string.home_tag_color)
    var showColorPicker by remember { mutableStateOf(false) }
    val contentColor = dropTargetContentColorOrNull(isDropTarget, selected, focused, MaterialTheme.colorScheme.onTertiaryContainer)
    val rowInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier.testTag(tagRowTestTag(tag.id))
            .fillMaxWidth()
            .listRowClickable(rowInteraction, onClick)
            .nativeContextMenu(
                items = {
                    listOf(
                        NativeMenuItem(editLabel, renameNativeShortcut) { onEdit() },
                        NativeMenuItem(deleteLabel, deleteNativeShortcut) { onDelete() },
                    )
                },
                onOpen = { if (!selected) onClick() },
            )
            .listRowSurface(
                dropTargetBackground(isDropTarget, selected, focused, MaterialTheme.colorScheme.tertiaryContainer),
                ListRowKind.NavItem,
                rowInteraction,
                decoration = dropTargetBorderModifier(isDropTarget, MaterialTheme.colorScheme.tertiary),
            )
            .padding(start = 8.dp, end = 8.dp)
            .heightIn(min = listRowMinHeight()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val isTouchPrimary = works.merc.keryx.app.platform.isTouchPrimary
        CompositionLocalProvider(LocalContentColor provides (contentColor ?: LocalContentColor.current)) {
            ExpandCollapseChevron(expanded = expanded, onToggle = onToggleExpanded)
            Spacer(Modifier.width(4.dp))
            Row(
                Modifier.weight(1f).padding(vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Anchors the color popover; sized by the click target inside it.
                Box {
                    Box(
                        // Desktop: the click target is deliberately larger than the dot it
                        // contains, absorbing the 8dp gap that used to be a Spacer here plus 4dp
                        // above and below, so the geometry of the row is unchanged while the hit
                        // area is not a 10dp circle. Touch: a full Material 48dp touch target
                        // instead — safe now that the row's own listRowMinHeight() floor keeps
                        // this from stretching the row taller than its neighbors.
                        Modifier
                            .testTag(tagColorDotTestTag(tag.id))
                            .clickable(onClickLabel = colorLabel) { showColorPicker = true }
                            .then(
                                if (isTouchPrimary) {
                                    Modifier.size(TAG_COLOR_DOT_TOUCH_TARGET_DP.dp)
                                } else {
                                    Modifier.padding(top = 4.dp, bottom = 4.dp, end = 8.dp)
                                },
                            ),
                        contentAlignment = Alignment.Center,
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
                                Box(Modifier.size(TAG_COLOR_DOT_SIZE_DP.dp).background(colorFromHex(tag.color), CircleShape))
                            }
                        }
                    }
                    if (showColorPicker) {
                        TagColorPickerPopup(
                            selected = tag.color,
                            onSelect = { showColorPicker = false; onSelectColor(it) },
                            onDismissRequest = { showColorPicker = false },
                            anchorOffsetY = if (isTouchPrimary) {
                                TAG_COLOR_DOT_TOUCH_TARGET_DP.dp
                            } else {
                                (TAG_MARKER_SIZE_DP + TAG_COLOR_DOT_HIT_PADDING_DP * 2).dp
                            },
                        )
                    }
                }
                // Same weighted slot either way, so the color dot on the left and the count badge on
                // the right never move when editing starts or ends.
                if (editingName) {
                    Box(Modifier.weight(1f)) {
                        InlineRenameField(
                            value = tag.name,
                            onCommit = onRenameCommit,
                            onCancel = onRenameCancel,
                            blockingError = nameError,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                } else {
                    Text(tag.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        if (count > 0) CountBadge(count, selected, focused, isDropTarget, onContainerColor = MaterialTheme.colorScheme.onTertiaryContainer)
    }
}

/** Size of a [TagRow]'s leading marker slot, holding either the tag color dot or the drop "+" glyph. */
private const val TAG_MARKER_SIZE_DP = 16

/** Diameter of the visible tag color dot inside the [TAG_MARKER_SIZE_DP] marker slot. */
private const val TAG_COLOR_DOT_SIZE_DP = 10

/** Vertical slack added around the marker slot to widen the color dot's click target without
 * changing the row's height (the row's own text is taller than the resulting box). Desktop only —
 * see [TAG_COLOR_DOT_TOUCH_TARGET_DP] for touch. */
private const val TAG_COLOR_DOT_HIT_PADDING_DP = 4

/** The color dot's click target on a touch-primary platform — a full Material touch target,
 * safe now that [listRowMinHeight] keeps the row itself at least this tall. */
private const val TAG_COLOR_DOT_TOUCH_TARGET_DP = 48

/** Test tag on a tag row's color dot, which opens its color popover. */
internal fun tagColorDotTestTag(tagId: String): String = "tag-color-dot-$tagId"

/** Test tag on a [TagRow] itself, distinguishing its clickable band from its color dot. */
internal fun tagRowTestTag(tagId: String): String = "tag-row-$tagId"

/**
 * Renders a feed attached to an expanded tag.
 *
 * @param feed The attached feed.
 * @param count The number of unread articles in the feed.
 * @param selectionTone How this rendered instance paints its selection — the same feed also renders
 *   under its folder group, and only the instance actually selected paints
 *   [RowSelectionTone.PRIMARY] (see [FeedListRowSelection]).
 * @param focused Whether the sidebar has focus.
 * @param onClick Handles feed selection.
 * @param onRename Starts inline editing of the feed's display title, on this tag-nested row.
 * @param editingName Whether the title is currently open for inline editing on this row (see
 *   [InlineRenameField]) — this feed's folder-group row edits independently of this one.
 * @param onRenameCommit Applies an edited title; a blank value resets it to the feed's own title.
 * @param onRenameCancel Abandons an in-progress title edit.
 * @param onRemoveFromTag Detaches the feed from the tag.
 * @param onCopyFeedUrl Copies the feed's own (RSS/Atom) URL to the clipboard.
 * @param onCopySiteUrl Copies the feed's website URL to the clipboard.
 * @param onOpenSite Opens the feed's website in the external browser.
 */
@Composable
private fun TagFeedRow(
    feed: Feeds,
    count: Long,
    selectionTone: RowSelectionTone,
    focused: Boolean,
    onClick: () -> Unit,
    onRename: () -> Unit,
    editingName: Boolean,
    onRenameCommit: (String) -> Unit,
    onRenameCancel: () -> Unit,
    onRemoveFromTag: () -> Unit,
    onCopyFeedUrl: () -> Unit,
    onCopySiteUrl: () -> Unit,
    onOpenSite: () -> Unit,
) {
    val renameLabel = stringResource(Res.string.home_rename_feed)
    val removeLabel = stringResource(Res.string.home_remove_feed_from_tag_menu)
    val copyFeedUrlLabel = stringResource(Res.string.home_copy_feed_url)
    val copySiteUrlLabel = stringResource(Res.string.home_copy_site_url)
    val openSiteLabel = stringResource(Res.string.home_open_site)
    val siteUrlUsable = hasUsableUrl(feed.site_url)
    val rowInteraction = remember { MutableInteractionSource() }
    Row(
        Modifier.fillMaxWidth()
            .listRowClickable(rowInteraction, onClick)
            .nativeContextMenu(
                items = {
                    listOf(
                        NativeMenuItem(copyFeedUrlLabel) { onCopyFeedUrl() },
                        NativeMenuItem(copySiteUrlLabel, enabled = siteUrlUsable) { onCopySiteUrl() },
                        NativeMenuItem(openSiteLabel, enabled = siteUrlUsable) { onOpenSite() },
                        NativeMenuSeparator,
                        NativeMenuItem(renameLabel, renameNativeShortcut) { onRename() },
                        NativeMenuSeparator,
                        NativeMenuItem(removeLabel) { onRemoveFromTag() },
                    )
                },
                // A secondary-toned (or unselected) row is not the one currently focused, so a
                // right-click on it promotes it first, exactly as the old `!selected` check did.
                onOpen = { if (selectionTone != RowSelectionTone.PRIMARY) onClick() },
            )
            .listRowSurface(selectionBackground(selectionTone, focused), ListRowKind.NavItem, rowInteraction)
            .padding(start = FEED_ROW_INDENT, end = 8.dp, top = 4.dp, bottom = 4.dp)
            .heightIn(min = listRowMinHeight()),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FeedAvatar(feed.displayTitle(), feed.favicon_url)
        Spacer(Modifier.width(12.dp))
        // Same weighted slot either way, so the favicon on the left and the count badge on the right
        // never move when editing starts or ends (mirrors FeedRow's folder-group editor).
        if (editingName) {
            Box(Modifier.weight(1f)) {
                InlineRenameField(
                    value = feed.custom_title ?: feed.title,
                    onCommit = onRenameCommit,
                    onCancel = onRenameCancel,
                    placeholder = feed.title,
                    allowBlank = true,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        } else {
            CompositionLocalProvider(LocalContentColor provides (selectionContentColorOrNull(selectionTone, focused) ?: LocalContentColor.current)) {
                Text(feed.displayTitle(), Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (count > 0) CountBadge(count, selectionTone == RowSelectionTone.PRIMARY, focused)
    }
}
