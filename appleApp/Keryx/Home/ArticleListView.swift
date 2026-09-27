import KeryxShared
import SwiftUI

private let markStart: Character = "\u{0002}"
private let markEnd: Character = "\u{0003}"

/// The center pane: toolbar (unread-only, hide-read, sort, mark-all-read), the article rows
/// themselves (search-highlighted when a query is active), the "new articles" pill, and the four
/// empty states — see `external-spec.md` §7's article-list bullets and the M2 research notes.
///
/// The notification-center bell that lives in this pane's header on every platform (per
/// `error-design.md`'s "Notification Center") is added in M5, once `NotificationAlerts` is wired up.
struct ArticleListView: View {
    let home: HomeObservable
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    @State private var appearedIds: Set<String> = []

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
                    .onChange(of: filterKey, initial: false) { _, _ in
                        scrollToFreshEnd(proxy)
                    }
                    .overlay(alignment: home.newestFirst ? .top : .bottom) {
                        if home.newArticleCount > 0 {
                            newArticlesPill(proxy: proxy)
                        }
                    }
            }
        }
        .focused(focusedPane, equals: .articleList)
    }

    // MARK: - Toolbar

    private var toolbar: some View {
        HStack(spacing: 12) {
            Button {
                home.viewModel.setUnreadOnly(value: !home.unreadOnly)
            } label: {
                Image(systemName: home.unreadOnly ? "circle.inset.filled" : "circle")
            }
            .help("Unread Only")

            Button {
                home.viewModel.hideRead()
            } label: {
                Image(systemName: "eye.slash")
            }
            .disabled(!home.canHideRead)
            .help("Hide Read")

            Button {
                home.viewModel.toggleSort()
            } label: {
                Image(systemName: home.newestFirst ? "arrow.down" : "arrow.up")
            }
            .help(home.newestFirst ? "Newest First" : "Oldest First")

            Spacer()

            Button {
                home.viewModel.markAllRead()
            } label: {
                Image(systemName: "checkmark.circle")
            }
            .help("Mark All Read")
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    // MARK: - Content / empty states

    @ViewBuilder
    private var content: some View {
        if home.feeds.isEmpty {
            ContentUnavailableView(
                "No Feeds Yet",
                systemImage: "tray",
                description: Text("Add a feed to start reading.")
            )
        } else if home.searchActive && home.searchQuery.trimmingCharacters(in: .whitespacesAndNewlines).count < Int(ConstantsKt.SEARCH_MIN_TERM_LENGTH) {
            ContentUnavailableView(
                "Keep Typing",
                systemImage: "magnifyingglass",
                description: Text("Enter at least \(ConstantsKt.SEARCH_MIN_TERM_LENGTH) characters to search.")
            )
        } else if home.searchActive && !home.searching && home.searchResults.isEmpty {
            ContentUnavailableView.search
        } else if displayedRows.isEmpty {
            ContentUnavailableView(
                "No Articles",
                systemImage: "doc.text",
                description: Text("Nothing to show here yet.")
            )
        } else {
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
    }

    private func reportVisible() {
        let ordered = displayedRows.filter { appearedIds.contains($0.id) }.map(\.id)
        home.viewModel.markArticlesSeen(ids: ordered)
    }

    // MARK: - Row

    @ViewBuilder
    private func row(_ article: ArticleListRow) -> some View {
        Button {
            home.viewModel.selectArticle(article: article)
        } label: {
            HStack(alignment: .top, spacing: 8) {
                FaviconView(url: feedFor(article)?.favicon_url, letter: article.title.first)
                    .frame(width: 20, height: 20)
                VStack(alignment: .leading, spacing: 2) {
                    Text(titleMarks[article.id].map(highlighted) ?? AttributedString(article.title))
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
            Button(article.is_starred == 1 ? "Unstar" : "Star") {
                home.viewModel.toggleStar(article: article)
            }
            Button(article.is_read == 1 ? "Mark Unread" : "Mark Read") {
                home.viewModel.toggleRead(article: article)
            }
            if ArticleListModelKt.hasUsableUrl(url: article.url) {
                Button("Copy URL") {
                    copyToPasteboard(article.url)
                }
                Button("Open in Browser") {
                    openInBrowser(article.url)
                }
            }
        }
    }

    private func feedFor(_ article: ArticleListRow) -> Feeds? {
        home.feeds.first { $0.id == article.feed_id }
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
            Text("\(home.newArticleCount) new articles")
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
        withAnimation {
            proxy.scrollTo(target, anchor: home.newestFirst ? .top : .bottom)
        }
    }
}

private extension Feeds {
    /// `custom_title`, falling back to `title` — named to avoid clashing with any bridged Kotlin
    /// extension of a similar name reaching Swift under a different signature.
    func keryxDisplayTitle() -> String? {
        custom_title ?? title
    }
}
