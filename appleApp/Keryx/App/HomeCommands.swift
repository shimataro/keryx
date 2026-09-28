import KeryxShared
import SwiftUI
#if os(macOS)
import AppKit
import UniformTypeIdentifiers
#endif

/// The application menu bar's dynamic items (File/View/Article/Feed/Help) — see
/// `presentation/menu/MenuState.kt`'s `computeMenuUiState` for the enabled/checked rules this
/// mirrors. This app has exactly one window showing Home (Setup takes over before Home ever
/// appears), so this reads `AppModel`'s state directly rather than through `FocusedValue` — except
/// for `HomeView`'s own pane focus (`homeFocusedPane`, published via `.focusedSceneValue`), which
/// this struct has no other way to observe, since it declares no `@FocusState` of its own.
struct HomeCommands: Commands {
    let model: AppModel

    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif
    @FocusedValue(\.homeFocusedPane) private var focusedPane: HomeFocusedPane??

    /// Return/Delete (`renameLabel`/`deleteLabel` below) keep their bare, unmodified accelerator —
    /// matching Compose's own `AppMenuTree.kt` `FeedRename`/`FeedUnsubscribe` (`ctrl = false`), an
    /// established file-manager rename/delete convention — but only while the sidebar itself holds
    /// keyboard focus and no sheet/alert is covering it. Without this guard, AppKit resolves a bare
    /// Return/Delete against the menu before any view's own `.onKeyPress`/text field ever sees it —
    /// so Backspace inside `NamePromptSheet`'s text field, or Return/Delete while the article list or
    /// reader holds focus, would trigger the sidebar's rename/delete instead of editing text or doing
    /// nothing, acting on whatever the sidebar happens to have selected underneath.
    private var bareKeysActive: Bool {
        focusedPane.flatMap { $0 } == .feedList && !model.sidebarDialogs.isPresenting
    }

    var body: some Commands {
        #if os(macOS)
        CommandGroup(replacing: .appInfo) {
            Button(L("menu_help_about")) { openWindow(id: "about") }
        }
        #endif

        CommandGroup(replacing: .newItem) {
            if let home = model.home {
                Button(L("menu_file_add_feed")) { model.sidebarDialogs.isAddingFeed = true }
                    .keyboardShortcut("n", modifiers: .command)
                    .disabled(!menuState(home).addItemsEnabled)
                Button(L("menu_file_add_folder")) { model.sidebarDialogs.isAddingFolder = true }
                    .disabled(!menuState(home).addItemsEnabled)
                Button(L("menu_file_add_tag")) { model.sidebarDialogs.isAddingTag = true }
                    .disabled(!menuState(home).addItemsEnabled)
                #if os(macOS)
                Divider()
                Button(L("menu_file_import_opml")) { importOpml() }
                    .keyboardShortcut("i", modifiers: .command)
                    .disabled(!menuState(home).opmlEnabled)
                Button(L("menu_file_export_opml")) { exportOpml() }
                    .keyboardShortcut("e", modifiers: .command)
                    .disabled(!menuState(home).opmlEnabled)
                #endif
            }
        }

        CommandGroup(after: .toolbar) {
            if let home = model.home {
                let state = menuState(home)
                // Focusing the system search field needs `.searchFocused(_:equals:)` (macOS 15+),
                // so before that the item would do nothing and is left out entirely.
                if #available(macOS 15, *) {
                    Button(L("menu_view_search")) {
                        home.viewModel.setSearchBarVisible(visible: true)
                        home.viewModel.requestSearchFocus()
                    }
                    .keyboardShortcut("f", modifiers: .command)
                    .disabled(!state.searchEnabled)
                }

                Toggle(L("menu_view_unread_only"), isOn: Binding(
                    get: { home.unreadOnly },
                    set: { home.viewModel.setUnreadOnly(value: $0) }
                ))
                .keyboardShortcut("u", modifiers: .command)
                .disabled(!state.unreadOnlyEnabled)

                Button(L("menu_view_toggle_sort")) { home.viewModel.toggleSort() }
                    .disabled(!state.toggleSortEnabled)

                Button(L("menu_view_mark_all_read")) { home.viewModel.markAllRead() }
                    .disabled(!state.markAllReadEnabled)
            }
        }

        CommandMenu(L("menu_article")) {
            if let home = model.home {
                let state = menuState(home)
                Button(L("menu_article_toggle_read")) { home.viewModel.toggleReadSelected() }
                    .keyboardShortcut("u", modifiers: [.command, .shift])
                    .disabled(!state.articleActionsEnabled)
                Button(L("menu_article_toggle_star")) { home.viewModel.toggleStarSelected() }
                    .keyboardShortcut("s", modifiers: [.command, .shift])
                    .disabled(!state.articleActionsEnabled)
                Button(L("menu_article_open_in_browser")) {
                    if let url = home.selectedArticle?.url { openInBrowser(url) }
                }
                .keyboardShortcut("o", modifiers: [.command, .shift])
                .disabled(!state.urlActionsEnabled)
                Button(L("menu_article_copy_url")) {
                    if let url = home.selectedArticle?.url {
                        copyToPasteboard(url)
                        home.pulseCopy()
                    }
                }
                .keyboardShortcut("c", modifiers: [.command, .shift])
                .disabled(!state.urlActionsEnabled)
            }
        }

        CommandMenu(L("menu_feed")) {
            if let home = model.home {
                let state = menuState(home)
                Button(L("menu_feed_refresh_all")) { home.viewModel.refreshAll() }
                    .keyboardShortcut("r", modifiers: .command)
                    .disabled(!state.refreshAllEnabled)
                Button(L("menu_feed_sync_now")) { home.viewModel.sync() }
                    .disabled(!state.syncEnabled)
                Divider()
                // The rest all act on the currently selected feed-list item, matching Compose's own
                // Feed menu (`AppMenuTree.kt:275-324`) exactly: Refresh, Tags ▸, Move to folder ▸, a
                // separator, the URL/site actions, a separator, Rename, a separator, Unsubscribe —
                // Rename/Unsubscribe alone use `renameOrDeleteEnabled` (they act on whatever's
                // selected — feed, folder or tag — not only a feed).
                Button(L("home_refresh")) {
                    if let feed = selectedFeed(home) { home.viewModel.refreshFeed(feed: feed) }
                }
                .keyboardShortcut("r", modifiers: [.command, .shift])
                .disabled(!state.feedActionsEnabled)

                Menu(L("home_assign_tags")) {
                    ForEach(sortedTags(home), id: \.id) { tag in
                        if let feed = selectedFeed(home) {
                            Toggle(tag.name, isOn: Binding(
                                get: { home.feedTagMap[feed.id]?.contains(tag.id) ?? false },
                                set: { attached in home.viewModel.setFeedTag(feedId: feed.id, tagId: tag.id, attached: attached) }
                            ))
                        }
                    }
                }
                .disabled(!state.feedActionsEnabled)

                Menu(L("home_move_to_folder")) {
                    if let feed = selectedFeed(home) {
                        Toggle(L("home_no_folder"), isOn: Binding(
                            get: { feed.folder_id == nil },
                            set: { _ in home.viewModel.moveFeed(feedId: feed.id, folderId: nil, targetFeedId: nil) }
                        ))
                        ForEach(sortedFolders(home), id: \.id) { folder in
                            Toggle(folder.name, isOn: Binding(
                                get: { feed.folder_id == folder.id },
                                set: { _ in home.viewModel.moveFeed(feedId: feed.id, folderId: folder.id, targetFeedId: nil) }
                            ))
                        }
                    }
                }
                .disabled(!state.feedActionsEnabled)

                Divider()
                Button(L("home_copy_feed_url")) {
                    if let feed = selectedFeed(home) {
                        copyToPasteboard(feed.url)
                        home.pulseCopy()
                    }
                }
                .disabled(!state.feedActionsEnabled)
                Button(L("home_copy_site_url")) {
                    if let site = selectedFeed(home)?.site_url {
                        copyToPasteboard(site)
                        home.pulseCopy()
                    }
                }
                .disabled(!state.feedSiteUrlActionsEnabled)
                Button(L("home_open_site")) {
                    if let site = selectedFeed(home)?.site_url { openInBrowser(site) }
                }
                .disabled(!state.feedSiteUrlActionsEnabled)

                Divider()
                Button(renameLabel(home)) { performRename(home) }
                    .keyboardShortcut(bareKeysActive ? KeyboardShortcut(.return, modifiers: []) : nil)
                    .disabled(!state.renameOrDeleteEnabled)
                Divider()
                Button(deleteLabel(home), role: .destructive) { performDelete(home) }
                    .keyboardShortcut(bareKeysActive ? KeyboardShortcut(.delete, modifiers: []) : nil)
                    .disabled(!state.renameOrDeleteEnabled)
            }
        }

        CommandGroup(replacing: .help) {
            Button(L("menu_help_website")) { openInBrowser(L("website_url")) }
            Button(L("menu_help_project_page")) { openInBrowser(projectUrl) }
        }
    }

    /// `sdk.menuState(...)` needs several booleans this app doesn't track anywhere else yet
    /// (`hasSelectedFeed`/`selectedFeedHasSiteUrl`/`hasRenamableSelection`); resolved the same way
    /// `HomeView`'s own rename/delete keyboard handling does, via `resolveFeedListSelectionTarget`.
    /// `textInputFocused` reads `HomeObservable`'s own mirror of `HomeView`'s `focusedPane`, so
    /// this reacts to the search field the same way `HomeShortcutsKt.homeShortcutFor` does.
    private func menuState(_ home: HomeObservable) -> MenuUiState {
        let target = selectionTarget(home)
        var hasSelectedFeed = false
        var selectedFeedHasSiteUrl = false
        if let target, case .feed(let f) = onEnum(of: target) {
            hasSelectedFeed = true
            selectedFeedHasSiteUrl = ArticleListModelKt.hasUsableUrl(url: f.feed.site_url)
        }
        guard let sdk = model.sdk else {
            return MenuUiState(
                addItemsEnabled: false, opmlEnabled: false, searchEnabled: false, unreadOnlyEnabled: false,
                unreadOnlyChecked: false, toggleSortEnabled: false, markAllReadEnabled: false,
                articleActionsEnabled: false, urlActionsEnabled: false, refreshAllEnabled: false,
                syncEnabled: false, openSettingsEnabled: false, feedActionsEnabled: false,
                feedSiteUrlActionsEnabled: false, renameOrDeleteEnabled: false
            )
        }
        return sdk.menuState(
            onHome: !model.needsSetup,
            hasSelectedArticle: home.selectedArticle != nil,
            selectedArticleHasUrl: ArticleListModelKt.hasUsableUrl(url: home.selectedArticle?.url),
            cloudConnected: home.cloudConnected,
            searchActive: home.searchActive,
            unreadOnly: home.unreadOnly,
            hasSelectedFeed: hasSelectedFeed,
            textInputFocused: home.textInputFocused,
            hasRenamableSelection: target != nil,
            selectedFeedHasSiteUrl: selectedFeedHasSiteUrl
        )
    }

    private func selectionTarget(_ home: HomeObservable) -> FeedListSelectionTarget? {
        FeedListModelKt.resolveFeedListSelectionTarget(
            filter: home.filter, feeds: home.feeds, folders: home.folders, tags: home.tags
        )
    }

    /// The feed the Feed menu's selected-feed items (Refresh/Tags/Move to folder/Copy URL/…) act
    /// on — only when the sidebar's own selection is a feed itself, matching Compose's own
    /// `selectedFeedForMenu()` (`HomeScreen.kt`).
    private func selectedFeed(_ home: HomeObservable) -> Feeds? {
        guard case .feed(let filter) = onEnum(of: home.filter) else { return nil }
        return home.feeds.first { $0.id == filter.feedId }
    }

    private func sortedFolders(_ home: HomeObservable) -> [Folders] {
        home.folders.sorted { $0.sort_order < $1.sort_order }
    }

    private func sortedTags(_ home: HomeObservable) -> [Tags] {
        home.tags.sorted { $0.sort_order < $1.sort_order }
    }

    /// Rename/delete wording follows the selected item's type — a `nil` target falls back to the
    /// feed wording, matching Compose's own `renameLabel`/`deleteLabel` (`AppMenuBar.kt:161-169`);
    /// the items are disabled in that case, so the text is never acted on.
    private func renameLabel(_ home: HomeObservable) -> String {
        guard let target = selectionTarget(home) else { return L("home_rename_feed") }
        switch onEnum(of: target) {
        case .folder: return L("home_menu_rename_folder")
        case .tag: return L("home_menu_rename_tag")
        case .feed: return L("home_rename_feed")
        }
    }

    private func deleteLabel(_ home: HomeObservable) -> String {
        guard let target = selectionTarget(home) else { return L("home_unsubscribe_menu") }
        switch onEnum(of: target) {
        case .folder: return L("home_menu_delete_folder")
        case .tag: return L("home_menu_delete_tag")
        case .feed: return L("home_unsubscribe_menu")
        }
    }

    private func performRename(_ home: HomeObservable) {
        guard let target = selectionTarget(home) else { return }
        switch onEnum(of: target) {
        case .feed(let f): model.sidebarDialogs.renamingFeed = f.feed
        case .folder(let f): model.sidebarDialogs.renamingFolder = f.folder
        case .tag(let t): model.sidebarDialogs.renamingTag = t.tag
        }
    }

    private func performDelete(_ home: HomeObservable) {
        guard let target = selectionTarget(home) else { return }
        switch onEnum(of: target) {
        case .feed(let f): model.sidebarDialogs.unsubscribingFeed = f.feed
        case .folder(let f): model.sidebarDialogs.deletingFolder = f.folder
        case .tag(let t): model.sidebarDialogs.deletingTag = t.tag
        }
    }

    #if os(macOS)
    /// Shares `AppModel.opmlTransfer`'s busy-guard and result state with the Data settings tab
    /// (`DataSettingsTab.swift`), so triggering this from the menu can't race a run already started
    /// from there, matching Compose's own `SettingsViewModel` routing both entry points through one
    /// state (`AppMenuBar.kt`).
    private func importOpml() {
        guard let opmlTransfer = model.opmlTransfer, !opmlTransfer.isBusy else { return }
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "opml") ?? .xml, .xml]
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        opmlTransfer.importOpml(from: url)
    }

    private func exportOpml() {
        guard let opmlTransfer = model.opmlTransfer, let document = opmlTransfer.exportDocument() else { return }
        let panel = NSSavePanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "opml") ?? .xml]
        panel.nameFieldStringValue = "keryx.opml"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        do {
            try document.text.write(to: url, atomically: true, encoding: .utf8)
            opmlTransfer.reportExportResult(.success(url))
        } catch {
            opmlTransfer.reportExportResult(.failure(error))
        }
    }
    #endif
}
