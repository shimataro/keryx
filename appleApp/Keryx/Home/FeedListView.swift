import KeryxShared
import SwiftUI

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
    /// Opens the iOS Settings sheet from the toolbar's gear button (macOS reaches Settings from the
    /// app menu instead).
    let settingsNavigation: SettingsNavigation
    var focusedPane: FocusState<HomeFocusedPane?>.Binding
    /// Whether the split view is collapsed with this sidebar as its topmost column — see
    /// `CompactSidebarSelection`. Always false on macOS.
    let sidebarIsTopmost: Bool
    /// Pushes the article list when the split view is collapsed (iPhone); a no-op otherwise.
    let onOpenArticleList: () -> Void

    /// Whether the Folders / Tags section headers are expanded. Native-only view state (Compose has
    /// no equivalent), kept apart from the per-folder / per-tag disclosure state in `home`.
    @AppStorage("sidebar.foldersExpanded") var foldersExpanded = true
    @AppStorage("sidebar.tagsExpanded") var tagsExpanded = true

    #if os(macOS)
    /// Rows currently on screen, keyed by `feedListRowSelectionKey` — read by the scroll-to-
    /// selection effect below so an already-visible row (e.g. one just clicked) never jumps.
    /// Only ever read inside `.onChange` actions, never during `body`, so the insertions and
    /// removals scrolling makes do not re-evaluate this view (checked against an `NSHostingView`:
    /// writes to a `@State` that `body` does not read invalidate nothing).
    @State var appearedRowKeys: Set<String> = []

    // Drag-and-drop state: the item being dragged, and which row a dragged feed is over for the
    // drop-onto highlight and spring-loading — mirrors Compose's own
    // `draggedFeedIdState`/`hoveredAttachTagIdState` (`FeedListDragController.kt`). Insertion
    // between rows is the outline's own `.onInsert`, which draws the insertion line itself. See
    // `FeedListDragAndDrop.swift` for the drop wiring and `FeedListDropPresentation.swift` for the
    // rules behind it. The iOS sidebar keeps its drag state in its collection view instead.
    @State var draggingItem: FeedListDragPayload?
    @State var dropOnKey: FeedListHoverKey?
    @State var hoveredKey: FeedListHoverKey?
    #endif

    var selectedRowKey: String { feedListRowSelectionKey(home.selectedRowInstance) }

    /// The sidebar's structure (sort orders, groupings, drop index, row order), derived once per
    /// change by `HomeObservable` — see `SidebarModel`.
    var sidebar: SidebarModel { home.sidebar }
    var dropIndex: FeedListDropIndex { sidebar.dropIndex }

    var body: some View {
        rows
        .toolbar { toolbarContent }
        // The system search field. Its focus is reported into `focusedPane` (for ⌘F, the
        // `textInputFocused` guard and the ↓/↑ hand-off into the results — see `HomeScreen.kt`'s
        // own `focusSearch`/`moveArticleSelectionFromSearchField`) only from macOS 15 / iOS 18,
        // where `.searchFocused(_:equals:)` exists; earlier systems get the field without them.
        .searchable(text: searchQueryBinding, placement: .sidebar, prompt: L("home_search_placeholder"))
        .modifier(SearchFocusModifier(focusedPane: focusedPane))
        #if os(macOS)
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
        #endif
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
        #if os(iOS)
        .modifier(SidebarRenameSheet(home: home, dialogs: dialogs))
        #endif
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

    /// The rows themselves: the native source list on macOS (`FeedListView+SourceList.swift`), a
    /// UIKit collection view on iOS (`SidebarCollectionView`) — see "Sidebar" in
    /// `docs/app-architecture.md` for why the two differ.
    @ViewBuilder
    private var rows: some View {
        #if os(macOS)
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
        #else
        SidebarCollectionView(home: home, state: collectionState, actions: collectionActions)
            .ignoresSafeArea()
            .focusable()
            .focusEffectDisabled()
            .focused(focusedPane, equals: .feedList)
        #endif
    }

    var orderedRows: [FeedListRowSelection] { sidebar.orderedRows }

    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        #if os(iOS)
        // iOS has no app menu to hold "Settings…", so the sidebar carries it — the same role as
        // Android's own settings row at the bottom of its feed list (`external-spec.md` §9).
        ToolbarItem(placement: .topBarLeading) {
            Button {
                settingsNavigation.isSheetPresented = true
            } label: {
                Label(L("settings_title"), systemImage: "gearshape")
            }
        }
        #endif
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
        #if os(macOS)
        // On iOS, pulling the sidebar down refreshes everything instead (`SidebarCollectionView`), as
        // Mail's list does — and the navigation bar has room for fewer buttons.
        ToolbarItem {
            ToolbarActivityButton(
                titleKey: "home_refresh",
                busyTitleKey: "home_refreshing",
                systemImage: "arrow.clockwise",
                busy: home.activity.refreshIndicatorShown,
                enabled: home.activity.idle,
                action: { home.viewModel.refreshAll() }
            )
        }
        #endif
        if home.cloudConnected {
            ToolbarItem {
                ToolbarActivityButton(
                    titleKey: "home_sync",
                    busyTitleKey: "home_syncing",
                    systemImage: "arrow.triangle.2.circlepath",
                    busy: home.activity.syncing,
                    enabled: home.activity.idle,
                    action: { home.viewModel.sync() }
                )
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

    #if os(macOS)
    /// The name editor for the row `instance`, or `nil` when that row isn't being renamed.
    private func renameEditor(
        for instance: FeedListRowSelection,
        initialName: String,
        placeholder: String = "",
        allowBlank: Bool = false,
        blockingError: @escaping (String) -> String? = { _ in nil },
        commit: @escaping (String) -> Void
    ) -> InlineRenameField? {
        // Checked for every row on every evaluation, so the common case — nothing being renamed —
        // skips deriving the row's key.
        guard let renamingKey = dialogs.renamingRowKey, renamingKey == feedListRowSelectionKey(instance) else { return nil }
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

    /// The editor for renaming `folder` in place, while it is being renamed.
    func folderRenameEditor(_ folder: Folders) -> InlineRenameField? {
        renameEditor(
            for: FeedListRowSelectionFolder(folderId: folder.id),
            initialName: folder.name,
            blockingError: { NameValidationKt.isDuplicateFolderName(name: $0, folders: home.folders, excludeId: folder.id) ? L("home_folder_name_duplicate") : nil },
            commit: { home.viewModel.updateFolder(id: folder.id, name: $0) }
        )
    }

    /// The editor for renaming `tag` in place, while it is being renamed.
    func tagRenameEditor(_ tag: Tags) -> InlineRenameField? {
        renameEditor(
            for: FeedListRowSelectionTag(tagId: tag.id),
            initialName: tag.name,
            blockingError: { NameValidationKt.isDuplicateTagName(name: $0, tags: home.tags, excludeId: tag.id) ? L("home_tag_name_duplicate") : nil },
            // The tag's color is kept as it is — it has its own menu item and dot popover.
            commit: { home.viewModel.updateTag(id: tag.id, name: $0, color: tag.color) }
        )
    }

    /// The editor for renaming the feed row `instance` in place, while it is being renamed.
    func feedRenameEditor(_ feed: Feeds, instance: FeedListRowSelection) -> InlineRenameField? {
        renameEditor(
            for: instance,
            initialName: feed.displayTitle(),
            // What clearing the name falls back to: the feed's own parsed title.
            placeholder: feed.title,
            // Blank clears `custom_title` (`FeedRepository.renameFeed`); there is no duplicate check.
            allowBlank: true,
            commit: { home.viewModel.renameFeed(id: feed.id, title: $0) }
        )
    }
    #endif

    // MARK: - Sidebar structure

    var sortedFolders: [Folders] { sidebar.sortedFolders }

    var unassignedFeeds: [Feeds] { sidebar.unassignedFeeds }

    func feedsIn(folder: Folders) -> [Feeds] { sidebar.feeds(inFolder: folder.id) }

    var sortedTags: [Tags] { sidebar.sortedTags }

    func feeds(taggedWith tag: Tags) -> [Feeds] { sidebar.feeds(taggedWith: tag.id) }

    /// `.echo` — the role of the SECONDARY tone of the Compose app's `RowSelectionTone`
    /// (`FeedListPane.kt`'s `toneFor`) — for every *other* rendered copy of the selected filter: a feed shown under both
    /// its folder group and an expanded tag. The selected row itself is the native list selection,
    /// which already dims while the sidebar lacks focus, as Compose's PRIMARY tone does.
    func highlight(for instance: FeedListRowSelection) -> SidebarRowHighlight {
        SidebarRowContent.highlight(for: instance, selectedRow: home.selectedRowInstance, filter: home.filter)
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

#if os(iOS)
/// iOS renames a folder, tag or feed in the form sheet that creates one (`NamePromptSheet`), not in
/// the row itself — a row-sized keyboard editor with an Escape key does not suit touch. It is shown
/// for as long as `dialogs.renamingRowKey` names a row that still exists; a row removed underneath it
/// (see the auto-cancel in `FeedListView.body`) closes the sheet.
private struct SidebarRenameSheet: ViewModifier {
    let home: HomeObservable
    @Bindable var dialogs: SidebarDialogState

    private var target: SidebarRenameTarget? {
        guard let key = dialogs.renamingRowKey, let instance = home.sidebar.orderedRowsByKey[key] else { return nil }
        return SidebarRenameTarget.resolve(
            SidebarItemID(instance), folders: home.folders, tags: home.tags, feedsById: home.feedsById
        )
    }

    func body(content: Content) -> some View {
        content.sheet(isPresented: Binding(
            get: { target != nil },
            set: { if !$0 { dialogs.renamingRowKey = nil } }
        )) {
            if let target {
                NamePromptSheet(
                    titleKey: "home_rename_feed",
                    placeholderKey: placeholderKey(for: target),
                    placeholderText: target.placeholder,
                    duplicateMessageKey: target.duplicateMessageKey ?? "",
                    confirmTitleKey: "common_save",
                    allowBlank: target.allowBlank,
                    initialName: target.initialName,
                    isDuplicate: { isDuplicate($0, for: target) },
                    onConfirm: { name, _ in commit(name, for: target) },
                    isPresented: Binding(get: { dialogs.renamingRowKey != nil }, set: { if !$0 { dialogs.renamingRowKey = nil } })
                )
            }
        }
    }

    /// The hint shown in an empty folder or tag field; a feed shows its own title instead.
    private func placeholderKey(for target: SidebarRenameTarget) -> String {
        switch target.kind {
        case .folder: return "home_new_folder_hint"
        case .tag: return "home_new_tag_hint"
        case .feed: return "home_rename_feed"
        }
    }

    private func isDuplicate(_ name: String, for target: SidebarRenameTarget) -> Bool {
        switch target.kind {
        case .folder(let id): return NameValidationKt.isDuplicateFolderName(name: name, folders: home.folders, excludeId: id)
        case .tag(let id): return NameValidationKt.isDuplicateTagName(name: name, tags: home.tags, excludeId: id)
        case .feed: return false
        }
    }

    private func commit(_ name: String, for target: SidebarRenameTarget) {
        switch target.kind {
        case .folder(let id):
            home.viewModel.updateFolder(id: id, name: name)
        case .tag(let id):
            // The tag's color is kept as it is — it has its own menu palette.
            home.viewModel.updateTag(id: id, name: name, color: home.tags.first { $0.id == id }?.color)
        case .feed(let id):
            home.viewModel.renameFeed(id: id, title: name)
        }
    }
}
#endif

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

/// A sidebar toolbar action (Refresh All / Sync) that shows a spinner while its operation runs.
///
/// On macOS the spinner replaces the icon inside the button's `Label`: `NSToolbar` hosts the view
/// as-is, and the title stays fixed so VoiceOver and the collapsed-sidebar overflow menu still name
/// the action. iOS's navigation bar cannot render a `ProgressView` as a button's icon — it falls
/// back to the label's title text — so there the spinner takes the button's place instead, carrying
/// the in-progress title for VoiceOver. The button is disabled while busy either way, so nothing
/// tappable is lost.
private struct ToolbarActivityButton: View {
    let titleKey: String
    let busyTitleKey: String
    let systemImage: String
    let busy: Bool
    let enabled: Bool
    let action: () -> Void

    var body: some View {
        #if os(iOS)
        if busy {
            ProgressView()
                .accessibilityLabel(L(busyTitleKey))
        } else {
            Button(action: action) {
                Label(L(titleKey), systemImage: systemImage)
            }
            .disabled(!enabled)
        }
        #else
        Button(action: action) {
            // A `Label` rather than a bare icon so the toolbar's overflow menu (shown when the
            // sidebar is collapsed) gets a title; the toolbar itself still renders icon-only.
            Label {
                Text(L(titleKey))
            } icon: {
                if busy {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: systemImage)
                }
            }
        }
        .disabled(!enabled)
        .help(L(busy ? busyTitleKey : titleKey))
        #endif
    }
}
