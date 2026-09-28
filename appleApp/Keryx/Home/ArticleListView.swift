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
    @Bindable var dialogs: SidebarDialogState
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    @State private var appearedIds: Set<String> = []
    /// Whether the new-articles pill is actually shown, debounced against `home.newArticleCount`
    /// itself — see `pillShowDelayTask`'s own KDoc for why.
    @State private var pillShown = false

    private var displayedRows: [ArticleListRow] {
        home.searchActive ? home.searchResults.map(\.article) : home.articles
    }

    private var titleMarks: [String: String] {
        guard home.searchActive else { return [:] }
        return Dictionary(uniqueKeysWithValues: home.searchResults.map { ($0.article.id, $0.titleMarked) })
    }

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
            toolbar
            Divider()
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
            }
        }
        .focused(focusedPane, equals: .articleList)
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

    private var toolbar: some View {
        HStack(spacing: 12) {
            Button {
                home.viewModel.setUnreadOnly(value: !home.unreadOnly)
            } label: {
                Image(systemName: home.unreadOnly ? "circle.inset.filled" : "circle")
            }
            .help(L("home_unread_only"))

            Button {
                home.viewModel.hideRead()
            } label: {
                Image(systemName: "eye.slash")
            }
            .disabled(!home.canHideRead)
            .help(L("home_hide_read"))

            Button {
                home.viewModel.toggleSort()
            } label: {
                Image(systemName: home.newestFirst ? "arrow.down" : "arrow.up")
            }
            .disabled(home.searchActive)
            .help(L(home.searchActive ? "home_sort_disabled_search" : (home.newestFirst ? "home_sort_oldest" : "home_sort_newest")))

            Spacer()

            Button {
                home.viewModel.markAllRead()
            } label: {
                Image(systemName: "checkmark.circle")
            }
            .help(L("home_mark_all_read"))

            NotificationBell(home: home, notifications: notifications)
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
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
                .onAppear {
                    appearedIds.insert(article.id)
                    reportVisible()
                }
                .onDisappear {
                    appearedIds.remove(article.id)
                    reportVisible()
                }
        }
        .listStyle(.plain)
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

    @ViewBuilder
    private func row(_ article: ArticleListRow) -> some View {
        Button {
            focusedPane.wrappedValue = .articleList
            home.viewModel.selectArticle(article: article)
        } label: {
            HStack(alignment: .top, spacing: 8) {
                FaviconView(url: feedFor(article)?.favicon_url, letter: article.title.first)
                    .frame(width: 20, height: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text(article.title.isEmpty ? AttributedString(L("article_no_title")) : titleAttributedString(article))
                        .font(article.is_read == 1 ? .body : .body.bold())
                        .lineLimit(2)
                    HStack(spacing: 6) {
                        if let feedTitle = feedFor(article)?.keryxDisplayTitle() {
                            Text(feedTitle)
                        }
                        Text(formattedDate(article.published_at))
                    }
                    .font(.caption)
                    .foregroundStyle(.secondary)
                }
                Spacer()
                VStack {
                    if article.is_starred == 1 {
                        Image(systemName: "star.fill").foregroundStyle(.yellow)
                    }
                    if article.is_read == 0 {
                        Circle().fill(Color.accentColor).frame(width: 8, height: 8)
                    }
                }
            }
            .padding(.vertical, 4)
            .background(home.selectedArticle?.id == article.id ? Color.accentColor.opacity(0.15) : Color.clear)
        }
        .buttonStyle(.plain)
        .contextMenu {
            // Opening the menu selects the row first, matching Compose's own `onOpen = onClick`
            // (`ArticleRowComponents.kt`) — a `let` inside a `@ViewBuilder` menu-content closure
            // runs as a plain side effect, not a view, and this closure is rebuilt each time the
            // menu is requested.
            let _ = selectForContextMenu(article)
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

    private func feedFor(_ article: ArticleListRow) -> Feeds? {
        home.feeds.first { $0.id == article.feed_id }
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
        var highlighting = false
        for ch in marked {
            if ch == markStart { highlighting = true; continue }
            if ch == markEnd { highlighting = false; continue }
            var piece = AttributedString(String(ch))
            if highlighting {
                piece.backgroundColor = .yellow.opacity(0.4)
            }
            result += piece
        }
        return result
    }

    private func formattedDate(_ epochMillis: KotlinLong?) -> String {
        guard let epochMillis else { return "" }
        let date = Date(timeIntervalSince1970: Double(epochMillis.int64Value) / 1000)
        return date.formatted(date: .numeric, time: .shortened)
    }

    // MARK: - New articles pill

    @ViewBuilder
    private func newArticlesPill(proxy: ScrollViewProxy) -> some View {
        Button {
            home.viewModel.markAllArticlesSeen()
            scrollToFreshEnd(proxy)
        } label: {
            Text(LF("apple_new_articles_pill", Int64(home.newArticleCount)))
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
