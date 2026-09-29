import KeryxShared
import SwiftUI

private let markStart: Character = "\u{0002}"
private let markEnd: Character = "\u{0003}"

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

    @State private var cachedDisplayedRows: [ArticleListRow] = []
    @State private var cachedTitleMarks: [String: String] = [:]
    /// Whether the first real rows have been shown — see the restored-selection scroll in `body`.
    @State private var didShowFirstRows = false

    #if os(macOS)
    private static let strongSelectionFill = Color(nsColor: .selectedContentBackgroundColor)
    private static let dimmedSelectionFill = Color(nsColor: .unemphasizedSelectedContentBackgroundColor)
    #else
    // UIKit has no content-selection colors; these are the closest system equivalents.
    private static let strongSelectionFill = Color.accentColor
    private static let dimmedSelectionFill = Color(uiColor: .systemGray4)
    #endif

    private var displayedRows: [ArticleListRow] { cachedDisplayedRows }
    private var titleMarks: [String: String] { cachedTitleMarks }

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
                    .onChange(of: home.articles) { _, _ in
                        recacheDisplayedRows()
                        // The article restored from the previous session is already selected before
                        // any row exists, so the selection-change scroll above never sees it; bring it
                        // into view once, when the first rows land (after they are laid out).
                        guard !didShowFirstRows, !displayedRows.isEmpty else { return }
                        didShowFirstRows = true
                        guard let id = home.selectedArticle?.id else { return }
                        Task {
                            await Task.yield()
                            proxy.scrollTo(id)
                        }
                    }
                    .onChange(of: home.searchResults) { _, _ in recacheDisplayedRows() }
                    .onChange(of: home.searchActive) { _, _ in recacheDisplayedRows() }
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
        List(displayedRows, id: \.id) { article in
            row(article)
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

    /// Updates the cached `displayedRows` and `titleMarks` so they are not recomputed on every
    /// body evaluation. Called by `.onChange` observers on the underlying data sources.
    private func recacheDisplayedRows() {
        if home.searchActive {
            cachedDisplayedRows = home.searchResults.map(\.article)
            cachedTitleMarks = Dictionary(uniqueKeysWithValues: home.searchResults.map { ($0.article.id, $0.titleMarked) })
        } else {
            cachedDisplayedRows = home.articles
            cachedTitleMarks = [:]
        }
    }

    // MARK: - Row

    @ViewBuilder
    private func row(_ article: ArticleListRow) -> some View {
        Button {
            focusedPane.wrappedValue = .articleList
            home.viewModel.selectArticle(article: article)
        } label: {
            let isSelected = home.selectedArticle?.id == article.id
            let paneFocused = focusedPane.wrappedValue == .articleList && windowIsKey
            // Same treatment as Compose's `onPrimary`: on the strong (focused) selection fill the
            // text turns light; on the dimmed (unfocused) one it keeps its ordinary colors.
            let onStrongSelection = isSelected && paneFocused
            HStack(alignment: .center, spacing: 0) {
                // Fixed slot, always reserved, so the title never shifts when the dot or star appears.
                ZStack {
                    if article.is_read == 0 {
                        Circle().fill(onStrongSelection ? Color.white : Color.accentColor).frame(width: 8, height: 8)
                    }
                    if article.is_starred == 1 {
                        Image(systemName: "star.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(.yellow)
                            .frame(maxHeight: .infinity, alignment: .top)
                    }
                }
                .frame(width: 14)
                FaviconView(url: home.feedsById[article.feed_id]?.favicon_url, letter: article.title.first, blankWithoutUrl: true)
                    .frame(width: 32, height: 32)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                    .padding(.leading, 6)
                VStack(alignment: .leading, spacing: 2) {
                    Text(article.title.isEmpty ? AttributedString(L("article_no_title")) : titleAttributedString(article))
                        .font(article.is_read == 1 ? .body : .body.bold())
                        .foregroundStyle(onStrongSelection ? AnyShapeStyle(Color.white) : AnyShapeStyle(article.is_read == 1 ? HierarchicalShapeStyle.secondary : HierarchicalShapeStyle.primary))
                        .lineLimit(2, reservesSpace: true)
                    HStack(spacing: 6) {
                        if let feedTitle = home.feedsById[article.feed_id]?.keryxDisplayTitle() {
                            Text(feedTitle).lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        Text(FormattingKt.formatTimestamp(epochMillis: article.published_at)).lineLimit(1)
                    }
                    .font(.caption)
                    .foregroundStyle(onStrongSelection ? AnyShapeStyle(Color.white.opacity(0.8)) : AnyShapeStyle(HierarchicalShapeStyle.secondary))
                }
                .padding(.leading, 10)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 10)
            // A plain-style button only hit-tests what it draws; without this the Spacer and the
            // padding are dead zones.
            .contentShape(Rectangle())
            .background {
                if isSelected {
                    RoundedRectangle(cornerRadius: 6)
                        .fill(paneFocused ? Self.strongSelectionFill : Self.dimmedSelectionFill)
                }
            }
        }
        .buttonStyle(.plain)
        .selectsOnContextMenu(id: article.id) { selectForContextMenu(article) }
        .contextMenu {
            // Opening the menu selects the row first, matching Compose's own `onOpen = onClick`
            // (`ArticleRowComponents.kt`) — the actual selection runs on a right-click/Control-
            // click via `.selectsOnContextMenu` above, not as a side effect of this builder (see
            // `ContextMenuSelectionTracker`'s own doc for why).
            Button(L(article.is_starred == 1 ? "article_unstar" : "article_star")) {
                home.viewModel.toggleStar(article: article)
            }
            Button(L(article.is_read == 1 ? "article_mark_as_unread" : "article_mark_as_read")) {
                home.viewModel.toggleRead(article: article)
            }
            Button(L("article_copy_url")) {
                copyToPasteboard(article.url)
            }
            .disabled(!ArticleListModelKt.hasUsableUrl(url: article.url))
            Button(L("article_open_in_browser")) {
                openInBrowser(article.url)
            }
            .disabled(!ArticleListModelKt.hasUsableUrl(url: article.url))
        }
    }

    private func selectForContextMenu(_ article: ArticleListRow) {
        if home.selectedArticle?.id != article.id {
            home.viewModel.selectArticle(article: article)
        }
    }

    /// Falls back to the plain title when a search-marked title is blank — matches Compose's own
    /// `markedToAnnotatedString(it.ifBlank { article.title })` (`ArticleListPane.kt`).
    private func titleAttributedString(_ article: ArticleListRow) -> AttributedString {
        if let marked = titleMarks[article.id], !marked.isEmpty {
            return highlighted(marked)
        }
        return AttributedString(article.title)
    }

    private func highlighted(_ marked: String) -> AttributedString {
        var result = AttributedString()
        var remaining = marked[...]
        var highlighting = false
        while let markerIndex = remaining.firstIndex(where: { $0 == markStart || $0 == markEnd }) {
            let plain = remaining[..<markerIndex]
            if !plain.isEmpty {
                var piece = AttributedString(String(plain))
                if highlighting { piece.backgroundColor = .yellow.opacity(0.4) }
                result += piece
            }
            let marker = remaining[markerIndex]
            highlighting = (marker == markStart)
            remaining = remaining[remaining.index(after: markerIndex)...]
        }
        if !remaining.isEmpty {
            var piece = AttributedString(String(remaining))
            if highlighting { piece.backgroundColor = .yellow.opacity(0.4) }
            result += piece
        }
        return result
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

private extension Feeds {
    /// `custom_title`, falling back to `title` — named to avoid clashing with any bridged Kotlin
    /// extension of a similar name reaching Swift under a different signature.
    func keryxDisplayTitle() -> String? {
        custom_title ?? title
    }
}
