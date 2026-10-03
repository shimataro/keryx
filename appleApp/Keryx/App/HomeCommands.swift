import KeryxShared
import SwiftUI

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
    /// nothing, acting on whatever the sidebar happens to have selected underneath. Fed to
    /// `sdk.menuState` as `feedListKeysActive`; the items read the resulting
    /// `renameOrDeleteShortcutActive` and are otherwise left clickable (detach, don't disable).
    private var bareKeysActive: Bool {
        focusedPane.flatMap { $0 } == .feedList
            && !model.sidebarDialogs.isPresenting && !model.sidebarDialogs.isEditingInline
    }

    var body: some Commands {
        // Computed once per evaluation and shared by every menu below.
        let state = model.home.map(menuState)
        #if os(macOS)
        CommandGroup(replacing: .appInfo) {
            Button(L("menu_help_about")) { openWindow(id: "about") }
        }
        #endif

        CommandGroup(replacing: .newItem) {
            if let home = model.home, let state {
                Button(L("menu_file_add_feed")) { model.sidebarDialogs.isAddingFeed = true }
                    .keyboardShortcut("n", modifiers: .command)
                    .disabled(!state.addItemsEnabled)
                Button(L("menu_file_add_folder")) { model.sidebarDialogs.isAddingFolder = true }
                    .disabled(!state.addItemsEnabled)
                Button(L("menu_file_add_tag")) { model.sidebarDialogs.isAddingTag = true }
                    .disabled(!state.addItemsEnabled)
                #if os(macOS)
                Divider()
                // Both only ask; `OpmlRequestPresenter` (Home) then shows Settings ▸ Data, which
                // carries the request out with its own panel, spinner and result.
                Button(L("menu_file_import_opml")) { model.opmlTransfer?.request(OpmlRequestImportFile.shared) }
                    .keyboardShortcut("i", modifiers: .command)
                    .disabled(!state.opmlEnabled)
                Button(L("menu_file_export_opml")) { model.opmlTransfer?.request(OpmlRequestExportFile.shared) }
                    .keyboardShortcut("e", modifiers: .command)
                    .disabled(!state.opmlEnabled)
                #endif
            }
        }

        CommandGroup(after: .toolbar) {
            if let home = model.home, let state {
                // Focusing the system search field needs `.searchFocused(_:equals:)` (macOS 15+),
                // so before that the item would do nothing and is left out entirely.
                if #available(macOS 15, *) {
                    Button(L("menu_view_search")) {
                        home.setSearchBarVisible(true)
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
            if let home = model.home, let state {
                Button(L("menu_article_toggle_read")) { home.viewModel.toggleReadSelected() }
                    .keyboardShortcut("u", modifiers: [.command, .shift])
                    .disabled(!state.articleActionsEnabled)
                Button(L("menu_article_toggle_star")) { home.viewModel.toggleStarSelected() }
                    .keyboardShortcut("s", modifiers: [.command, .shift])
                    .disabled(!state.articleActionsEnabled)
                // Same grouping as the Compose menu bar's Article menu (`AppMenuTree.kt`) and the
                // article row's context menu (`ArticleRowView`).
                Divider()
                Button(L("menu_article_open_in_browser")) {
                    openInBrowserIfAllowed(home.selectedArticle?.url)
                }
                .keyboardShortcut("o", modifiers: [.command, .shift])
                .disabled(!state.openInBrowserEnabled)
                Button(L("menu_article_copy_url")) {
                    if let article = home.selectedArticle {
                        home.copyArticleUrl(url: article.url, articleId: article.id)
                    }
                }
                .keyboardShortcut("c", modifiers: [.command, .shift])
                .disabled(!state.copyUrlEnabled)
            }
        }

        CommandMenu(L("menu_feed")) {
            if let home = model.home, let state {
                Button(L("menu_feed_refresh_all")) { home.viewModel.refreshAll() }
                    .keyboardShortcut("r", modifiers: .command)
                    .disabled(!state.refreshAllEnabled)
                Button(L("menu_feed_sync_now")) { home.viewModel.sync() }
                    .disabled(!state.syncEnabled)
                Divider()
                // The rest all act on the currently selected feed-list item, matching Compose's own
                // Feed menu (`AppMenuTree.kt`) exactly: Refresh, Tags ▸, Move to folder ▸ (each closing
                // with its "New …" item), a separator, the URL/site actions, a separator, Rename, a separator, Unsubscribe —
                // Rename/Unsubscribe alone use `renameOrDeleteEnabled` (they act on whatever's
                // selected — feed, folder or tag — not only a feed).
                Button(L("home_refresh")) {
                    if let feed = selectedFeed(home) { home.refreshFeed(feed) }
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
                    // Closes the submenu like the sidebar row's own menu (`FeedListView+SourceList`)
                    // and Compose's `AppMenuTree.kt`, so Tags is never empty.
                    Button(L("home_new_tag")) {
                        if let feed = selectedFeed(home) { model.sidebarDialogs.creatingTagForFeed = feed }
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
                        Button(L("home_new_folder")) { model.sidebarDialogs.creatingFolderForFeed = feed }
                    }
                }
                .disabled(!state.feedActionsEnabled)

                Divider()
                Button(L("home_copy_feed_url")) {
                    if let feed = selectedFeed(home) {
                        copyToPasteboard(feed.url)
                    }
                }
                .disabled(!state.feedActionsEnabled)
                Button(L("home_copy_site_url")) {
                    if let site = selectedFeed(home)?.site_url {
                        copyToPasteboard(site)
                    }
                }
                .disabled(!state.feedSiteCopyEnabled)
                Button(L("home_open_site")) {
                    openInBrowserIfAllowed(selectedFeed(home)?.site_url)
                }
                .disabled(!state.feedSiteOpenEnabled)

                Divider()
                Button(renameLabel(home)) { performRename(home) }
                    .keyboardShortcut(state.renameOrDeleteShortcutActive ? KeyboardShortcut(.return, modifiers: []) : nil)
                    .disabled(!state.renameOrDeleteEnabled)
                Divider()
                Button(deleteLabel(home), role: .destructive) { performDelete(home) }
                    .keyboardShortcut(state.renameOrDeleteShortcutActive ? KeyboardShortcut(.delete, modifiers: []) : nil)
                    .disabled(!state.renameOrDeleteEnabled)
            }
        }

        CommandGroup(replacing: .help) {
            Button(L("menu_help_website")) { openInBrowser(L("website_url")) }
            Button(L("menu_help_project_page")) { openInBrowser(projectUrl) }
        }
    }

    /// `sdk.menuState(...)` needs several booleans this app doesn't track anywhere else yet
    /// (`hasSelectedFeed`/`selectedFeedHasSiteUrl`/`selectedFeedSiteCanOpenInBrowser`/
    /// `hasRenamableSelection`); resolved the same way
    /// `HomeView`'s own rename/delete keyboard handling does, via `resolveFeedListSelectionTarget`
    /// (kept by `HomeObservable.feedListSelectionTarget`). Everything read here is a narrow value
    /// `HomeObservable` only reassigns when it changes, so an article selection alone does not
    /// rebuild the menu bar.
    /// `feedListKeysActive` is `bareKeysActive` (the sidebar holds keyboard focus, no sheet or inline
    /// editor is up), so `state.renameOrDeleteShortcutActive` — not a second copy of the rule here —
    /// decides whether Rename/Delete carry their bare Return/Delete accelerator; the items
    /// themselves stay enabled with any selection, matching Compose's `AppMenuTree.kt`.
    private func menuState(_ home: HomeObservable) -> MenuUiState {
        let target = selectionTarget(home)
        var hasSelectedFeed = false
        var selectedFeedHasSiteUrl = false
        var selectedFeedSiteCanOpenInBrowser = false
        if let target, case .feed(let f) = onEnum(of: target) {
            hasSelectedFeed = true
            selectedFeedHasSiteUrl = ArticleListModelKt.hasUsableUrl(url: f.feed.site_url)
            selectedFeedSiteCanOpenInBrowser = ArticleListModelKt.canOpenInBrowser(url: f.feed.site_url)
        }
        guard let sdk = model.sdk else {
            return MenuUiState(
                addItemsEnabled: false, opmlEnabled: false, searchEnabled: false, unreadOnlyEnabled: false,
                unreadOnlyChecked: false, toggleSortEnabled: false, markAllReadEnabled: false,
                articleActionsEnabled: false, copyUrlEnabled: false, openInBrowserEnabled: false,
                refreshAllEnabled: false,
                syncEnabled: false, openSettingsEnabled: false, feedActionsEnabled: false,
                feedSiteCopyEnabled: false, feedSiteOpenEnabled: false, renameOrDeleteEnabled: false,
                renameOrDeleteShortcutActive: false
            )
        }
        return sdk.menuState(
            onHome: !model.needsSetup,
            hasSelectedArticle: home.hasSelectedArticle,
            selectedArticleHasUrl: home.selectedArticleHasUsableUrl,
            selectedArticleCanOpenInBrowser: home.selectedArticleCanOpenInBrowser,
            canSyncNow: home.canSyncNow,
            searchActive: home.searchActive,
            unreadOnly: home.unreadOnly,
            opmlBusy: model.opmlTransfer?.isBusy ?? false,
            hasSelectedFeed: hasSelectedFeed,
            feedListKeysActive: bareKeysActive,
            hasRenamableSelection: target != nil,
            selectedFeedHasSiteUrl: selectedFeedHasSiteUrl,
            selectedFeedSiteCanOpenInBrowser: selectedFeedSiteCanOpenInBrowser
        )
    }

    private func selectionTarget(_ home: HomeObservable) -> FeedListSelectionTarget? {
        home.feedListSelectionTarget
    }

    /// The feed the Feed menu's selected-feed items (Refresh/Tags/Move to folder/Copy URL/…) act
    /// on — only when the sidebar's own selection is a feed itself, matching Compose's own
    /// `selectedFeedForMenu()` (`HomeScreen.kt`).
    private func selectedFeed(_ home: HomeObservable) -> Feeds? {
        guard let target = selectionTarget(home), case .feed(let f) = onEnum(of: target) else { return nil }
        return f.feed
    }

    private func sortedFolders(_ home: HomeObservable) -> [Folders] {
        home.sortedFolders
    }

    private func sortedTags(_ home: HomeObservable) -> [Tags] {
        home.sortedTags
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
        model.sidebarDialogs.startRename(home.selectedRowInstance)
    }

    private func performDelete(_ home: HomeObservable) {
        guard let target = selectionTarget(home) else { return }
        switch onEnum(of: target) {
        case .feed(let f): model.sidebarDialogs.unsubscribingFeed = f.feed
        case .folder(let f): model.sidebarDialogs.deletingFolder = f.folder
        case .tag(let t): model.sidebarDialogs.deletingTag = t.tag
        }
    }
}
