import KeryxShared
import SwiftUI
import UniformTypeIdentifiers

/// The sidebar pane: All / Starred, folders (collapsible, with unread badges), unfoldered feeds,
/// and tags (expandable, with color + attached feeds) — see `external-spec.md` §9's "3-pane width"
/// and `docs/app-architecture.md`. Desktop/macOS is unconditionally the 3-pane steady state, so
/// this pane (and its permanent search field) is always on screen — there is no narrower-width
/// drawer variant to reproduce here (that only applies to Android; see M2's research notes).
///
/// M3 adds feed/folder/tag management here: add-feed sheet, folder/tag create+rename+delete
/// (`NamePromptSheet`, shared with duplicate-name validation via `NameValidation.kt`), context
/// menus, and drag-and-drop (feed reorder/move-to-folder/tag-attach, folder reorder).
struct FeedListView: View {
    let home: HomeObservable
    @Bindable var dialogs: SidebarDialogState
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    /// Rows currently on screen, keyed by `feedListRowSelectionKey` — read by the scroll-to-
    /// selection effect below so an already-visible row (e.g. one just clicked) never jumps.
    @State private var appearedRowKeys: Set<String> = []

    // Drag-and-drop state, shared across every row/header via `feedListDropTarget` — mirrors
    // Compose's own `activeBoundaryState`/`hoveredAttachTagIdState`/`draggedFeedIdState`
    // (`FeedListDragController.kt`). See `FeedListDragAndDrop.swift` for the shared drop-resolution
    // wiring and why the live highlight is a deliberate simplification.
    @State private var draggingItem: FeedListDragPayload?
    @State private var activeBoundary: DropBoundary?
    @State private var hoveredTagId: String?
    @State private var hoveredKey: FeedListHoverKey?

    private var selectedRowKey: String { feedListRowSelectionKey(home.selectedRowInstance) }

    /// Rebuilt from the current feeds/folders on every change — mirrors Compose's own
    /// `derivedStateOf { buildFeedListDropIndex(feeds, folders) }` (`FeedListPane.kt`), just without
    /// the memoization (this pane's row counts are small enough that recomputing on every body
    /// evaluation costs nothing worth caching for).
    private var dropIndex: FeedListDropIndex {
        FeedListDragKt.buildFeedListDropIndex(feeds: sortedFeeds, folders: sortedFolders)
    }

    var body: some View {
        VStack(spacing: 0) {
            searchField
            Divider()
            ScrollViewReader { proxy in
                listContent
                    .listStyle(.sidebar)
                    .focused(focusedPane, equals: .feedList)
                    // Only when the selection actually moved off-screen (arrow-key navigation, a
                    // restored selection) — mirrors the article list's own scroll-to-selection
                    // effect (`ArticleListView.body`) and Compose's `FeedListPane.kt:370-373`.
                    .onChange(of: selectedRowKey, initial: false) { _, key in
                        guard !appearedRowKeys.contains(key) else { return }
                        proxy.scrollTo(key)
                    }
            }
        }
        .navigationTitle(L("app_name"))
        .toolbar { toolbarContent }
        // Spring-loaded folder: holding a dragged feed over a collapsed folder opens it after a
        // short pause, so its feeds become reachable drop targets mid-drag — matches Compose's own
        // `LaunchedEffect(isFeedDragHighlight, collapsed)` (`FeedListDragAndDrop.kt`). Re-checks
        // `hoveredKey` after the delay so releasing/moving away first cancels the expand.
        .onChange(of: hoveredKey) { _, key in
            guard case .folder(let folderId) = key, home.collapsedFolderIds.contains(folderId) else { return }
            Task {
                try? await Task.sleep(for: .milliseconds(700))
                guard hoveredKey == .folder(folderId) else { return }
                home.viewModel.toggleFolderCollapsed(folderId: folderId)
            }
        }
        .modifier(SidebarCreateSheets(home: home, dialogs: dialogs))
        .modifier(SidebarRenameSheets(home: home, dialogs: dialogs))
        .modifier(SidebarDeleteAlerts(home: home, dialogs: dialogs))
        // The 3-pane desktop/macOS layout keeps this field permanently visible (mirrors Compose's
        // own `FeedListPane`, whose `onSelectionAdvance == null` branch is this same steady state —
        // there is no narrower layout here to ever hide it again), so this only needs setting once.
        .task { home.viewModel.setSearchBarVisible(visible: true) }
        .onChange(of: home.pendingSearchFocus) { _, pending in
            guard pending else { return }
            focusedPane.wrappedValue = .search
            home.viewModel.consumeSearchFocusRequest()
        }
    }

    /// A plain `TextField` rather than `.searchable`: the system search field cannot report its own
    /// focus state before macOS 15 (`.searchFocused(_:)`), which this custom field needs both to
    /// answer `HomeShortcutsKt.homeShortcutFor`'s `textInputFocused` and to let ↓/↑ hand off into
    /// the article results (`HomeView.moveArticleSelectionFromSearchField`) — see `HomeScreen.kt`'s
    /// own `focusSearch`/`moveArticleSelectionFromSearchField`.
    private var searchField: some View {
        HStack(spacing: 6) {
            Image(systemName: "magnifyingglass").foregroundStyle(.secondary)
            TextField(L("home_search_placeholder"), text: searchQueryBinding)
                .textFieldStyle(.plain)
                .focused(focusedPane, equals: .search)
            if !home.searchQuery.isEmpty {
                Button {
                    home.viewModel.setSearchQuery(query: "")
                } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(.secondary)
                }
                .buttonStyle(.plain)
                .help(L("home_search_clear"))
            }
        }
        .padding(.horizontal, 8)
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private var listContent: some View {
        List {
            Section {
                allRow
                starredRow
            }
            ForEach(sortedFolders, id: \.id) { folder in
                folderSection(folder)
            }
            // Always present — even with no unassigned feeds — so a feed can still be dragged out
            // of every folder into "no folder" (D3: without this header there would be no drop
            // target for that when the group is otherwise empty). Compose shows the same header
            // unconditionally (`FeedListPane.kt`'s own "No folder" section).
            Section {
                noFolderHeader
                ForEach(unassignedFeeds, id: \.id) { feed in
                    feedRow(feed, instance: FeedListRowSelectionFeedInFolderGroup(feedId: feed.id))
                }
            }
            ForEach(sortedTags, id: \.id) { tag in
                tagSection(tag)
            }
        }
    }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem {
            Menu {
                Button(L("menu_file_add_feed")) { dialogs.isAddingFeed = true }
                Button(L("menu_file_add_folder")) { dialogs.isAddingFolder = true }
                Button(L("menu_file_add_tag")) { dialogs.isAddingTag = true }
            } label: {
                Image(systemName: "plus")
            }
        }
        // Mirrors Compose's own `FeedListToolbarRow` (`FeedListPane.kt:883-943`): Refresh All is
        // always present, Sync only while a cloud provider is connected, and both disable while
        // either operation (or the refresh-then-sync cycle covering the gap between them) is
        // already in flight — `activity.refreshIndicatorShown`/`.syncing` pick which one's own
        // spinner shows, never both for the same phase.
        ToolbarItem {
            Button {
                home.viewModel.refreshAll()
            } label: {
                if home.activity.refreshIndicatorShown {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "arrow.clockwise")
                }
            }
            .disabled(!home.activity.idle)
            .help(L(home.activity.refreshIndicatorShown ? "home_refreshing" : "home_refresh"))
            // Kept the same regardless of the spinner replacing the icon label, so VoiceOver
            // always announces what the button does rather than the SF Symbol's own default name.
            .accessibilityLabel(L("home_refresh"))
        }
        if home.cloudConnected {
            ToolbarItem {
                Button {
                    home.viewModel.sync()
                } label: {
                    if home.activity.syncing {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "icloud")
                    }
                }
                .disabled(!home.activity.idle)
                .help(L(home.activity.syncing ? "home_syncing" : "home_sync"))
                .accessibilityLabel(L("home_sync"))
            }
        }
    }

    private var searchQueryBinding: Binding<String> {
        // `setSearchBarVisible` is set once in `body`'s own `.task` (the 3-pane layout keeps this
        // field permanently visible), not on every keystroke here.
        Binding(
            get: { home.searchQuery },
            set: { home.viewModel.setSearchQuery(query: $0) }
        )
    }

    // MARK: - All / Starred

    private var allRow: some View {
        row(
            title: L("home_all_feeds"),
            systemImage: "tray.full",
            unreadCount: home.totalUnread,
            instance: FeedListRowSelectionAll(),
        )
    }

    private var starredRow: some View {
        row(
            title: L("home_starred"),
            systemImage: "star.fill",
            unreadCount: home.starredUnreadCount,
            instance: FeedListRowSelectionStarred(),
        )
    }

    // MARK: - Folders

    private var sortedFolders: [Folders] {
        home.folders.sorted { $0.sort_order < $1.sort_order }
    }

    private var sortedFeeds: [Feeds] {
        home.feeds.sorted { $0.sort_order < $1.sort_order }
    }

    /// Reuses the shared `groupFeedsByFolder` (`FeedListModel.kt`) rather than filtering by
    /// `folder_id` locally, so a feed whose `folder_id` points at a folder that no longer exists
    /// (deleted on another device, not yet synced here) defensively falls into the unassigned
    /// group here too, matching Compose's own `groupFeedsByFolder` fallback.
    private var groupedFeeds: [(folder: Folders?, feeds: [Feeds])] {
        FeedListModelKt.groupFeedsByFolder(feeds: sortedFeeds, folders: sortedFolders)
            .map { (folder: $0.first, feeds: $0.second as? [Feeds] ?? []) }
    }

    private var unassignedFeeds: [Feeds] {
        groupedFeeds.first { $0.folder == nil }?.feeds ?? []
    }

    private func feedsIn(folder: Folders) -> [Feeds] {
        groupedFeeds.first { $0.folder?.id == folder.id }?.feeds ?? []
    }

    /// The "No folder" section header — not itself a selectable filter (there is no
    /// `ArticleFilter` for "every unfoldered feed"), only a drop target for moving a feed out of
    /// every folder, matching Compose's own header (`FeedListPane.kt`).
    private var noFolderHeader: some View {
        Text(L("home_no_folder"))
            .font(.caption)
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
            // No top overlay here: when there's at least one unassigned feed, its own row already
            // draws this exact `BeforeFeed` boundary at its top (`feedRow`'s own top overlay above) —
            // drawing it here too would show the same insertion line twice at once.
            .overlay(alignment: .bottom) {
                if unassignedFeeds.isEmpty, dropBoundariesEqual(activeBoundary, DropBoundaryAppendFeeds(folderId: nil)) {
                    FeedListInsertionLine()
                }
            }
            .feedListDropTarget(
                FeedListDropTargetNoFolderHeader.shared,
                hoverKey: .noFolder,
                home: home,
                index: dropIndex,
                draggingItem: $draggingItem,
                activeBoundary: $activeBoundary,
                hoveredTagId: $hoveredTagId,
                hoveredKey: $hoveredKey
            )
    }

    @ViewBuilder
    private func folderSection(_ folder: Folders) -> some View {
        let isCollapsed = home.collapsedFolderIds.contains(folder.id)
        let instance = FeedListRowSelectionFolder(folderId: folder.id)
        Section {
            HStack {
                expandChevron(expanded: !isCollapsed) {
                    home.viewModel.toggleFolderCollapsed(folderId: folder.id)
                }
                Text(folder.name)
                Spacer()
                unreadBadge(home.unreadByFolder[folder.id] ?? 0)
            }
            .selectableRowLabel(selectionBackground(for: instance))
            .selectsOnClick(isSelected: feedListRowSelectionsEqual(instance, home.selectedRowInstance)) { select(instance) }
            .selectsOnContextMenu(id: feedListRowSelectionKey(instance)) { selectForContextMenu(instance) }
            .contextMenu {
                // Opening the menu selects the row first, matching Compose's own
                // `onOpen = { if (!selected) onClick() }` (`FeedListDragAndDrop.kt`) — the actual
                // selection runs on a right-click/Control-click via `.selectsOnContextMenu` above,
                // not as a side effect of this builder (see `ContextMenuSelectionTracker`'s own doc
                // for why).
                Button(L("home_edit_folder_menu")) { dialogs.renamingFolder = folder }
                Button(L("home_delete_folder_menu"), role: .destructive) { dialogs.deletingFolder = folder }
            }
            .feedListDraggable(FeedListDragPayload(kind: .folder, id: folder.id), draggingItem: $draggingItem)
            .overlay(alignment: .top) {
                if dropBoundariesEqual(activeBoundary, DropBoundaryBeforeFolder(folderId: folder.id)) {
                    FeedListInsertionLine()
                }
            }
            .overlay(alignment: .bottom) {
                // A folder dropped into this one lands at its front (matches Compose's own
                // `feedZoneBoundaryFor`), so the header itself — not its last feed row — is where
                // that boundary is drawn; the header is always present, whether or not the folder
                // is collapsed or empty.
                if dropBoundariesEqual(activeBoundary, DropBoundaryAppendFeeds(folderId: folder.id)),
                   isCollapsed || feedsIn(folder: folder).isEmpty {
                    FeedListInsertionLine()
                }
                if dropIndex.nextFolderId[folder.id] == nil,
                   dropBoundariesEqual(activeBoundary, DropBoundaryAppendFolders.shared) {
                    FeedListInsertionLine()
                }
            }
            .feedListDropTarget(
                FeedListDropTargetFolderHeader(folderId: folder.id),
                hoverKey: .folder(folder.id),
                home: home,
                index: dropIndex,
                draggingItem: $draggingItem,
                activeBoundary: $activeBoundary,
                hoveredTagId: $hoveredTagId,
                hoveredKey: $hoveredKey
            )
            .trackAppearance(feedListRowSelectionKey(instance), in: $appearedRowKeys)

            if !isCollapsed {
                ForEach(feedsIn(folder: folder), id: \.id) { feed in
                    feedRow(feed, instance: FeedListRowSelectionFeedInFolderGroup(feedId: feed.id))
                }
            }
        }
    }

    // MARK: - Tags

    private var sortedTags: [Tags] {
        home.tags.sorted { $0.sort_order < $1.sort_order }
    }

    private func feeds(taggedWith tag: Tags) -> [Feeds] {
        FeedListModelKt.feedsForTag(feeds: sortedFeeds, feedTagMap: home.feedTagMap, tagId: tag.id)
    }

    @ViewBuilder
    private func tagSection(_ tag: Tags) -> some View {
        let isExpanded = home.expandedTagIds.contains(tag.id)
        let instance = FeedListRowSelectionTag(tagId: tag.id)
        Section {
            TagHeaderRow(
                home: home,
                dialogs: dialogs,
                tag: tag,
                instance: instance,
                isExpanded: isExpanded,
                focusedPane: focusedPane,
                appearedRowKeys: $appearedRowKeys,
                selectionBackground: selectionBackground(for:),
                dropIndex: dropIndex,
                draggingItem: $draggingItem,
                activeBoundary: $activeBoundary,
                hoveredTagId: $hoveredTagId,
                hoveredKey: $hoveredKey
            )

            if isExpanded {
                ForEach(feeds(taggedWith: tag), id: \.id) { feed in
                    feedRow(feed, instance: FeedListRowSelectionFeedInTag(feedId: feed.id, tagId: tag.id), isDropTarget: false)
                }
            }
        }
    }

    // MARK: - Rows

    /// - Parameter isDropTarget: `false` for a feed's copy nested under an expanded tag — such a
    ///   row can still be *dragged* (moved into a folder, reordered), but is never itself a drop
    ///   target, matching Compose's own `FeedListRowKey.Other` classification for it
    ///   (`FeedListDragAndDrop.kt`'s own `parseFeedListRowKey`).
    @ViewBuilder
    private func feedRow(_ feed: Feeds, instance: FeedListRowSelection, isDropTarget: Bool = true) -> some View {
        row(
            title: feed.displayTitle(),
            faviconUrl: feed.favicon_url,
            unreadCount: home.unreadByFeed[feed.id] ?? 0,
            isErroring: feed.error_count > 0 || feed.last_error == ConstantsKt.FEED_ERROR_REASON_GONE,
            isGone: feed.last_error == ConstantsKt.FEED_ERROR_REASON_GONE,
            instance: instance,
        )
        .feedListDraggable(FeedListDragPayload(kind: .feed, id: feed.id), draggingItem: $draggingItem)
        .overlay(alignment: .top) {
            // Guarded by `isDropTarget` for the same reason as the bottom overlay below: a feed's
            // copy nested under an expanded tag is never itself a drop target, so it must never draw
            // the `BeforeFeed` boundary its folder-group copy already draws for the same feed.
            if isDropTarget, dropBoundariesEqual(activeBoundary, DropBoundaryBeforeFeed(feedId: feed.id)) {
                FeedListInsertionLine()
            }
        }
        .overlay(alignment: .bottom) {
            // The last feed in its group also carries the group's own "append to the end" boundary
            // — matches Compose's own paired top/bottom markers resolving to the same boundary
            // from either side (`FeedListDragAndDrop.kt`'s `insertionMarkers`).
            if isDropTarget, dropIndex.nextFeedInGroup[feed.id] == nil,
               dropBoundariesEqual(activeBoundary, DropBoundaryAppendFeeds(folderId: feed.folder_id)) {
                FeedListInsertionLine()
            }
        }
        .modifier(ConditionalFeedListDropTarget(
            isEnabled: isDropTarget,
            target: FeedListDropTargetFeedRow(feedId: feed.id),
            hoverKey: .feed(feed.id),
            home: home,
            index: dropIndex,
            draggingItem: $draggingItem,
            activeBoundary: $activeBoundary,
            hoveredTagId: $hoveredTagId,
            hoveredKey: $hoveredKey
        ))
        .selectsOnContextMenu(id: feedListRowSelectionKey(instance)) { selectForContextMenu(instance) }
        .contextMenu {
            // Opening the menu selects the row first, matching Compose's own
            // `onOpen = { if (!selected) onClick() }` (`FeedListDragAndDrop.kt`) — the actual
            // selection runs on a right-click/Control-click via `.selectsOnContextMenu` above, not
            // as a side effect of this builder (see `ContextMenuSelectionTracker`'s own doc for why).
            // Order matches `FeedListDragAndDrop.kt:553-593` exactly: Refresh, Move to Folder ▸,
            // Assign tags ▸, a separator, the URL/site actions, a separator, Rename, a separator,
            // Unsubscribe.
            Button(L("home_refresh")) { home.viewModel.refreshFeed(feed: feed) }
            Menu(L("home_move_to_folder")) {
                Toggle(L("home_no_folder"), isOn: Binding(
                    get: { feed.folder_id == nil },
                    set: { _ in home.viewModel.moveFeed(feedId: feed.id, folderId: nil, targetFeedId: nil) }
                ))
                ForEach(sortedFolders, id: \.id) { folder in
                    Toggle(folder.name, isOn: Binding(
                        get: { feed.folder_id == folder.id },
                        set: { _ in home.viewModel.moveFeed(feedId: feed.id, folderId: folder.id, targetFeedId: nil) }
                    ))
                }
                Button(L("home_new_folder")) { dialogs.creatingFolderForFeed = feed }
            }
            Menu(L("home_assign_tags")) {
                ForEach(sortedTags, id: \.id) { tag in
                    Toggle(tag.name, isOn: Binding(
                        get: { home.feedTagMap[feed.id]?.contains(tag.id) ?? false },
                        set: { attached in home.viewModel.setFeedTag(feedId: feed.id, tagId: tag.id, attached: attached) }
                    ))
                }
                Button(L("home_new_tag")) { dialogs.creatingTagForFeed = feed }
            }
            Divider()
            Button(L("home_copy_feed_url")) { copyToPasteboard(feed.url) }
            Button(L("home_copy_site_url")) { if let site = feed.site_url { copyToPasteboard(site) } }
                .disabled(!ArticleListModelKt.hasUsableUrl(url: feed.site_url))
            Button(L("home_open_site")) { if let site = feed.site_url { openInBrowser(site) } }
                .disabled(!ArticleListModelKt.hasUsableUrl(url: feed.site_url))
            Divider()
            Button(L("home_rename_feed")) { dialogs.renamingFeed = feed }
            Divider()
            Button(L("home_unsubscribe_menu"), role: .destructive) { dialogs.unsubscribingFeed = feed }
        }
    }

    @ViewBuilder
    private func row(
        title: String,
        systemImage: String? = nil,
        faviconUrl: String? = nil,
        unreadCount: Int64,
        isErroring: Bool = false,
        isGone: Bool = false,
        instance: FeedListRowSelection,
    ) -> some View {
        HStack {
            if let systemImage {
                Image(systemName: systemImage).frame(width: 18)
            } else {
                FaviconView(url: faviconUrl, letter: title.first)
                    .frame(width: 18, height: 18)
            }
            Text(title).lineLimit(1)
            Spacer()
            if isErroring {
                // The hover tooltip (`.help`) only appears for a gone (410) feed, matching
                // Compose's own `FeedErrorIndicator` (`FeedListDragAndDrop.kt:655-676`) — an
                // ordinary fetch error gets no tooltip, only the accessibility label below.
                Group {
                    if isGone {
                        Image(systemName: "exclamationmark.triangle.fill").help(L("home_feed_gone"))
                    } else {
                        Image(systemName: "exclamationmark.triangle.fill")
                    }
                }
                .foregroundStyle(.orange)
                .accessibilityLabel(L(isGone ? "home_feed_gone" : "home_feed_error"))
            }
            unreadBadge(unreadCount)
        }
        .padding(.vertical, 2)
        .selectableRowLabel(selectionBackground(for: instance))
        .selectsOnClick(isSelected: feedListRowSelectionsEqual(instance, home.selectedRowInstance)) { select(instance) }
        .trackAppearance(feedListRowSelectionKey(instance), in: $appearedRowKeys)
    }

    /// A row's primary click action: focuses the sidebar and selects `instance`'s filter.
    private func select(_ instance: FeedListRowSelection) {
        focusedPane.wrappedValue = .feedList
        home.viewModel.selectFilter(filter: instance.filter, instance: instance)
    }

    /// Selects `instance` if it isn't already the primary selection — called when a row's context
    /// menu opens, matching Compose's own `onOpen = { if (!selected) onClick() }`
    /// (`FeedListDragAndDrop.kt`).
    private func selectForContextMenu(_ instance: FeedListRowSelection) {
        guard !feedListRowSelectionsEqual(instance, home.selectedRowInstance) else { return }
        home.viewModel.selectFilter(filter: instance.filter, instance: instance)
    }

    /// Mirrors the Compose app's `RowSelectionTone` (`FeedListPane.kt`'s `toneFor`): the instance
    /// actually clicked/navigated to is the strong PRIMARY tint (dimmed while the sidebar lacks
    /// focus), and every other rendered copy of the same filter — a feed shown under both its
    /// folder group and an expanded tag — is a faint SECONDARY echo.
    private func selectionBackground(for instance: FeedListRowSelection) -> Color {
        if feedListRowSelectionsEqual(instance, home.selectedRowInstance) {
            return focusedPane.wrappedValue == .feedList ? Color.accentColor.opacity(0.25) : Color.secondary.opacity(0.2)
        }
        if articleFiltersEqual(instance.filter, home.filter) {
            return Color.accentColor.opacity(0.10)
        }
        return .clear
    }

}

/// A folder/tag header's expand/collapse control — a button of its own so that clicking the rest
/// of the header selects it instead, as in the Compose app. A free function (not a `FeedListView`
/// method) so `TagHeaderRow`, a separate `View`, can share it.
private func expandChevron(expanded: Bool, toggle: @escaping () -> Void) -> some View {
    Button(action: toggle) {
        Image(systemName: expanded ? "chevron.down" : "chevron.right")
            .foregroundStyle(.secondary)
            .font(.caption)
            .frame(width: 12)
            .contentShape(Rectangle())
    }
    .buttonStyle(.plain)
    .accessibilityLabel(L(expanded ? "home_collapse" : "home_expand"))
}

@ViewBuilder
private func unreadBadge(_ count: Int64) -> some View {
    if count > 0 {
        Text("\(count)")
            .font(.caption2)
            .foregroundStyle(.secondary)
    }
}

private extension View {
    /// A sidebar row's click-to-select, deliberately *not* a `Button`: on macOS a `Button` runs its
    /// own mouse-tracking loop from mouse-down to mouse-up, which swallows the drag gesture, so a
    /// row wrapped in one can never start a feed-list drag. A tap gesture fails as soon as the
    /// pointer moves, leaving the drag free to begin. The button trait and default action keep the
    /// row announced and activatable exactly as a `Button` was under VoiceOver.
    func selectsOnClick(isSelected: Bool, perform select: @escaping () -> Void) -> some View {
        onTapGesture(perform: select)
            .accessibilityElement(children: .combine)
            .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
            .accessibilityAction(.default, select)
    }

    /// Paints a row label's selection tint as a capsule background (macOS source-list style) and
    /// widens the tap/click target to the row's full width — `.listRowBackground` does not reliably
    /// paint through `.sidebar`-style `List` rows, so the tint has to live on the label itself.
    func selectableRowLabel(_ background: Color) -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .padding(.horizontal, 4)
            .background(RoundedRectangle(cornerRadius: 5).fill(background))
    }

    /// Tags a sidebar row with `key` for `ScrollViewProxy.scrollTo` and records whether it is
    /// currently on screen in `appearedKeys`, so the scroll-to-selection effect in
    /// `FeedListView.body` only ever scrolls a row that actually needs it.
    func trackAppearance(_ key: String, in appearedKeys: Binding<Set<String>>) -> some View {
        id(key)
            .onAppear { appearedKeys.wrappedValue.insert(key) }
            .onDisappear { appearedKeys.wrappedValue.remove(key) }
    }
}

/// Favicon with a letter-avatar fallback while loading or on failure — the SwiftUI equivalent of
/// the Compose app's Coil3 `AsyncImage` usage (`external-spec.md`'s "Both the feed list and article
/// list display favicons").
struct FaviconView: View {
    let url: String?
    let letter: Character?

    var body: some View {
        if let url, let parsed = URL(string: url) {
            AsyncImage(url: parsed) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().scaledToFit()
                default:
                    fallback
                }
            }
        } else {
            fallback
        }
    }

    private var fallback: some View {
        Circle()
            .fill(Color.secondary.opacity(0.3))
            .overlay(
                Text(letter.map(String.init)?.uppercased() ?? "?")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            )
    }
}

/// A tag section's own header row — a distinct `View` (rather than a `FeedListView` method, like
/// the folder header) so its color popover has somewhere stable to hold `@State`.
private struct TagHeaderRow: View {
    let home: HomeObservable
    let dialogs: SidebarDialogState
    let tag: Tags
    let instance: FeedListRowSelection
    let isExpanded: Bool
    var focusedPane: FocusState<HomeFocusedPane?>.Binding
    @Binding var appearedRowKeys: Set<String>
    let selectionBackground: (FeedListRowSelection) -> Color
    let dropIndex: FeedListDropIndex
    @Binding var draggingItem: FeedListDragPayload?
    @Binding var activeBoundary: DropBoundary?
    @Binding var hoveredTagId: String?
    @Binding var hoveredKey: FeedListHoverKey?

    @State private var showingColorPicker = false

    var body: some View {
        HStack {
            colorDot
            Text(tag.name)
            Spacer()
            unreadBadge(home.unreadByTag[tag.id] ?? 0)
            expandChevron(expanded: isExpanded) {
                home.viewModel.toggleTagExpanded(tagId: tag.id)
            }
        }
        .selectableRowLabel(selectionBackground(instance))
        // Highlights while a feed hovers for attachment — matches Compose's own
        // `dropTargetBackground` (`FeedListPane.kt`'s tag row).
        .background(hoveredTagId == tag.id ? Color.accentColor.opacity(0.15) : Color.clear)
        .selectsOnClick(isSelected: feedListRowSelectionsEqual(instance, home.selectedRowInstance)) {
            focusedPane.wrappedValue = .feedList
            home.viewModel.selectFilter(filter: instance.filter, instance: instance)
        }
        .selectsOnContextMenu(id: feedListRowSelectionKey(instance)) { selectForContextMenu() }
        .contextMenu {
            // Opening the menu selects the row first, matching Compose's own
            // `onOpen = { if (!selected) onClick() }` (`FeedListPane.kt`) — the actual selection
            // runs on a right-click/Control-click via `.selectsOnContextMenu` above, not as a side
            // effect of this builder (see `ContextMenuSelectionTracker`'s own doc for why).
            Button(L("home_edit_tag_menu")) { dialogs.renamingTag = tag }
            Button(L("home_change_tag_color_menu")) { showingColorPicker = true }
            Button(L("home_delete_tag_menu"), role: .destructive) { dialogs.deletingTag = tag }
        }
        .feedListDropTarget(
            FeedListDropTargetTagHeader(tagId: tag.id),
            hoverKey: .tag(tag.id),
            home: home,
            index: dropIndex,
            draggingItem: $draggingItem,
            activeBoundary: $activeBoundary,
            hoveredTagId: $hoveredTagId,
            hoveredKey: $hoveredKey
        )
        .trackAppearance(feedListRowSelectionKey(instance), in: $appearedRowKeys)
    }

    /// The color dot doubles as its own click target — tapping it opens the color popover directly,
    /// matching Compose's own dot (`FeedListPane.kt`'s `clickable(onClickLabel = colorLabel)`),
    /// without also triggering the surrounding row `Button`'s select action.
    private var colorDot: some View {
        Circle()
            .fill(colorFromHex(tag.color))
            .frame(width: 10, height: 10)
            .contentShape(Circle())
            .onTapGesture { showingColorPicker = true }
            .popover(isPresented: $showingColorPicker) {
                colorPickerContent
            }
            .accessibilityLabel(L("home_tag_color"))
    }

    private var colorPickerContent: some View {
        HStack(spacing: 8) {
            colorSwatch(nil)
            ForEach(TagColorsKt.TAG_COLOR_PALETTE, id: \.self) { hex in colorSwatch(hex) }
        }
        .padding(12)
    }

    /// Applies immediately on tap — there is nothing to confirm, matching Compose's own
    /// `TagColorPickerPopup` (`TagColorPicker.kt`).
    private func colorSwatch(_ hex: String?) -> some View {
        Circle()
            .fill(colorFromHex(hex))
            .frame(width: 20, height: 20)
            .overlay(Circle().strokeBorder(Color.primary, lineWidth: tag.color == hex ? 2 : 0))
            .onTapGesture {
                home.viewModel.updateTag(id: tag.id, name: tag.name, color: hex)
                showingColorPicker = false
            }
    }

    private func selectForContextMenu() {
        guard !feedListRowSelectionsEqual(instance, home.selectedRowInstance) else { return }
        home.viewModel.selectFilter(filter: instance.filter, instance: instance)
    }
}

/// The three sidebar `ViewModifier`s below exist only to keep `FeedListView.body`'s own modifier
/// chain short — chaining all of M3's sheets/alerts directly onto `body` made a single expression
/// too complex for the type checker ("unable to type-check this expression in reasonable time").

private struct SidebarCreateSheets: ViewModifier {
    let home: HomeObservable
    @Bindable var dialogs: SidebarDialogState

    func body(content: Content) -> some View {
        content
            .sheet(isPresented: $dialogs.isAddingFeed) {
                AddFeedSheet(home: home, isPresented: $dialogs.isAddingFeed)
            }
            .sheet(isPresented: $dialogs.isAddingFolder) {
                NamePromptSheet(
                    titleKey: "home_add_folder",
                    placeholderKey: "home_new_folder_hint",
                    duplicateMessageKey: "home_folder_name_duplicate",
                    isDuplicate: { NameValidationKt.isDuplicateFolderName(name: $0, folders: home.folders, excludeId: nil) },
                    onConfirm: { name, _ in _ = home.viewModel.createFolder(name: name) },
                    isPresented: $dialogs.isAddingFolder
                )
            }
            .sheet(isPresented: $dialogs.isAddingTag) {
                NamePromptSheet(
                    titleKey: "home_add_tag",
                    placeholderKey: "home_new_tag_hint",
                    duplicateMessageKey: "home_tag_name_duplicate",
                    // No color by default, matching Compose's own add-tag dialog
                    // (`FeedListDialogs.kt`'s `var color by remember { mutableStateOf<String?>(null) }`).
                    showColorPicker: true,
                    isDuplicate: { NameValidationKt.isDuplicateTagName(name: $0, tags: home.tags, excludeId: nil) },
                    onConfirm: { name, color in _ = home.viewModel.createTag(name: name, color: color) },
                    isPresented: $dialogs.isAddingTag
                )
            }
            // A feed row's "Move to Folder ▸ New folder…" / "Assign tags ▸ New tag…" — mirrors
            // Compose's own `creatingFolderForFeedId`/`creatingTagForFeedId` (`FeedListDialogs.kt`):
            // the created folder/tag is applied to that feed on confirm.
            .sheet(item: $dialogs.creatingFolderForFeed) { feed in
                NamePromptSheet(
                    titleKey: "home_new_folder",
                    placeholderKey: "home_new_folder_hint",
                    duplicateMessageKey: "home_folder_name_duplicate",
                    isDuplicate: { NameValidationKt.isDuplicateFolderName(name: $0, folders: home.folders, excludeId: nil) },
                    onConfirm: { name, _ in
                        if let id = home.viewModel.createFolder(name: name) {
                            home.viewModel.moveFeed(feedId: feed.id, folderId: id, targetFeedId: nil)
                        }
                    },
                    isPresented: Binding(get: { dialogs.creatingFolderForFeed != nil }, set: { if !$0 { dialogs.creatingFolderForFeed = nil } })
                )
            }
            .sheet(item: $dialogs.creatingTagForFeed) { feed in
                NamePromptSheet(
                    titleKey: "home_new_tag",
                    placeholderKey: "home_new_tag_hint",
                    duplicateMessageKey: "home_tag_name_duplicate",
                    showColorPicker: true,
                    isDuplicate: { NameValidationKt.isDuplicateTagName(name: $0, tags: home.tags, excludeId: nil) },
                    onConfirm: { name, color in
                        if let id = home.viewModel.createTag(name: name, color: color) {
                            home.viewModel.setFeedTag(feedId: feed.id, tagId: id, attached: true)
                        }
                    },
                    isPresented: Binding(get: { dialogs.creatingTagForFeed != nil }, set: { if !$0 { dialogs.creatingTagForFeed = nil } })
                )
            }
    }
}

private struct SidebarRenameSheets: ViewModifier {
    let home: HomeObservable
    @Bindable var dialogs: SidebarDialogState

    func body(content: Content) -> some View {
        content
            .sheet(item: $dialogs.renamingFolder) { folder in
                NamePromptSheet(
                    titleKey: "home_edit_folder_menu",
                    placeholderKey: "home_new_folder_hint",
                    duplicateMessageKey: "home_folder_name_duplicate",
                    initialName: folder.name,
                    isDuplicate: { NameValidationKt.isDuplicateFolderName(name: $0, folders: home.folders, excludeId: folder.id) },
                    onConfirm: { name, _ in home.viewModel.updateFolder(id: folder.id, name: name) },
                    isPresented: Binding(get: { dialogs.renamingFolder != nil }, set: { if !$0 { dialogs.renamingFolder = nil } })
                )
            }
            .sheet(item: $dialogs.renamingTag) { tag in
                NamePromptSheet(
                    titleKey: "home_edit_tag_menu",
                    placeholderKey: "home_new_tag_hint",
                    duplicateMessageKey: "home_tag_name_duplicate",
                    initialName: tag.name,
                    // Keeps the tag's existing color (nil included) unless the user actually picks
                    // one — Compose's own rename never changes `tag.color` on its own
                    // (`FeedListPane.kt`'s `onEdit`), so pre-selecting a default here would apply a
                    // color the user never touched.
                    initialColor: tag.color,
                    showColorPicker: true,
                    isDuplicate: { NameValidationKt.isDuplicateTagName(name: $0, tags: home.tags, excludeId: tag.id) },
                    onConfirm: { name, color in home.viewModel.updateTag(id: tag.id, name: name, color: color) },
                    isPresented: Binding(get: { dialogs.renamingTag != nil }, set: { if !$0 { dialogs.renamingTag = nil } })
                )
            }
            .sheet(item: $dialogs.renamingFeed) { feed in
                NamePromptSheet(
                    titleKey: "home_rename_feed",
                    placeholderKey: "apple_rename_feed_hint",
                    // The placeholder shown once the field is cleared is the feed's own parsed
                    // title — what confirming a blank name reverts `custom_title` to (below) —
                    // matching Compose's own inline-rename placeholder (`FeedListDragAndDrop.kt`).
                    placeholderText: feed.title,
                    initialName: feed.displayTitle(),
                    // A blank name is allowed here (unlike a folder/tag): it clears `custom_title`
                    // back to the feed's own fetched title (`FeedRepository.renameFeed`'s
                    // `takeIf { isNotBlank }`, which already treats "" the same as nil).
                    allowBlank: true,
                    isDuplicate: { _ in false },
                    onConfirm: { name, _ in home.viewModel.renameFeed(id: feed.id, title: name) },
                    isPresented: Binding(get: { dialogs.renamingFeed != nil }, set: { if !$0 { dialogs.renamingFeed = nil } })
                )
            }
            // Closes a rename sheet if its target is deleted mid-edit (e.g. a sync merge removes
            // the folder/tag/feed while the sheet is open) — otherwise confirming would write to a
            // row that no longer exists. Mirrors Compose's own auto-cancel when the row stops being
            // rendered (`FeedListPane.kt:400-412`).
            .onChange(of: home.folders) { _, folders in
                if let id = dialogs.renamingFolder?.id, !folders.contains(where: { $0.id == id }) {
                    dialogs.renamingFolder = nil
                }
            }
            .onChange(of: home.tags) { _, tags in
                if let id = dialogs.renamingTag?.id, !tags.contains(where: { $0.id == id }) {
                    dialogs.renamingTag = nil
                }
            }
            .onChange(of: home.feeds) { _, feeds in
                if let id = dialogs.renamingFeed?.id, !feeds.contains(where: { $0.id == id }) {
                    dialogs.renamingFeed = nil
                }
            }
    }
}

private struct SidebarDeleteAlerts: ViewModifier {
    let home: HomeObservable
    @Bindable var dialogs: SidebarDialogState

    func body(content: Content) -> some View {
        content
            .alert(
                dialogs.deletingFolder.map { LF("home_delete_folder_confirm", $0.name) } ?? "",
                isPresented: isPresentedBinding($dialogs.deletingFolder),
                presenting: dialogs.deletingFolder
            ) { folder in
                Button(L("common_delete"), role: .destructive) { home.viewModel.deleteFolder(id: folder.id) }
                Button(L("common_cancel"), role: .cancel) {}
            }
            .alert(
                dialogs.deletingTag.map { LF("home_delete_tag_confirm", $0.name) } ?? "",
                isPresented: isPresentedBinding($dialogs.deletingTag),
                presenting: dialogs.deletingTag
            ) { tag in
                Button(L("common_delete"), role: .destructive) { home.viewModel.deleteTag(id: tag.id) }
                Button(L("common_cancel"), role: .cancel) {}
            }
            .alert(
                dialogs.unsubscribingFeed.map { LF("home_unsubscribe_title", $0.custom_title ?? $0.title) } ?? "",
                isPresented: isPresentedBinding($dialogs.unsubscribingFeed),
                presenting: dialogs.unsubscribingFeed
            ) { feed in
                Button(L("home_unsubscribe_menu"), role: .destructive) { home.viewModel.unsubscribeFeed(id: feed.id) }
                Button(L("common_cancel"), role: .cancel) {}
            } message: { _ in
                Text(L("home_unsubscribe_body"))
            }
    }

    private func isPresentedBinding<T>(_ source: Binding<T?>) -> Binding<Bool> {
        Binding(get: { source.wrappedValue != nil }, set: { if !$0 { source.wrappedValue = nil } })
    }
}
