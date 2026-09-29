import KeryxShared
import SwiftUI

/// The center pane: toolbar (unread-only, hide-read, sort, mark-all-read, the notification bell),
/// the article rows themselves (search-highlighted when a query is active), the "new articles"
/// pill, and the four empty states — see `external-spec.md` §7's article-list bullets and the M2
/// research notes.
struct ArticleListView: View {
    let home: HomeObservable
    let notifications: NotificationCenterObservable
    let settingsNavigation: SettingsNavigation
    @Bindable var dialogs: SidebarDialogState
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    /// The selection is drawn strongly only while this window is key (AppKit's own rule for a
    /// source list's selection), on top of the article list holding the pane focus.
    #if os(macOS)
    @Environment(\.controlActiveState) private var controlActiveState
    private var windowIsKey: Bool { controlActiveState == .key }
    #else
    /// iOS has no key-window distinction for this, so the pane focus alone decides.
    private var windowIsKey: Bool { true }
    #endif

    @State private var appearedIds: Set<String> = []
    /// Whether the new-articles pill is actually shown, debounced against `home.newArticleCount`
    /// itself — see `pillShowDelayTask`'s own KDoc for why.
    @State private var pillShown = false

    /// Debounced task for reporting visible article ids. Rapid `onAppear`/`onDisappear` pairs from
    /// scrolling are coalesced into a single report after a short delay — mirroring Compose's
    /// `snapshotFlow { ... }.distinctUntilChanged()` behaviour.
    @State private var visibleReportTask: Task<Void, Never>? = nil

    /// Whether the first real rows have been shown — see the restored-selection scroll in `body`.
    @State private var didShowFirstRows = false

    /// The rows on screen: the search results while a search is active, else the filter's own list.
    /// Both are resolved once per emission by `HomeObservable` (see `ArticleRowModel`).
    private var displayed: ArticleRowList { home.searchActive ? home.searchRows : home.articleRows }
    private var displayedRows: [ArticleRowModel] { displayed.rows }

    /// A plain `String` key for `home.filter` (a Kotlin sealed-type protocol value), so
    /// `.onChange(of:)` — which requires a genuinely `Equatable` value type, not a bridged
    /// existential — has something safe to compare.
    private var filterKey: String {
        switch onEnum(of: home.filter) {
        case .all: return "all"
        case .starred: return "starred"
        case .feed(let f): return "feed:\(f.feedId)"
        case .folder(let f): return "folder:\(f.folderId)"
        case .tag(let t): return "tag:\(t.tagId)"
        }
    }

    var body: some View {
        VStack(spacing: 0) {
            ScrollViewReader { proxy in
                content
                    // Resets to the top on every filter switch, whatever the sort order — unlike
                    // the new-articles pill's own jump, which follows it (`ArticleListPane.kt:205-213`
                    // vs. `NewArticlesPill`'s own "fresh end"). `initial: false` keeps the first
                    // composition — including a restored `lastArticleId` selection — from being
                    // scrolled out from under it.
                    .onChange(of: filterKey, initial: false) { _, _ in
                        if let first = displayedRows.first?.id { proxy.scrollTo(first) }
                    }
                    // Only when the selection actually moved off-screen (keyboard navigation, a
                    // restored selection) — a row already visible (e.g. just clicked) never jumps.
                    .onChange(of: home.selectedArticle?.id) { _, id in
                        guard let id, !appearedIds.contains(id) else { return }
                        proxy.scrollTo(id)
                    }
                    .overlay(alignment: home.newestFirst ? .top : .bottom) {
                        if pillShown {
                            newArticlesPill(proxy: proxy)
                        }
                    }
                    .task(id: home.newArticleCount) {
                        await updatePillShown()
                    }
                    .onChange(of: displayedRows.isEmpty) { _, isEmpty in
                        // The article restored from the previous session is already selected before
                        // any row exists, so the selection-change scroll above never sees it; bring it
                        // into view once, when the first rows land (after they are laid out).
                        guard !didShowFirstRows, !isEmpty else { return }
                        didShowFirstRows = true
                        guard let id = home.selectedArticle?.id else { return }
                        Task {
                            await Task.yield()
                            proxy.scrollTo(id)
                        }
                    }
            }
        }
        .focused(focusedPane, equals: .articleList)
        .toolbar { toolbarContent }
    }

    /// The pill's count going from `0` to positive is deliberately not shown immediately — the
    /// article list's own visible-id report lands a frame after a new query result does, so an
    /// article landing *inside* the current viewport would otherwise flash the pill for a single
    /// frame before the visibility report catches up and drops it back out of the count. Going
    /// back to `0` is always immediate. Mirrors Compose's own `NewArticlesPill` (`NewArticlesPill.kt`).
    private func updatePillShown() async {
        guard home.newArticleCount > 0 else {
            pillShown = false
            return
        }
        try? await Task.sleep(for: .milliseconds(200))
        guard !Task.isCancelled else { return }
        pillShown = true
    }

    // MARK: - Toolbar

    // Lives in the window toolbar (the strip above this column), not in the pane: the native
    // toolbar draws its buttons at the platform's standard size and groups them, replacing the
    // hand-rolled capsule Compose uses as a stand-in (see `ui-guidelines`' "Icon grouping").
    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        ToolbarItem(placement: .navigation) {
            HStack(spacing: 4) {
                Toggle(isOn: Binding(
                    get: { home.unreadOnly },
                    set: { home.viewModel.setUnreadOnly(value: $0) }
                )) {
                    Label(
                        L("home_unread_only"),
                        systemImage: home.unreadOnly
                            ? "line.3.horizontal.decrease.circle.fill"
                            : "line.3.horizontal.decrease.circle"
                    )
                    .labelStyle(.iconOnly)
                }
                .toggleStyle(.button)
                .help(L("home_unread_only"))

                Button {
                    home.viewModel.hideRead()
                } label: {
                    Image(systemName: "eye.slash")
                }
                .disabled(!home.canHideRead)
                .help(L("home_hide_read"))
                .accessibilityLabel(L("home_hide_read"))
            }
        }

        // `.primaryAction` still flows from the column's leading edge; a flexible spacer (macOS 26)
        // is what pushes the trailing cluster to the column's right edge.
        if #available(macOS 26, iOS 26, *) {
            ToolbarSpacer(.flexible)
        }

        ToolbarItemGroup(placement: .primaryAction) {
            NotificationBell(home: home, notifications: notifications, settingsNavigation: settingsNavigation, focusedPane: focusedPane)

            Button {
                home.viewModel.toggleSort()
            } label: {
                Image(systemName: home.newestFirst ? "arrow.down" : "arrow.up")
            }
            .disabled(home.searchActive)
            .help(L(home.searchActive ? "home_sort_disabled_search" : (home.newestFirst ? "home_sort_oldest" : "home_sort_newest")))
            .accessibilityLabel(L(home.newestFirst ? "home_sort_oldest" : "home_sort_newest"))

            Button {
                home.viewModel.markAllRead()
            } label: {
                Image(systemName: "checklist.checked")
            }
            .help(L("home_mark_all_read"))
            .accessibilityLabel(L("home_mark_all_read"))
        }
    }

    // MARK: - Content / empty states

    @ViewBuilder
    private var content: some View {
        if home.feeds.isEmpty {
            ContentUnavailableView {
                Label(L("home_no_feeds"), systemImage: "tray")
            } actions: {
                Button(L("home_add_feed")) { dialogs.isAddingFeed = true }
            }
        } else if home.searchActive {
            searchContent
        } else if displayedRows.isEmpty {
            ContentUnavailableView(
                L("home_no_articles"),
                systemImage: "doc.text"
            )
        } else {
            articleList
        }
    }

    /// Mirrors Compose's own `emptyContent` `when` in `ArticleListPane.kt`: a query with no
    /// 2+-character word (`searchTerms`, shared with the trigram/`LIKE` split `FtsSearch` makes)
    /// is "too short"; an in-flight search with nothing yet shows nothing at all, rather than
    /// flashing "no results" between keystrokes; only a *settled* empty result set is "no results".
    @ViewBuilder
    private var searchContent: some View {
        if SearchQueryKt.searchTerms(raw: home.searchQuery).isEmpty {
            ContentUnavailableView(
                L("home_search_too_short"),
                systemImage: "magnifyingglass"
            )
        } else if home.searching && home.searchResults.isEmpty {
            Color.clear
        } else if home.searchResults.isEmpty {
            noSearchResultsView
        } else {
            articleList
        }
    }

    /// A secondary line pointing at "All Feeds" only when the search is actually narrowed to
    /// something less than that — switching to All wouldn't change anything otherwise. Mirrors
    /// Compose's own `NoSearchResultsHint` (`ArticleListPane.kt`).
    private var noSearchResultsView: some View {
        VStack(spacing: 4) {
            Text(L("home_search_no_results")).font(.caption).foregroundStyle(.secondary)
            if !isAllFeedsFilter {
                Text(L("home_search_try_all_feeds")).font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var isAllFeedsFilter: Bool {
        if case .all = onEnum(of: home.filter) { return true }
        return false
    }

    private var articleList: some View {
        let selectedId = home.selectedArticle?.id
        let paneFocused = focusedPane.wrappedValue == .articleList && windowIsKey
        return List(displayedRows) { article in
            ArticleRowView(
                model: article,
                isSelected: article.id == selectedId,
                paneFocused: paneFocused,
                viewModel: home.viewModel,
                onSelect: {
                    focusedPane.wrappedValue = .articleList
                    home.viewModel.selectArticle(article: article.row)
                },
                onContextMenuSelect: { selectForContextMenu(article.row) }
            )
            .equatable()
            // No horizontal inset of our own: the macOS List already pads each row's content by
            // 8pt on either side, which alone matches Compose's `listRowHorizontalMargin()`
            // (8dp). Adding 8 here on top of it doubled the card's gap from the pane edge.
            .listRowInsets(EdgeInsets(top: 2, leading: 0, bottom: 2, trailing: 0))
            .listRowSeparator(.hidden)
            .onAppear {
                appearedIds.insert(article.id)
                scheduleReportVisible()
            }
            .onDisappear {
                appearedIds.remove(article.id)
                scheduleReportVisible()
            }
        }
        .listStyle(.plain)
    }

    /// Schedules a debounced report of visible article ids. Rapid `onAppear`/`onDisappear` pairs
    /// from a single scroll gesture are coalesced into one `markArticlesSeen` call after a short
    /// delay, mirroring Compose's own `distinctUntilChanged` behaviour.
    private func scheduleReportVisible() {
        visibleReportTask?.cancel()
        visibleReportTask = Task {
            try? await Task.sleep(for: .milliseconds(50))
            guard !Task.isCancelled else { return }
            reportVisible()
        }
    }

    /// Suppressed during search: the visible rows are search results, not the underlying filter's
    /// own list, and reporting them as "seen" would corrupt `newArticleCount`'s bookkeeping once
    /// the search closes (`ArticleListPane.kt`'s own guard).
    private func reportVisible() {
        guard !home.searchActive else { return }
        let ordered = displayedRows.filter { appearedIds.contains($0.id) }.map(\.id)
        home.viewModel.markArticlesSeen(ids: ordered)
    }

    // MARK: - Row

    private func selectForContextMenu(_ article: ArticleListRow) {
        if home.selectedArticle?.id != article.id {
            home.viewModel.selectArticle(article: article)
        }
    }

    // MARK: - New articles pill

    @ViewBuilder
    private func newArticlesPill(proxy: ScrollViewProxy) -> some View {
        Button {
            home.viewModel.markAllArticlesSeen()
            scrollToFreshEnd(proxy)
        } label: {
            Label(LF("home_new_articles", Int64(home.newArticleCount)), systemImage: home.newestFirst ? "arrow.up" : "arrow.down")
                .font(.callout)
                .padding(.horizontal, 12)
                .padding(.vertical, 6)
                .background(Capsule().fill(Color.accentColor))
                .foregroundStyle(.white)
        }
        .buttonStyle(.plain)
        .padding(.top, home.newestFirst ? 8 : 0)
        .padding(.bottom, home.newestFirst ? 0 : 8)
    }

    private func scrollToFreshEnd(_ proxy: ScrollViewProxy) {
        guard let target = home.newestFirst ? displayedRows.first?.id : displayedRows.last?.id else { return }
        proxy.scrollTo(target, anchor: home.newestFirst ? .top : .bottom)
    }
}
