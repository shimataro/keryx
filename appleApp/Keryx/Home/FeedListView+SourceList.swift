import KeryxShared
import SwiftUI
import UniformTypeIdentifiers

#if os(macOS)
/// The macOS sidebar's rows as a SwiftUI `List` — the source list itself, apart from
/// `FeedListView`'s own frame (toolbar, search field, sheets and alerts). iOS draws its rows with a
/// collection view instead (`SidebarCollectionView`).
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
        // `SidebarDropGuard` reads this `List` by its sections, in this order (`SidebarRootSection`):
        // adding, removing or reordering a section means changing that too.
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
                }
            }
            // Always present — even with no unassigned feeds — so a feed can still be dragged out
            // of every folder into "no folder" (D3: without this header there would be no drop
            // target for that when the group is otherwise empty). Compose shows the same header
            // unconditionally (`FeedListPane.kt`'s own "No folder" section).
            Section {
                ForEach(unassignedFeeds, id: \.id) { feed in
                    feedRow(feed, item: .feed(feed.id))
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
        // Every feed and folder drop is decided and applied by the guard, never by SwiftUI's own drop
        // handling — see `SidebarDropGuard`. So the `List` carries no `.onInsert`/`.onDrop`.
        .background(SidebarDropGuard(
            folderCount: sortedFolders.count,
            tagCount: sortedTags.count,
            resolve: dropAction(for:),
            apply: applyDrop(_:)
        ))
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
                guard let key, let instance = sidebar.orderedRowsByKey[key] else { return }
                let tap = CompactSidebarSelection.tap(key: key, selectedKey: selectedRowKey)
                if tap.changesFilter {
                    home.selectFilter(instance.filter, instance: instance)
                    focusedPane.wrappedValue = .feedList
                }
                if tap.navigates { onOpenArticleList() }
            }
        )
    }

    /// The shared action the dragged item takes when dropped at `destination` — a position in the
    /// outline, resolved to ids here and then through `resolveFeedListDropAction`, the same rules the
    /// Compose app applies — or `nil` where the drop would do nothing. `SidebarDropGuard` resolves a
    /// drop with it while the item hovers, and again just before applying it, so a drop goes only
    /// where it was shown.
    private func dropAction(for destination: SidebarDropDestination) -> FeedListDropAction? {
        guard let dragging = draggingItem else { return nil }
        func ontoRow(_ target: FeedListDropTarget) -> FeedListDropAction? {
            // A row's own action doesn't depend on which half of it the item is over.
            FeedListDragKt.resolveFeedListDropAction(item: dragging.toShared(), target: target, half: .top, index: dropIndex)
        }
        func insert(into group: FeedListInsertGroup, at offset: Int) -> FeedListDropAction? {
            feedListInsertAction(into: group, at: offset, dragging: dragging, index: dropIndex)
        }
        let folders = sortedFolders
        switch destination {
        case .folderSlot(let index):
            return insert(into: .folders(folderIds: folders.map(\.id)), at: index)
        case .ontoFolder(let index):
            guard folders.indices.contains(index) else { return nil }
            return ontoRow(FeedListDropTargetFolderHeader(folderId: folders[index].id))
        case .ontoNoFolder:
            return ontoRow(FeedListDropTargetNoFolderHeader.shared)
        case .ontoTag(let index):
            let tags = sortedTags
            guard tags.indices.contains(index) else { return nil }
            return ontoRow(FeedListDropTargetTagHeader(tagId: tags[index].id))
        case .ontoFeedInTag(let index):
            let tags = sortedTags
            guard tags.indices.contains(index) else { return nil }
            return ontoRow(FeedListDropTargetFeedInTag(tagId: tags[index].id))
        case .intoFolder(let index, let offset):
            guard folders.indices.contains(index) else { return nil }
            let folder = folders[index]
            return insert(into: .feeds(folderId: folder.id, feedIds: feedsIn(folder: folder).map(\.id)), at: offset)
        case .intoNoFolder(let offset):
            return insert(into: .feeds(folderId: nil, feedIds: unassignedFeeds.map(\.id)), at: offset)
        }
    }

    private func applyDrop(_ action: FeedListDropAction) {
        draggingItem = nil
        applyFeedListDropAction(action, home: home)
    }

    // MARK: - All / Starred

    private var allRow: some View {
        row(content: .all, unread: .total, selection: rowSelection(.all, FeedListRowSelectionAll()))
    }

    private var starredRow: some View {
        row(content: .starred, unread: .starred, selection: rowSelection(.starred, FeedListRowSelectionStarred()))
    }

    // MARK: - Folders

    /// The "No folder" section header — not itself a selectable filter (there is no
    /// `ArticleFilter` for "every unfoldered feed"), only a drop target for moving a feed out of
    /// every folder, matching Compose's own header (`FeedListPane.kt`). The outline highlights it
    /// while a feed is over it (`SidebarDropGuard`).
    private var noFolderHeader: some View {
        Text(L("home_no_folder"))
            .foregroundStyle(.secondary)
            .frame(maxWidth: .infinity, alignment: .leading)
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
        let selection = rowSelection(.folder(folder.id), FeedListRowSelectionFolder(folderId: folder.id))
        let instance = selection.instance
        return DisclosureGroup(isExpanded: folderExpandedBinding(folder.id)) {
            ForEach(feedsIn(folder: folder), id: \.id) { feed in
                feedRow(feed, item: .feed(feed.id))
            }
        } label: {
            SidebarSourceListRow(
                home: home,
                content: sidebar.folderContents[folder.id] ?? SidebarRowStaticContent(title: folder.name, icon: .symbol("folder")),
                unread: .folder(folder.id),
                highlight: highlight(for: instance),
                editor: folderRenameEditor(folder)
            )
                .equatable()
                .selectsOnContextMenu(id: selection.key) { selectForContextMenu(instance) }
                .contextMenu {
                    // Opening the menu selects the row first, matching Compose's own
                    // `onOpen = { if (!selected) onClick() }` (`FeedListRows.kt`) — the actual
                    // selection runs on a right-click/Control-click via `.selectsOnContextMenu` above,
                    // not as a side effect of this builder (see `ContextMenuSelectionTracker`'s own doc
                    // for why).
                    Button(L("home_edit_folder_menu")) { dialogs.startRename(instance) }
                    Button(L("home_delete_folder_menu"), role: .destructive) { dialogs.deletingFolder = folder }
                }
                .feedListDraggable(
                    FeedListDragPayload(kind: .folder, id: folder.id),
                    draggingItem: $draggingItem,
                    enabled: dialogs.renamingRowKey != selection.key
                )
                .tag(selection.key)
                .trackAppearance(selection.key, in: $appearedRowKeys)
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
        let selection = rowSelection(.tag(tag.id), FeedListRowSelectionTag(tagId: tag.id))
        return DisclosureGroup(isExpanded: tagExpandedBinding(tag.id)) {
            // Identified by tag and feed, not by the feed alone: the same feed's row elsewhere in the
            // `List` must not share its identity, or the outline behind it takes one row for the
            // other — highlighting the wrong row mid-drag, and proposing a drop into a feed row.
            ForEach(feeds(taggedWith: tag).map { TagFeedRowID(tagId: tag.id, feed: $0) }) { row in
                feedRow(row.feed, item: .feedInTag(feedId: row.feed.id, tagId: tag.id))
            }
        } label: {
            TagRowLabel(
                home: home,
                dialogs: dialogs,
                tag: tag,
                content: sidebar.tagContents[tag.id] ?? SidebarRowStaticContent(title: tag.name, icon: .tagColor(hex: tag.color)),
                selection: selection,
                editor: tagRenameEditor(tag),
                appearedRowKeys: $appearedRowKeys,
                highlight: highlight(for: selection.instance)
            )
        }
    }

    // MARK: - Rows

    /// A feed row is only ever a drag *source*: a feed is dropped between feed rows, never onto one
    /// (`sidebarDropDestination`). A copy nested under an expanded tag is draggable too, but nothing
    /// is inserted among a tag's copies — a feed dropped there goes to the tag itself, by the shared
    /// rules' `FeedListDropTarget.FeedInTag`, as in Compose and on iOS.
    ///
    /// `item` is `.feed` for a feed in its folder group or among the unfoldered feeds, `.feedInTag`
    /// for its copy under an expanded tag.
    private func feedRow(_ feed: Feeds, item: SidebarItemID) -> some View {
        let selection = rowSelection(item, item.rowSelection ?? FeedListRowSelectionFeedInFolderGroup(feedId: feed.id))
        let instance = selection.instance
        return row(
            content: sidebar.feedContents[feed.id] ?? SidebarRowStaticContent(feed: feed),
            unread: .feed(feed.id),
            selection: selection,
            editor: feedRenameEditor(feed, instance: instance)
        )
        .feedListDraggable(
            FeedListDragPayload(kind: .feed, id: feed.id),
            draggingItem: $draggingItem,
            enabled: dialogs.renamingRowKey != selection.key
        )
        .selectsOnContextMenu(id: selection.key) { selectForContextMenu(instance) }
        .contextMenu {
            // Opening the menu selects the row first, matching Compose's own
            // `onOpen = { if (!selected) onClick() }` (`FeedListRows.kt`) — the actual
            // selection runs on a right-click/Control-click via `.selectsOnContextMenu` above, not
            // as a side effect of this builder (see `ContextMenuSelectionTracker`'s own doc for why).
            // Order matches `FeedListRows.kt` and the Feed menu (`HomeCommands.swift`) exactly:
            // Refresh, Assign tags ▸, Move to Folder ▸, a separator, the URL/site actions, a
            // separator, Rename, a separator, Unsubscribe.
            Button(L("home_refresh")) { home.refreshFeed(feed) }
            Menu(L("home_assign_tags")) {
                ForEach(sortedTags, id: \.id) { tag in
                    Toggle(tag.name, isOn: Binding(
                        get: { home.feedTagMap[feed.id]?.contains(tag.id) ?? false },
                        set: { attached in home.viewModel.setFeedTag(feedId: feed.id, tagId: tag.id, attached: attached) }
                    ))
                }
                Button(L("home_new_tag")) { dialogs.creatingTagForFeed = feed }
            }
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
            Divider()
            Button(L("home_copy_feed_url")) { copyToPasteboard(feed.url) }
            Button(L("home_copy_site_url")) { if let site = feed.site_url { copyToPasteboard(site) } }
                .disabled(!ArticleListModelKt.hasUsableUrl(url: feed.site_url))
            Button(L("home_open_site")) { openInBrowserIfAllowed(feed.site_url) }
                .disabled(!ArticleListModelKt.canOpenInBrowser(url: feed.site_url))
            Divider()
            Button(L("home_rename_feed")) { dialogs.startRename(instance) }
            Divider()
            Button(L("home_unsubscribe_menu"), role: .destructive) { dialogs.unsubscribingFeed = feed }
        }
    }

    private func row(
        content: SidebarRowStaticContent,
        unread: SidebarUnreadSource,
        selection: SidebarRowSelection,
        editor: InlineRenameField? = nil
    ) -> some View {
        SidebarSourceListRow(
            home: home,
            content: content,
            unread: unread,
            highlight: highlight(for: selection.instance),
            editor: editor
        )
        .equatable()
        .tag(selection.key)
        .trackAppearance(selection.key, in: $appearedRowKeys)
    }

    /// The selection and key of the row `item`, precomputed by `SidebarModel` — or built from
    /// `instance` for a row it has none for (All / Starred).
    private func rowSelection(_ item: SidebarItemID, _ instance: @autoclosure () -> FeedListRowSelection) -> SidebarRowSelection {
        sidebar.rowSelections[item] ?? SidebarRowSelection(instance())
    }

    /// Selects `instance` if it isn't already the primary selection — called when a row's context
    /// menu opens, matching Compose's own `onOpen = { if (!selected) onClick() }`
    /// (`FeedListRows.kt`).
    private func selectForContextMenu(_ instance: FeedListRowSelection) {
        guard !feedListRowSelectionsEqual(instance, home.selectedRowInstance) else { return }
        home.selectFilter(instance.filter, instance: instance)
    }
}

/// A feed's copy under a tag, identified by both — see `tagGroup`.
private struct TagFeedRowID: Identifiable {
    let tagId: String
    let feed: Feeds
    var id: String { "\(tagId)/\(feed.id)" }
}

private extension View {
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
    let content: SidebarRowStaticContent
    let selection: SidebarRowSelection
    let editor: InlineRenameField?
    @Binding var appearedRowKeys: Set<String>
    /// The row's echo highlight, resolved by `FeedListView`.
    let highlight: SidebarRowHighlight

    @State private var showingColorPicker = false

    var body: some View {
        // A feed hovering the row for attachment is highlighted by the outline itself
        // (`SidebarDropGuard`) — the role of Compose's own `dropTargetBackground` (`FeedListTagRow.kt`).
        SidebarSourceListRow(
            home: home,
            content: content,
            unread: .tag(tag.id),
            highlight: highlight,
            editor: editor,
            onIconTap: { showingColorPicker = true },
            iconPopoverPresented: $showingColorPicker
        ) {
            TagColorPicker(selectedHex: tag.color) { hex in
                home.viewModel.updateTag(id: tag.id, name: tag.name, color: hex)
                showingColorPicker = false
            }
        }
        .equatable()
        .selectsOnContextMenu(id: selection.key) { selectForContextMenu() }
        .contextMenu {
            // Opening the menu selects the row first, matching Compose's own
            // `onOpen = { if (!selected) onClick() }` (`FeedListTagRow.kt`) — the actual selection
            // runs on a right-click/Control-click via `.selectsOnContextMenu` above, not as a side
            // effect of this builder (see `ContextMenuSelectionTracker`'s own doc for why).
            Button(L("home_edit_tag_menu")) { dialogs.startRename(selection.instance) }
            Button(L("home_change_tag_color_menu")) { showingColorPicker = true }
            Button(L("home_delete_tag_menu"), role: .destructive) { dialogs.deletingTag = tag }
        }
        .tag(selection.key)
        .trackAppearance(selection.key, in: $appearedRowKeys)
    }

    private func selectForContextMenu() {
        let instance = selection.instance
        guard !feedListRowSelectionsEqual(instance, home.selectedRowInstance) else { return }
        home.selectFilter(instance.filter, instance: instance)
    }
}
#endif
