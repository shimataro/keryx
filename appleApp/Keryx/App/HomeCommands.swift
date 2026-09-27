import KeryxShared
import SwiftUI
#if os(macOS)
import AppKit
import UniformTypeIdentifiers
#endif

/// The application menu bar's dynamic items (File/View/Article/Feed/Help) — see
/// `presentation/menu/MenuState.kt`'s `computeMenuUiState` for the enabled/checked rules this
/// mirrors. This app has exactly one window showing Home (Setup takes over before Home ever
/// appears), so this reads `AppModel`'s state directly rather than through `FocusedValue` — there
/// is no second window whose state could otherwise disagree with the menu.
struct HomeCommands: Commands {
    let model: AppModel

    #if os(macOS)
    @Environment(\.openWindow) private var openWindow
    #endif

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
                    .disabled(!menuState(home).opmlEnabled)
                Button(L("menu_file_export_opml")) { exportOpml() }
                    .disabled(!menuState(home).opmlEnabled)
                #endif
            }
        }

        CommandGroup(after: .toolbar) {
            if let home = model.home {
                let state = menuState(home)
                Button(L("menu_view_search")) {
                    home.viewModel.setSearchBarVisible(visible: true)
                    home.viewModel.requestSearchFocus()
                }
                .keyboardShortcut("f", modifiers: .command)
                .disabled(!state.searchEnabled)

                Toggle(L("menu_view_unread_only"), isOn: Binding(
                    get: { home.unreadOnly },
                    set: { home.viewModel.setUnreadOnly(value: $0) }
                ))
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
                    .disabled(!state.articleActionsEnabled)
                Button(L("menu_article_toggle_star")) { home.viewModel.toggleStarSelected() }
                    .disabled(!state.articleActionsEnabled)
                Button(L("menu_article_open_in_browser")) {
                    if let url = home.selectedArticle?.url { openInBrowser(url) }
                }
                .disabled(!state.urlActionsEnabled)
                Button(L("menu_article_copy_url")) {
                    if let url = home.selectedArticle?.url { copyToPasteboard(url) }
                }
                .disabled(!state.urlActionsEnabled)
            }
        }

        CommandMenu(L("menu_feed")) {
            if let home = model.home {
                let state = menuState(home)
                Button(L("menu_feed_refresh_all")) { home.viewModel.refreshAll() }
                    .disabled(!state.refreshAllEnabled)
                Button(L("menu_feed_sync_now")) { home.viewModel.sync() }
                    .disabled(!state.syncEnabled)
            }
        }

        CommandGroup(replacing: .help) {
            Button(L("menu_help_website")) { openInBrowser(L("website_url")) }
            Button(L("menu_help_project_page")) { openInBrowser(projectUrl) }
        }
    }

    /// `sdk.menuState(...)` needs several booleans this app doesn't track anywhere else yet
    /// (`hasSelectedFeed`/`selectedFeedHasSiteUrl`/`hasRenamableSelection`/`textInputFocused`); this
    /// resolves them the same way `HomeView`'s own rename/delete keyboard handling does, via
    /// `resolveFeedListSelectionTarget`, rather than leaving them permanently at their `false`
    /// defaults (which would incorrectly grey out every feed-specific menu item).
    private func menuState(_ home: HomeObservable) -> MenuUiState {
        let target = FeedListModelKt.resolveFeedListSelectionTarget(
            filter: home.filter, feeds: home.feeds, folders: home.folders, tags: home.tags
        )
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
            onHome: true,
            hasSelectedArticle: home.selectedArticle != nil,
            selectedArticleHasUrl: ArticleListModelKt.hasUsableUrl(url: home.selectedArticle?.url),
            cloudConnected: home.cloudConnected,
            searchActive: home.searchActive,
            unreadOnly: home.unreadOnly,
            hasSelectedFeed: hasSelectedFeed,
            textInputFocused: false,
            hasRenamableSelection: target != nil,
            selectedFeedHasSiteUrl: selectedFeedHasSiteUrl
        )
    }

    #if os(macOS)
    private func importOpml() {
        guard let sdk = model.sdk else { return }
        let panel = NSOpenPanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "opml") ?? .xml, .xml]
        panel.allowsMultipleSelection = false
        guard panel.runModal() == .OK, let url = panel.url else { return }
        guard let xml = try? String(contentsOf: url, encoding: .utf8) else { return }
        Task { _ = try? await sdk.opml.importOpml(xml: xml) }
    }

    private func exportOpml() {
        guard let sdk = model.sdk else { return }
        let panel = NSSavePanel()
        panel.allowedContentTypes = [UTType(filenameExtension: "opml") ?? .xml]
        panel.nameFieldStringValue = "keryx-feeds.opml"
        guard panel.runModal() == .OK, let url = panel.url else { return }
        try? sdk.opml.exportOpml().write(to: url, atomically: true, encoding: .utf8)
    }
    #endif
}
