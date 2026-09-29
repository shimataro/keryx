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

    /// Whether the Folders / Tags section headers are expanded. Native-only view state (Compose has
    /// no equivalent), kept apart from the per-folder / per-tag disclosure state in `home`.
    @AppStorage("sidebar.foldersExpanded") private var foldersExpanded = true
    @AppStorage("sidebar.tagsExpanded") private var tagsExpanded = true

    // Drag-and-drop state: the item being dragged, and which row a dragged feed is over for the
    // drop-onto highlight and spring-loading — mirrors Compose's own
    // `draggedFeedIdState`/`hoveredAttachTagIdState` (`FeedListDragController.kt`). Insertion
    // between rows is the outline's own `.onInsert`, which draws the insertion line itself. See
    // `FeedListDragAndDrop.swift` for the drop wiring and `FeedListDropPresentation.swift` for the
    // rules behind it.
    @State private var draggingItem: FeedListDragPayload?
    @State private var dropOnKey: FeedListHoverKey?
    @State private var hoveredKey: FeedListHoverKey?

    private var selectedRowKey: String { feedListRowSelectionKey(home.selectedRowInstance) }

    /// The sidebar's structure (sort orders, groupings, drop index, row order), derived once per
    /// change by `HomeObservable` — see `SidebarModel`.
    private var sidebar: SidebarModel { home.sidebar }
    private var dropIndex: FeedListDropIndex { sidebar.dropIndex }

    var body: some View {
        VStack(spacing: 0) {
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
                    // Starting an in-place rename scrolls its row into view, like Compose's
                    // `LaunchedEffect(inlineEdit)` (`FeedListPane.kt`).
                    .onChange(of: dialogs.renamingRowKey) { _, key in
                        guard let key, !appearedRowKeys.contains(key) else { return }
                        proxy.scrollTo(key)
                    }
            }
        }
        .toolbar { toolbarContent }
        // The system search field. Its focus is reported into `focusedPane` (for ⌘F, the
        // `textInputFocused` guard and the ↓/↑ hand-off into the results — see `HomeScreen.kt`'s
        // own `focusSearch`/`moveArticleSelectionFromSearchField`) only from macOS 15 / iOS 18,
        // where `.searchFocused(_:equals:)` exists; earlier systems get the field without them.
        .searchable(text: searchQueryBinding, placement: .sidebar, prompt: L("home_search_placeholder"))
        .modifier(SearchFocusModifier(focusedPane: focusedPane))
        // Spring-loaded folder: holding a dragged feed over a collapsed folder opens it after a
        // short pause, so its feeds become reachable drop targets mid-drag — matches Compose's own
        // `LaunchedEffect(isFeedDragHighlight, collapsed)` (`FeedListDragAndDrop.kt`). The pause and
        // whether to spring at all follow the system's own spring-loading setting, as Finder does.
        // Re-checks `hoveredKey` after the delay so releasing/moving away first cancels the expand.
        .onChange(of: hoveredKey) { _, key in
            guard draggingItem?.kind == .feed,
                  case .folder(let folderId) = key,
                  home.collapsedFolderIds.contains(folderId),
                  let delay = springLoadingDelay() else { return }
            Task {
                try? await Task.sleep(for: delay)
                guard hoveredKey == .folder(folderId) else { return }
                withAnimation { home.viewModel.toggleFolderCollapsed(folderId: folderId) }
            }
        }
        .modifier(SidebarCreateSheets(home: home, dialogs: dialogs))
        // Ends an in-place rename whose row stopped being rendered — deleted, removed by a sync
        // merge, or a tag/folder collapsed over it — otherwise confirming would write to a row that
        // is no longer there. Mirrors Compose's own auto-cancel (`FeedListPane.kt:402-413`).
        .onChange(of: sidebar.orderedRowKeys) { _, keys in
            if let key = dialogs.renamingRowKey, !keys.contains(key) {
                dialogs.renamingRowKey = nil
            }
        }
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

    /// A native source list — the same `NSOutlineView` sidebar Notes and Finder use — so row
    /// height, font, icon size (System Settings > Appearance > Sidebar icon size), selection shape,
    /// disclosure triangles and indentation all come from the system rather than being drawn here.
    /// Sections mirror Compose's own grouping: All/Starred, the folders, the always-present
    /// "No folder" group, then the tags. Groups are told apart the way Finder and Mail do it — by
    /// their section headers (the Folders and Tags ones collapse), not by divider lines — and the
    /// spacing between them is left to the system.
    @ViewBuilder
    private var listContent: some View {
        List(selection: selectionKeyBinding) {
            Section {
                allRow
                starredRow
            }
            if !sortedFolders.isEmpty {
                Section(L("home_folders"), isExpanded: $foldersExpanded) {
                    ForEach(sortedFolders, id: \.id) { folder in
                        folderGroup(folder)
                    }
                    .onInsert(of: [feedListFolderDragType]) { offset, _ in
                        insert(into: .folders(folderIds: sortedFolders.map(\.id)), at: offset)
                    }
                }
            }
            // Always present — even with no unassigned feeds — so a feed can still be dragged out
            // of every folder into "no folder" (D3: without this header there would be no drop
            // target for that when the group is otherwise empty). Compose shows the same header
            // unconditionally (`FeedListPane.kt`'s own "No folder" section).
            Section {
                ForEach(unassignedFeeds, id: \.id) { feed in
                    feedRow(feed, instance: FeedListRowSelectionFeedInFolderGroup(feedId: feed.id))
                }
                .onInsert(of: [feedListFeedDragType]) { offset, _ in
                    insert(into: .feeds(folderId: nil, feedIds: unassignedFeeds.map(\.id)), at: offset)
                }
            } header: {
                noFolderHeader
            }
            if !sortedTags.isEmpty {
                Section(L("home_tags"), isExpanded: $tagsExpanded) {
                    ForEach(sortedTags, id: \.id) { tag in
                        tagGroup(tag)
                    }
                }
            }
        }
        // ←/→ move between panes (`external-spec.md` §9), so they are taken here before the
        // outline's own expand/collapse handling sees them; the disclosure triangles still expand
        // and collapse.
        .onKeyPress(.rightArrow) {
            moveFocusFromFeedListToArticleList(home: home, focusedPane: focusedPane)
            return .handled
        }
        .onKeyPress(.leftArrow) { .handled }
    }

    /// Bridges the native `List` selection (row tags) to the shared selection. A `nil` (clicking
    /// empty space, Command-clicking the selected row) or unknown key is ignored, so the sidebar
    /// always keeps a selection, as Compose's does.
    private var selectionKeyBinding: Binding<String?> {
        Binding(
            get: { selectedRowKey },
            set: { key in
                guard let key, key != selectedRowKey,
                      let instance = feedListRowSelection(forKey: key, in: orderedRows) else { return }
                focusedPane.wrappedValue = .feedList
                home.viewModel.selectFilter(filter: instance.filter, instance: instance)
            }
        )
    }

    /// A drop between rows, from a `ForEach`'s `.onInsert` — see `performFeedListInsert`.
    private func insert(into group: FeedListInsertGroup, at offset: Int) {
        dropOnKey = nil
        hoveredKey = nil
        performFeedListInsert(into: group, at: offset, draggingItem: $draggingItem, index: dropIndex, home: home)
    }

    private var orderedRows: [FeedListRowSelection] { sidebar.orderedRows }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem {
            Menu {
                Button(L("menu_file_add_feed")) { dialogs.isAddingFeed = true }
                    #if os(macOS)
                    // Displays the ⌘N that `HomeCommands` binds in the File menu; both set the same flag.
                    .keyboardShortcut("n", modifiers: .command)
                    #endif
                Button(L("menu_file_add_folder")) { dialogs.isAddingFolder = true }
                Button(L("menu_file_add_tag")) { dialogs.isAddingTag = true }
            } label: {
                Label(L("home_add"), systemImage: "plus")
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
                // A `Label` rather than a bare icon so the toolbar's overflow menu (shown when the
                // sidebar is collapsed) gets a title; the toolbar itself still renders icon-only.
                // The title stays fixed while the spinner replaces the icon, so VoiceOver always
                // announces what the button does.
                Label {
                    Text(L("home_refresh"))
                } icon: {
                    if home.activity.refreshIndicatorShown {
                        ProgressView().controlSize(.small)
                    } else {
                        Image(systemName: "arrow.clockwise")
                    }
                }
            }
            .disabled(!home.activity.idle)
            .help(L(home.activity.refreshIndicatorShown ? "home_refreshing" : "home_refresh"))
        }
        if home.cloudConnected {
            ToolbarItem {
                Button {
                    home.viewModel.sync()
                } label: {
                    Label {
                        Text(L("home_sync"))
                    } icon: {
                        if home.activity.syncing {
                            ProgressView().controlSize(.small)
                        } else {
                            Image(systemName: "icloud")
                        }
                    }
                }
                .disabled(!home.activity.idle)
                .help(L(home.activity.syncing ? "home_syncing" : "home_sync"))
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

    // MARK: - In-place rename

    /// The name editor for the row `instance`, or `nil` when that row isn't being renamed.
    private func renameEditor(
        for instance: FeedListRowSelection,
        initialName: String,
        placeholder: String = "",
        allowBlank: Bool = false,
        blockingError: @escaping (String) -> String? = { _ in nil },
        commit: @escaping (String) -> Void
    ) -> InlineRenameField? {
        guard dialogs.renamingRowKey == feedListRowSelectionKey(instance) else { return nil }
        return InlineRenameField(
            initialName: initialName,
            placeholder: placeholder,
            allowBlank: allowBlank,
            blockingError: blockingError,
            onCommit: { name in
                dialogs.renamingRowKey = nil
                commit(name)
            },
            onCancel: { dialogs.renamingRowKey = nil },
            focusedPane: focusedPane
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
            systemImage: "star",
            unreadCount: home.starredUnreadCount,
            instance: FeedListRowSelectionStarred(),
        )
    }

    // MARK: - Folders

    private var sortedFolders: [Folders] { sidebar.sortedFolders }

    private var unassignedFeeds: [Feeds] { sidebar.unassignedFeeds }

    private func feedsIn(folder: Folders) -> [Feeds] { sidebar.feeds(inFolder: folder.id) }

    /// The "No folder" section header — not itself a selectable filter (there is no
    /// `ArticleFilter` for "every unfoldered feed"), only a drop target for moving a feed out of
    /// every folder, matching Compose's own header (`FeedListPane.kt`).
    private var noFolderHeader: some View {
        Text(L("home_no_folder"))
            .feedListDropHighlight(dropOnKey == .noFolder)
            .frame(maxWidth: .infinity, alignment: .leading)
            .feedListDropTarget(
                FeedListDropTargetNoFolderHeader.shared,
                hoverKey: .noFolder,
                home: home,
                index: dropIndex,
                draggingItem: $draggingItem,
                dropOnKey: $dropOnKey,
                hoveredKey: $hoveredKey
            )
    }

    /// `toggleFolderCollapsed` flips the state, so it only runs when the requested state differs —
    /// a repeated `set` with the same value must not flip it back.
    private func folderExpandedBinding(_ folderId: String) -> Binding<Bool> {
        Binding(
            get: { !home.collapsedFolderIds.contains(folderId) },
            set: { expanded in
                guard expanded == home.collapsedFolderIds.contains(folderId) else { return }
                home.viewModel.toggleFolderCollapsed(folderId: folderId)
            }
        )
    }

    private func folderGroup(_ folder: Folders) -> some View {
        let instance = FeedListRowSelectionFolder(folderId: folder.id)
        return DisclosureGroup(isExpanded: folderExpandedBinding(folder.id)) {
            ForEach(feedsIn(folder: folder), id: \.id) { feed in
                feedRow(feed, instance: FeedListRowSelectionFeedInFolderGroup(feedId: feed.id))
            }
            .onInsert(of: [feedListFeedDragType]) { offset, _ in
                insert(into: .feeds(folderId: folder.id, feedIds: feedsIn(folder: folder).map(\.id)), at: offset)
            }
        } label: {
            Label {
                if let editor = renameEditor(
                    for: instance,
                    initialName: folder.name,
                    blockingError: { NameValidationKt.isDuplicateFolderName(name: $0, folders: home.folders, excludeId: folder.id) ? L("home_folder_name_duplicate") : nil },
                    commit: { home.viewModel.updateFolder(id: folder.id, name: $0) }
                ) {
                    editor
                } else {
                    Text(folder.name).lineLimit(1)
                }
            } icon: {
                Image(systemName: "folder")
            }
                .sidebarRowHighlight(dropOnKey == .folder(folder.id) ? .drop : highlight(for: instance))
                .badge(Int(home.unreadByFolder[folder.id] ?? 0))
                .selectsOnContextMenu(id: feedListRowSelectionKey(instance)) { selectForContextMenu(instance) }
                .contextMenu {
                    // Opening the menu selects the row first, matching Compose's own
                    // `onOpen = { if (!selected) onClick() }` (`FeedListDragAndDrop.kt`) — the actual
                    // selection runs on a right-click/Control-click via `.selectsOnContextMenu` above,
                    // not as a side effect of this builder (see `ContextMenuSelectionTracker`'s own doc
                    // for why).
                    Button(L("home_edit_folder_menu")) { dialogs.startRename(instance) }
                    Button(L("home_delete_folder_menu"), role: .destructive) { dialogs.deletingFolder = folder }
                }
                .feedListDraggable(
                    FeedListDragPayload(kind: .folder, id: folder.id),
                    draggingItem: $draggingItem,
                    enabled: dialogs.renamingRowKey != feedListRowSelectionKey(instance)
                )
                .feedListDropTarget(
                    FeedListDropTargetFolderHeader(folderId: folder.id),
                    hoverKey: .folder(folder.id),
                    home: home,
                    index: dropIndex,
                    draggingItem: $draggingItem,
                    dropOnKey: $dropOnKey,
                    hoveredKey: $hoveredKey
                )
                .tag(feedListRowSelectionKey(instance))
                .trackAppearance(feedListRowSelectionKey(instance), in: $appearedRowKeys)
        }
    }

    // MARK: - Tags

    private var sortedTags: [Tags] { sidebar.sortedTags }

    private func feeds(taggedWith tag: Tags) -> [Feeds] { sidebar.feeds(taggedWith: tag.id) }

    /// `toggleTagExpanded` flips the state — see `folderExpandedBinding`.
    private func tagExpandedBinding(_ tagId: String) -> Binding<Bool> {
        Binding(
            get: { home.expandedTagIds.contains(tagId) },
            set: { expanded in
                guard expanded != home.expandedTagIds.contains(tagId) else { return }
                home.viewModel.toggleTagExpanded(tagId: tagId)
            }
        )
    }

    private func tagGroup(_ tag: Tags) -> some View {
        let instance = FeedListRowSelectionTag(tagId: tag.id)
        return DisclosureGroup(isExpanded: tagExpandedBinding(tag.id)) {
            ForEach(feeds(taggedWith: tag), id: \.id) { feed in
                feedRow(feed, instance: FeedListRowSelectionFeedInTag(feedId: feed.id, tagId: tag.id))
            }
        } label: {
            TagRowLabel(
                home: home,
                dialogs: dialogs,
                tag: tag,
                instance: instance,
                focusedPane: focusedPane,
                appearedRowKeys: $appearedRowKeys,
                highlight: highlight(for:),
                dropIndex: dropIndex,
                draggingItem: $draggingItem,
                dropOnKey: $dropOnKey,
                hoveredKey: $hoveredKey
            )
        }
    }

    // MARK: - Rows

    /// A feed row is only ever a drag *source*: a feed is dropped between feed rows (the
    /// enclosing `ForEach`'s `.onInsert`), never onto one. A copy nested under an expanded tag is
    /// draggable too, but its tag's `ForEach` takes no insertions — matching Compose's own
    /// `FeedListRowKey.Other` classification for it (`FeedListDragAndDrop.kt`'s own
    /// `parseFeedListRowKey`).
    private func feedRow(_ feed: Feeds, instance: FeedListRowSelection) -> some View {
        row(
            title: feed.displayTitle(),
            faviconUrl: feed.favicon_url,
            unreadCount: home.unreadByFeed[feed.id] ?? 0,
            isErroring: feed.error_count > 0 || feed.last_error == ConstantsKt.FEED_ERROR_REASON_GONE,
            isGone: feed.last_error == ConstantsKt.FEED_ERROR_REASON_GONE,
            instance: instance,
            editor: renameEditor(
                for: instance,
                initialName: feed.displayTitle(),
                // What clearing the name falls back to: the feed's own parsed title.
                placeholder: feed.title,
                // Blank clears `custom_title` (`FeedRepository.renameFeed`); there is no duplicate check.
                allowBlank: true,
                commit: { home.viewModel.renameFeed(id: feed.id, title: $0) }
            ),
        )
        .feedListDraggable(
            FeedListDragPayload(kind: .feed, id: feed.id),
            draggingItem: $draggingItem,
            enabled: dialogs.renamingRowKey != feedListRowSelectionKey(instance)
        )
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
            Button(L("home_rename_feed")) { dialogs.startRename(instance) }
            Divider()
            Button(L("home_unsubscribe_menu"), role: .destructive) { dialogs.unsubscribingFeed = feed }
        }
    }

    private func row(
        title: String,
        systemImage: String? = nil,
        faviconUrl: String? = nil,
        unreadCount: Int64,
        isErroring: Bool = false,
        isGone: Bool = false,
        instance: FeedListRowSelection,
        editor: InlineRenameField? = nil,
    ) -> some View {
        Label {
            HStack(spacing: 4) {
                if let editor {
                    editor
                } else {
                    Text(title).lineLimit(1)
                }
                if isErroring {
                    Spacer(minLength: 0)
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
            }
        } icon: {
            if let systemImage {
                Image(systemName: systemImage)
            } else {
                FaviconView(url: faviconUrl, letter: title.first)
                    .frame(width: 16, height: 16)
            }
        }
        .sidebarRowHighlight(highlight(for: instance))
        .badge(Int(unreadCount))
        .tag(feedListRowSelectionKey(instance))
        .trackAppearance(feedListRowSelectionKey(instance), in: $appearedRowKeys)
    }

    /// Selects `instance` if it isn't already the primary selection — called when a row's context
    /// menu opens, matching Compose's own `onOpen = { if (!selected) onClick() }`
    /// (`FeedListDragAndDrop.kt`).
    private func selectForContextMenu(_ instance: FeedListRowSelection) {
        guard !feedListRowSelectionsEqual(instance, home.selectedRowInstance) else { return }
        home.viewModel.selectFilter(filter: instance.filter, instance: instance)
    }

    /// `.echo` — the faint SECONDARY tone of the Compose app's `RowSelectionTone` (`FeedListPane.kt`'s
    /// `toneFor`) — for every *other* rendered copy of the selected filter: a feed shown under both
    /// its folder group and an expanded tag. The selected row itself is the native list selection,
    /// which already dims while the sidebar lacks focus, as Compose's PRIMARY tone does.
    private func highlight(for instance: FeedListRowSelection) -> SidebarRowHighlight {
        guard !feedListRowSelectionsEqual(instance, home.selectedRowInstance),
              articleFiltersEqual(instance.filter, home.filter) else { return .none }
        return .echo
    }
}

private extension View {
    /// The drop highlight alone, for the "No folder" section header — a header has no selection
    /// shape to match, so the highlight hugs its title.
    func feedListDropHighlight(_ isOn: Bool) -> some View {
        foregroundStyle(isOn ? AnyShapeStyle(Color.white) : AnyShapeStyle(.secondary))
            .padding(.horizontal, isOn ? 4 : 0)
            .background {
                RoundedRectangle(cornerRadius: 4).fill(isOn ? Color.accentColor : .clear)
            }
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
/// list display favicons"). Decoded images are shared through `FaviconCache`, so a row scrolled back
/// into view shows its favicon at once instead of reloading it.
struct FaviconView: View {
    let url: String?
    let letter: Character?
    /// Leaves the slot empty when there is no favicon URL, as Compose's article row does (the
    /// feed list keeps the letter avatar).
    var blankWithoutUrl = false

    /// The image this view loaded, tagged with the URL it was loaded for so a reused view never
    /// shows the previous URL's image.
    @State private var loaded: (url: String, image: PlatformImage)?

    var body: some View {
        if let url, let parsed = URL(string: url) {
            Group {
                if let image = currentImage(for: url) {
                    Image(platformImage: image).resizable().scaledToFit()
                } else {
                    fallback
                }
            }
            .task(id: url) {
                guard currentImage(for: url) == nil, let image = await FaviconCache.shared.image(for: parsed) else { return }
                loaded = (url, image)
            }
        } else if blankWithoutUrl {
            Color.clear
        } else {
            fallback
        }
    }

    private func currentImage(for url: String) -> PlatformImage? {
        if let loaded, loaded.url == url { return loaded.image }
        return FaviconCache.shared.cachedImage(for: url)
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

/// A tag's own row, the label of its `DisclosureGroup` — a distinct `View` (rather than a
/// `FeedListView` method, like the folder row) so its color popover has somewhere stable to hold
/// `@State`.
private struct TagRowLabel: View {
    let home: HomeObservable
    let dialogs: SidebarDialogState
    let tag: Tags
    let instance: FeedListRowSelection
    var focusedPane: FocusState<HomeFocusedPane?>.Binding
    @Binding var appearedRowKeys: Set<String>
    let highlight: (FeedListRowSelection) -> SidebarRowHighlight
    let dropIndex: FeedListDropIndex
    @Binding var draggingItem: FeedListDragPayload?
    @Binding var dropOnKey: FeedListHoverKey?
    @Binding var hoveredKey: FeedListHoverKey?

    @State private var showingColorPicker = false

    var body: some View {
        Label {
            if dialogs.renamingRowKey == feedListRowSelectionKey(instance) {
                InlineRenameField(
                    initialName: tag.name,
                    blockingError: { NameValidationKt.isDuplicateTagName(name: $0, tags: home.tags, excludeId: tag.id) ? L("home_tag_name_duplicate") : nil },
                    // The tag's color is kept as it is — it has its own menu item and dot popover.
                    onCommit: { name in
                        dialogs.renamingRowKey = nil
                        home.viewModel.updateTag(id: tag.id, name: name, color: tag.color)
                    },
                    onCancel: { dialogs.renamingRowKey = nil },
                    focusedPane: focusedPane
                )
            } else {
                Text(tag.name).lineLimit(1)
            }
        } icon: {
            colorDot
        }
        // Highlights while a feed hovers for attachment — matches Compose's own
        // `dropTargetBackground` (`FeedListPane.kt`'s tag row).
        .sidebarRowHighlight(dropOnKey == .tag(tag.id) ? .drop : highlight(instance))
        .badge(Int(home.unreadByTag[tag.id] ?? 0))
        .selectsOnContextMenu(id: feedListRowSelectionKey(instance)) { selectForContextMenu() }
        .contextMenu {
            // Opening the menu selects the row first, matching Compose's own
            // `onOpen = { if (!selected) onClick() }` (`FeedListPane.kt`) — the actual selection
            // runs on a right-click/Control-click via `.selectsOnContextMenu` above, not as a side
            // effect of this builder (see `ContextMenuSelectionTracker`'s own doc for why).
            Button(L("home_edit_tag_menu")) { dialogs.startRename(instance) }
            Button(L("home_change_tag_color_menu")) { showingColorPicker = true }
            Button(L("home_delete_tag_menu"), role: .destructive) { dialogs.deletingTag = tag }
        }
        .feedListDropTarget(
            FeedListDropTargetTagHeader(tagId: tag.id),
            hoverKey: .tag(tag.id),
            home: home,
            index: dropIndex,
            draggingItem: $draggingItem,
            dropOnKey: $dropOnKey,
            hoveredKey: $hoveredKey
        )
        .tag(feedListRowSelectionKey(instance))
        .trackAppearance(feedListRowSelectionKey(instance), in: $appearedRowKeys)
    }

    /// The color dot doubles as its own click target — tapping it opens the color popover directly,
    /// matching Compose's own dot (`FeedListPane.kt`'s `clickable(onClickLabel = colorLabel)`),
    /// without also changing the list selection.
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

/// The two sidebar `ViewModifier`s below exist only to keep `FeedListView.body`'s own modifier
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

/// Binds the sidebar's `.searchable` field to `focusedPane`'s `.search` case where the system
/// supports it (`.searchFocused(_:equals:)` is macOS 15 / iOS 18+); a no-op before that.
private struct SearchFocusModifier: ViewModifier {
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    func body(content: Content) -> some View {
        if #available(macOS 15, iOS 18, *) {
            content.searchFocused(focusedPane, equals: .search)
        } else {
            content
        }
    }
}
