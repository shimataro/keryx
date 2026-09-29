import KeryxShared
import SwiftUI
import UniformTypeIdentifiers

/// The sidebar's rows as a SwiftUI `List` — the source list itself, apart from `FeedListView`'s
/// own frame (toolbar, search field, sheets and alerts).
extension FeedListView {
    /// A native source list — the same `NSOutlineView` sidebar Notes and Finder use — so row
    /// height, font, icon size (System Settings > Appearance > Sidebar icon size), selection shape,
    /// disclosure triangles and indentation all come from the system rather than being drawn here.
    /// Sections mirror Compose's own grouping: All/Starred, the folders, the always-present
    /// "No folder" group, then the tags. Groups are told apart the way Finder and Mail do it — by
    /// their section headers (the Folders and Tags ones collapse), not by divider lines — and the
    /// spacing between them is left to the system.
    @ViewBuilder
    var listContent: some View {
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
    /// always keeps a selection, as Compose's does. Collapsed (iPhone), a tap also opens the article
    /// list — re-tapping the current row included — see `CompactSidebarSelection`.
    private var selectionKeyBinding: Binding<String?> {
        Binding(
            get: {
                CompactSidebarSelection.displayedKey(selectedKey: selectedRowKey, sidebarIsTopmost: sidebarIsTopmost)
            },
            set: { key in
                guard let key,
                      let instance = feedListRowSelection(forKey: key, in: orderedRows) else { return }
                let tap = CompactSidebarSelection.tap(key: key, selectedKey: selectedRowKey)
                if tap.changesFilter {
                    focusedPane.wrappedValue = .feedList
                    home.viewModel.selectFilter(filter: instance.filter, instance: instance)
                }
                if tap.navigates { onOpenArticleList() }
            }
        )
    }

    /// A drop between rows, from a `ForEach`'s `.onInsert` — see `performFeedListInsert`.
    private func insert(into group: FeedListInsertGroup, at offset: Int) {
        dropOnKey = nil
        hoveredKey = nil
        performFeedListInsert(into: group, at: offset, draggingItem: $draggingItem, index: dropIndex, home: home)
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
            SidebarRowLabel(
                title: folder.name,
                icon: .symbol("folder"),
                editor: folderRenameEditor(folder)
            )
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
                editor: tagRenameEditor(tag),
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
            editor: feedRenameEditor(feed, instance: instance),
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
        SidebarRowLabel(
            title: title,
            icon: systemImage.map(SidebarRowIcon.symbol) ?? .favicon(url: faviconUrl),
            isErroring: isErroring,
            isGone: isGone,
            editor: editor
        )
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

/// A tag's own row, the label of its `DisclosureGroup` — a distinct `View` (rather than a
/// `FeedListView` method, like the folder row) so its color popover has somewhere stable to hold
/// `@State`.
private struct TagRowLabel: View {
    let home: HomeObservable
    let dialogs: SidebarDialogState
    let tag: Tags
    let instance: FeedListRowSelection
    let editor: InlineRenameField?
    @Binding var appearedRowKeys: Set<String>
    let highlight: (FeedListRowSelection) -> SidebarRowHighlight
    let dropIndex: FeedListDropIndex
    @Binding var draggingItem: FeedListDragPayload?
    @Binding var dropOnKey: FeedListHoverKey?
    @Binding var hoveredKey: FeedListHoverKey?

    @State private var showingColorPicker = false

    var body: some View {
        SidebarRowLabel(
            title: tag.name,
            icon: .tagColor(hex: tag.color),
            editor: editor,
            onIconTap: { showingColorPicker = true },
            iconPopoverPresented: $showingColorPicker
        ) {
            TagColorPicker(selectedHex: tag.color) { hex in
                home.viewModel.updateTag(id: tag.id, name: tag.name, color: hex)
                showingColorPicker = false
            }
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

    private func selectForContextMenu() {
        guard !feedListRowSelectionsEqual(instance, home.selectedRowInstance) else { return }
        home.viewModel.selectFilter(filter: instance.filter, instance: instance)
    }
}
