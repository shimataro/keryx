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

    var body: some View {
        listContent
            .listStyle(.sidebar)
            .searchable(text: searchQueryBinding, placement: .sidebar, prompt: Text(L("home_search_placeholder")))
            .focused(focusedPane, equals: .feedList)
            .navigationTitle(L("app_name"))
            .toolbar { toolbarContent }
            .modifier(SidebarCreateSheets(home: home, dialogs: dialogs))
            .modifier(SidebarRenameSheets(home: home, dialogs: dialogs))
            .modifier(SidebarDeleteAlerts(home: home, dialogs: dialogs))
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
            if !unassignedFeeds.isEmpty {
                Section {
                    ForEach(unassignedFeeds, id: \.id) { feed in
                        feedRow(feed, instance: FeedListRowSelectionFeedInFolderGroup(feedId: feed.id))
                    }
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
    }

    private var searchQueryBinding: Binding<String> {
        Binding(
            get: { home.searchQuery },
            set: { newValue in
                home.viewModel.setSearchQuery(query: newValue)
                home.viewModel.setSearchBarVisible(visible: true)
            }
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

    private var unassignedFeeds: [Feeds] {
        home.feeds.filter { $0.folder_id == nil }.sorted { $0.sort_order < $1.sort_order }
    }

    private func feedsIn(folder: Folders) -> [Feeds] {
        home.feeds.filter { $0.folder_id == folder.id }.sorted { $0.sort_order < $1.sort_order }
    }

    @ViewBuilder
    private func folderSection(_ folder: Folders) -> some View {
        let isCollapsed = home.collapsedFolderIds.contains(folder.id)
        let instance = FeedListRowSelectionFolder(folderId: folder.id)
        Section {
            Button {
                focusedPane.wrappedValue = .feedList
                home.viewModel.selectFilter(filter: instance.filter, instance: instance)
            } label: {
                HStack {
                    expandChevron(expanded: !isCollapsed) {
                        home.viewModel.toggleFolderCollapsed(folderId: folder.id)
                    }
                    Text(folder.name)
                    Spacer()
                    unreadBadge(home.unreadByFolder[folder.id] ?? 0)
                }
                .selectableRowLabel(selectionBackground(for: instance))
            }
            .buttonStyle(.plain)
            .contextMenu {
                Button(L("home_menu_rename_folder")) { dialogs.renamingFolder = folder }
                Button(L("home_menu_delete_folder"), role: .destructive) { dialogs.deletingFolder = folder }
            }
            .draggable(folder.id)
            .dropDestination(for: String.self) { items, _ in
                guard let draggedId = items.first else { return false }
                if home.folders.contains(where: { $0.id == draggedId }) {
                    home.viewModel.reorderFolders(draggedFolderId: draggedId, targetFolderId: folder.id)
                } else {
                    home.viewModel.moveFeed(feedId: draggedId, folderId: folder.id, targetFeedId: nil)
                }
                return true
            }

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
        home.feeds
            .filter { home.feedTagMap[$0.id]?.contains(tag.id) ?? false }
            .sorted { $0.sort_order < $1.sort_order }
    }

    @ViewBuilder
    private func tagSection(_ tag: Tags) -> some View {
        let isExpanded = home.expandedTagIds.contains(tag.id)
        let instance = FeedListRowSelectionTag(tagId: tag.id)
        Section {
            Button {
                focusedPane.wrappedValue = .feedList
                home.viewModel.selectFilter(filter: instance.filter, instance: instance)
            } label: {
                HStack {
                    Circle()
                        .fill(colorFromHex(tag.color ?? "#808080"))
                        .frame(width: 10, height: 10)
                    Text(tag.name)
                    Spacer()
                    unreadBadge(home.unreadByTag[tag.id] ?? 0)
                    expandChevron(expanded: isExpanded) {
                        home.viewModel.toggleTagExpanded(tagId: tag.id)
                    }
                }
                .selectableRowLabel(selectionBackground(for: instance))
            }
            .buttonStyle(.plain)
            .contextMenu {
                Button(L("home_menu_rename_tag")) { dialogs.renamingTag = tag }
                Button(L("home_menu_delete_tag"), role: .destructive) { dialogs.deletingTag = tag }
            }
            .dropDestination(for: String.self) { items, _ in
                guard let feedId = items.first, home.feeds.contains(where: { $0.id == feedId }) else { return false }
                home.viewModel.setFeedTag(feedId: feedId, tagId: tag.id, attached: true)
                return true
            }

            if isExpanded {
                ForEach(feeds(taggedWith: tag), id: \.id) { feed in
                    feedRow(feed, instance: FeedListRowSelectionFeedInTag(feedId: feed.id, tagId: tag.id))
                }
            }
        }
    }

    // MARK: - Rows

    @ViewBuilder
    private func feedRow(_ feed: Feeds, instance: FeedListRowSelection) -> some View {
        row(
            title: feed.custom_title ?? feed.title,
            faviconUrl: feed.favicon_url,
            unreadCount: home.unreadByFeed[feed.id] ?? 0,
            isErroring: feed.error_count > 0 || feed.last_error == ConstantsKt.FEED_ERROR_REASON_GONE,
            isGone: feed.last_error == ConstantsKt.FEED_ERROR_REASON_GONE,
            instance: instance,
        )
        .draggable(feed.id)
        .dropDestination(for: String.self) { items, _ in
            guard let draggedId = items.first, draggedId != feed.id,
                  home.feeds.contains(where: { $0.id == draggedId }) else { return false }
            home.viewModel.moveFeed(feedId: draggedId, folderId: feed.folder_id, targetFeedId: feed.id)
            return true
        }
        .contextMenu {
            Button(L("home_rename_feed")) { dialogs.renamingFeed = feed }
            Button(L("home_refresh")) { home.viewModel.refreshFeed(feed: feed) }
            Menu(L("home_assign_tags")) {
                ForEach(sortedTags, id: \.id) { tag in
                    let attached = home.feedTagMap[feed.id]?.contains(tag.id) ?? false
                    Button {
                        home.viewModel.setFeedTag(feedId: feed.id, tagId: tag.id, attached: !attached)
                    } label: {
                        Label(tag.name, systemImage: attached ? "checkmark" : "")
                    }
                }
            }
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
        Button {
            focusedPane.wrappedValue = .feedList
            home.viewModel.selectFilter(filter: instance.filter, instance: instance)
        } label: {
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
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                        .help(L(isGone ? "home_feed_gone" : "home_feed_error"))
                }
                unreadBadge(unreadCount)
            }
            .padding(.vertical, 2)
            .selectableRowLabel(selectionBackground(for: instance))
        }
        .buttonStyle(.plain)
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

    /// A folder/tag header's expand/collapse control — a button of its own so that clicking the
    /// rest of the header selects it instead, as in the Compose app.
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
}

private extension View {
    /// Paints a row label's selection tint as a capsule background (macOS source-list style) and
    /// widens the tap/click target to the row's full width — `.listRowBackground` does not reliably
    /// paint through `.sidebar`-style `List` rows, so the tint has to live on the label itself.
    func selectableRowLabel(_ background: Color) -> some View {
        frame(maxWidth: .infinity, alignment: .leading)
            .contentShape(Rectangle())
            .padding(.horizontal, 4)
            .background(RoundedRectangle(cornerRadius: 5).fill(background))
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
                    initialColor: tagColorPalette[0],
                    showColorPicker: true,
                    isDuplicate: { NameValidationKt.isDuplicateTagName(name: $0, tags: home.tags, excludeId: nil) },
                    onConfirm: { name, color in _ = home.viewModel.createTag(name: name, color: color) },
                    isPresented: $dialogs.isAddingTag
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
                    titleKey: "home_menu_rename_folder",
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
                    titleKey: "home_menu_rename_tag",
                    placeholderKey: "home_new_tag_hint",
                    duplicateMessageKey: "home_tag_name_duplicate",
                    initialName: tag.name,
                    initialColor: tag.color ?? tagColorPalette[0],
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
                    initialName: feed.custom_title ?? feed.title,
                    isDuplicate: { _ in false },
                    onConfirm: { name, _ in home.viewModel.renameFeed(id: feed.id, title: name) },
                    isPresented: Binding(get: { dialogs.renamingFeed != nil }, set: { if !$0 { dialogs.renamingFeed = nil } })
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
