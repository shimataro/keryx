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
    /// Whether the split view is collapsed with this list as its topmost column — it then keeps no
    /// selection on screen (`CompactArticleSelection`). Always false on macOS.
    let articleListIsTopmost: Bool
    /// The row flashing gray after the reader was popped back to it — see `HomeView.returnFlashId`.
    let returnFlashId: String?
    /// Pushes the reader when the split view is collapsed (iPhone); a no-op otherwise. A row is a
    /// plain button rather than a `List` selection, so nothing navigates to the reader by itself.
    let onOpenArticle: () -> Void

    /// The selection is drawn strongly only while this window is key (AppKit's own rule for a
    /// source list's selection), on top of the article list holding the pane focus.
    #if os(macOS)
    @Environment(\.controlActiveState) private var controlActiveState
    private var windowIsKey: Bool { controlActiveState == .key }
    #else
    /// iOS has no key-window distinction for this, so the pane focus alone decides.
    private var windowIsKey: Bool { true }
    #endif

    #if os(macOS)
    /// Handed to every hosted row explicitly — see `ArticleTableView.contextMenuSelectionTracker`.
    @Environment(\.contextMenuSelectionTracker) private var contextMenuSelectionTracker
    /// Bumped on every filter switch — see `ArticleTableView.resetGeneration`.
    @State private var tableResetGeneration = 0
    /// The new-articles pill's pending jump — see `ArticleTableScrollRequest`.
    @State private var tableScrollRequest = ArticleTableScrollRequest()
    #else
    /// Which rows are on screen, and the pending report of them — see `VisibleRowTracker`.
    @State private var visibleRows = VisibleRowTracker()
    /// Whether the first real rows have been shown — see the restored-selection scroll in `body`.
    @State private var didShowFirstRows = false
    #endif

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
            #if os(macOS)
            // `ArticleTableView` does its own scrolling: back to the top on a filter switch, to an
            // off-screen selection, and to the fresh end for the pill.
            withNewArticlesPill(content) {
                tableScrollRequest = ArticleTableScrollRequest(
                    generation: tableScrollRequest.generation + 1,
                    newestFirst: home.newestFirst
                )
            }
            .onChange(of: filterKey, initial: false) { _, _ in
                tableResetGeneration += 1
            }
            #else
            ScrollViewReader { proxy in
                withNewArticlesPill(content) { scrollToFreshEnd(proxy) }
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
                    .onChange(of: home.selectedArticleId) { _, id in
                        guard let id, !visibleRows.appearedIds.contains(id) else { return }
                        proxy.scrollTo(id)
                    }
                    .onChange(of: displayedRows.isEmpty) { _, isEmpty in
                        // The article restored from the previous session is already selected before
                        // any row exists, so the selection-change scroll above never sees it; bring it
                        // into view once, when the first rows land (after they are laid out).
                        guard !didShowFirstRows, !isEmpty else { return }
                        didShowFirstRows = true
                        guard let id = home.selectedArticleId else { return }
                        Task {
                            await Task.yield()
                            proxy.scrollTo(id)
                        }
                    }
            }
            #endif
        }
        #if os(iOS)
        .focused(focusedPane, equals: .articleList)
        #endif
        .toolbar { toolbarContent }
    }

    /// Overlays the new-articles pill on `list`; tapping it clears the count and runs `jump`.
    private func withNewArticlesPill(_ list: some View, jump: @escaping () -> Void) -> some View {
        list
            .overlay(alignment: home.newestFirst ? .top : .bottom) {
                if home.newArticlesPillVisible {
                    newArticlesPill(jump: jump)
                }
            }
            .task(id: home.newArticleCount) {
                await updatePillShown()
            }
            // Not on screen (e.g. a phone-width reader pushed over the list): nothing for the copy
            // toast to keep clear of. `.task(id:)` runs again when the list reappears.
            .onDisappear { home.newArticlesPillVisible = false }
    }

    /// The pill's count going from `0` to positive is deliberately not shown immediately — the
    /// article list's own visible-id report lands a frame after a new query result does, so an
    /// article landing *inside* the current viewport would otherwise flash the pill for a single
    /// frame before the visibility report catches up and drops it back out of the count. Going
    /// back to `0` is always immediate. Mirrors Compose's own `NewArticlesPill` (`NewArticlesPill.kt`).
    /// The result lives in `HomeObservable.newArticlesPillVisible` so the copy toast, drawn by
    /// `HomeView` over every column, can keep clear of the pill.
    private func updatePillShown() async {
        guard home.newArticleCount > 0 else {
            home.newArticlesPillVisible = false
            return
        }
        try? await Task.sleep(for: .milliseconds(200))
        guard !Task.isCancelled else { return }
        home.newArticlesPillVisible = true
    }

    // MARK: - Toolbar

    // Lives in the window toolbar (the strip above this column), not in the pane: the native
    // toolbar draws its buttons at the platform's standard size and groups them, replacing the
    // hand-rolled capsule Compose uses as a stand-in (see `ui-guidelines`' "Icon grouping").
    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        // Every item's label is a `Label`, not a bare icon: the toolbar still renders icon-only, but
        // the overflow menu it collapses into at a narrow width takes each row's title from it.
        // Separate items of one group (not an `HStack` in one item) so each gets its own row there.
        ToolbarItemGroup(placement: .navigation) {
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
            }
            .toggleStyle(.button)
            .help(L("home_unread_only"))

            Button {
                home.viewModel.hideRead()
            } label: {
                Label(L("home_hide_read"), systemImage: "eye.slash")
            }
            .disabled(!home.canHideRead)
            .help(L("home_hide_read"))
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
                Label(
                    L(home.newestFirst ? "home_sort_oldest" : "home_sort_newest"),
                    systemImage: home.newestFirst ? "arrow.down" : "arrow.up"
                )
            }
            .disabled(home.searchActive)
            .help(L(home.searchActive ? "home_sort_disabled_search" : (home.newestFirst ? "home_sort_oldest" : "home_sort_newest")))

            Button {
                home.viewModel.markAllRead()
            } label: {
                Label(L("home_mark_all_read"), systemImage: "checkmark.circle")
            }
            .help(L("home_mark_all_read"))
        }
    }

    // MARK: - Content / empty states

    @ViewBuilder
    private var content: some View {
        if !home.hasFeeds {
            ContentUnavailableView {
                Label(L("home_no_feeds"), systemImage: "tray")
            } actions: {
                Button(L("home_add_feed")) { dialogs.isAddingFeed = true }
            }
        } else if home.searchActive {
            searchContent
        } else if displayedRows.isEmpty {
            noArticlesView
        } else {
            articleList
                .modifier(PullToRefreshModifier(home: home))
        }
    }

    /// Scrollable on iOS so an empty list can still be pulled to refresh (`external-spec.md` §9:
    /// "The gesture works on an empty list too", e.g. unread-only with nothing unread).
    @ViewBuilder
    private var noArticlesView: some View {
        let emptyState = ContentUnavailableView(L("home_no_articles"), systemImage: "doc.text")
        #if os(iOS)
        GeometryReader { geometry in
            ScrollView {
                emptyState.frame(width: geometry.size.width, height: geometry.size.height)
            }
            .modifier(PullToRefreshModifier(home: home))
        }
        #else
        emptyState
        #endif
    }

    /// Mirrors Compose's own `emptyContent` `when` in `ArticleListPane.kt`: a query with no
    /// 2+-character word (`searchTerms`, shared with the trigram/`LIKE` split `FtsSearch` makes —
    /// resolved once per query change as `searchQueryHasTerms`) is "too short"; an in-flight search
    /// with nothing yet shows nothing at all, rather than flashing "no results" between keystrokes;
    /// only a *settled* empty result set is "no results".
    @ViewBuilder
    private var searchContent: some View {
        if !home.searchQueryHasTerms {
            ContentUnavailableView(
                L("home_search_too_short"),
                systemImage: "magnifyingglass"
            )
        } else if home.searching && !home.hasSearchResults {
            Color.clear
        } else if !home.hasSearchResults {
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

    /// The selected row as the list draws it — see `CompactArticleSelection`.
    private var displayedSelectedId: String? {
        CompactArticleSelection.displayedId(selectedId: home.selectedArticleId, articleListIsTopmost: articleListIsTopmost)
    }

    private func rowView(_ article: ArticleRowModel, selectedId: String?, paneFocused: Bool) -> ArticleRowView {
        ArticleRowView(
            model: article,
            isSelected: article.id == selectedId,
            isReturnFlashing: article.id == returnFlashId,
            paneFocused: paneFocused,
            viewModel: home.viewModel,
            onSelect: {
                focusedPane.wrappedValue = .articleList
                home.viewModel.selectArticle(article: article.row)
                onOpenArticle()
            },
            onContextMenuSelect: { selectForContextMenu(article.row) },
            onCopyUrl: { home.copyArticleUrl(url: article.url, articleId: article.id) }
        )
    }

    #if os(macOS)
    private var articleList: some View {
        let selectedId = displayedSelectedId
        let paneFocused = focusedPane.wrappedValue == .articleList && windowIsKey
        return ArticleTableView(
            rows: displayed,
            selectedId: selectedId,
            resetGeneration: tableResetGeneration,
            scrollRequest: tableScrollRequest,
            contextMenuSelectionTracker: contextMenuSelectionTracker,
            makeRow: { rowView($0, selectedId: selectedId, paneFocused: paneFocused) },
            onBackgroundClick: { focusedPane.wrappedValue = .articleList },
            onVisibleIdsChanged: { reportVisible($0) }
        )
        // Rows scroll under the toolbar, as they did in the `List`; the table insets its content by
        // the toolbar's height itself.
        .ignoresSafeArea(edges: .top)
        .modifier(SoftTopScrollEdge())
        // SwiftUI hands a representable's focus to its AppKit views — the table takes the first
        // responder and passes every key on to `HomeView`'s handler (`ArticleNSTableView`). The
        // focus binding sits here rather than on the container in `body`, which is only focusable
        // through a focusable child such as iOS's `List`. `.edit`, because the default (`.activate`)
        // follows the system's keyboard-navigation setting, off by default.
        .focusable(interactions: .edit)
        .focusEffectDisabled()
        .focused(focusedPane, equals: .articleList)
    }
    #else
    private var articleList: some View {
        let selectedId = displayedSelectedId
        let paneFocused = focusedPane.wrappedValue == .articleList && windowIsKey
        return List(displayedRows) { article in
            rowView(article, selectedId: selectedId, paneFocused: paneFocused)
                .equatable()
                // No horizontal inset of our own: the List's own row padding already supplies the
                // card's gap from the pane edge (on macOS, where this list used to run too, 8pt —
                // Compose's `listRowHorizontalMargin()`); adding 8 on top of it doubled that gap.
                .listRowInsets(EdgeInsets(top: 2, leading: 0, bottom: 2, trailing: 0))
                .listRowSeparator(.hidden)
                .onAppear {
                    visibleRows.appearedIds.insert(article.id)
                    scheduleReportVisible()
                }
                .onDisappear {
                    visibleRows.appearedIds.remove(article.id)
                    scheduleReportVisible()
                }
        }
        .listStyle(.plain)
    }

    /// Schedules a debounced report of visible article ids. Rapid `onAppear`/`onDisappear` pairs
    /// from a single scroll gesture are coalesced into one `markArticlesSeen` call after a short
    /// delay, mirroring Compose's own `distinctUntilChanged` behaviour.
    private func scheduleReportVisible() {
        visibleRows.reportTask?.cancel()
        visibleRows.reportTask = Task {
            try? await Task.sleep(for: .milliseconds(50))
            guard !Task.isCancelled else { return }
            let indexById = displayed.indexById
            let ordered = visibleRows.appearedIds
                .compactMap { id in indexById[id].map { (index: $0, id: id) } }
                .sorted { $0.index < $1.index }
                .map(\.id)
            reportVisible(ordered)
        }
    }
    #endif

    /// Reports the rows on screen, in display order. Suppressed during search: the visible rows are
    /// search results, not the underlying filter's own list, and reporting them as "seen" would
    /// corrupt `newArticleCount`'s bookkeeping once the search closes (`ArticleListPane.kt`'s own
    /// guard).
    private func reportVisible(_ ids: [String]) {
        guard !home.searchActive else { return }
        home.viewModel.markArticlesSeen(ids: ids)
    }

    // MARK: - Row

    private func selectForContextMenu(_ article: ArticleListRow) {
        if home.selectedArticleId != article.id {
            home.viewModel.selectArticle(article: article)
        }
    }

    // MARK: - New articles pill

    @ViewBuilder
    private func newArticlesPill(jump: @escaping () -> Void) -> some View {
        Button {
            home.viewModel.markAllArticlesSeen()
            jump()
        } label: {
            PillLabel(
                title: LF("home_new_articles", Int64(home.newArticleCount)),
                systemImage: home.newestFirst ? "arrow.up" : "arrow.down"
            )
        }
        .buttonStyle(.plain)
        .padding(.top, home.newestFirst ? PillLabel.edgeInset : 0)
        .padding(.bottom, home.newestFirst ? 0 : PillLabel.edgeInset)
    }

    #if os(iOS)
    private func scrollToFreshEnd(_ proxy: ScrollViewProxy) {
        guard let target = home.newestFirst ? displayedRows.first?.id : displayedRows.last?.id else { return }
        proxy.scrollTo(target, anchor: home.newestFirst ? .top : .bottom)
    }
    #endif
}

/// Pull-to-refresh on iOS (`external-spec.md` §9), scoped to the current selection's feeds and held
/// up until the refresh and its sync finish — see `HomeObservable.pullToRefresh`. macOS has no pull
/// gesture, as Compose's desktop list has none.
private struct PullToRefreshModifier: ViewModifier {
    let home: HomeObservable

    func body(content: Content) -> some View {
        #if os(iOS)
        if PullToRefreshAvailability.isAvailable(searchActive: home.searchActive, hasFeeds: home.hasFeeds) {
            content.refreshable { await home.pullToRefresh() }
        } else {
            content
        }
        #else
        content
        #endif
    }
}

#if os(iOS)
/// The article list's on-screen rows and its pending visible-row report. A plain class held in
/// `@State` rather than `@State` values of their own: every row scrolling in or out writes to it,
/// and nothing here is drawn, so those writes must not invalidate the list's body.
@MainActor
private final class VisibleRowTracker {
    var appearedIds: Set<String> = []
    /// Rapid `onAppear`/`onDisappear` pairs from scrolling are coalesced into a single report after
    /// a short delay — mirroring Compose's `snapshotFlow { ... }.distinctUntilChanged()` behaviour.
    var reportTask: Task<Void, Never>?
}
#endif
