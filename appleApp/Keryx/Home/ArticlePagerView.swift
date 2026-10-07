import KeryxShared
import SwiftUI

#if os(iOS)
/// The iOS reader's swipe pager: a horizontal swipe moves to the next/previous article in the
/// order the list itself shows (`HomeViewModel.pagerArticles`, the same order J/K steps through) —
/// see `external-spec.md` §9. The page turn itself (following the finger, settling, the rubber band
/// past either end, telling a horizontal swipe from the WebView's own vertical scroll) is UIKit's
/// paging scroll view, through a page-style `TabView`.
///
/// Only a page the swipe actually came to rest on is selected — and so marked read. A page shown
/// beside it is only hydrated (`requestArticleContent`), never selected, and a selection made
/// elsewhere (the article list, J/K) just moves the pager to it.
struct ArticlePagerView: View {
    let home: HomeObservable
    let preferences: PreferencesObservable

    /// Collects `pagerArticles` only while this pager is on screen (`.task` below), so the flow's
    /// `WhileSubscribed` sharing stops it whenever the reader is gone.
    @State private var pager: ReaderPagerObservable

    init(home: HomeObservable, preferences: PreferencesObservable) {
        self.home = home
        self.preferences = preferences
        self._pager = State(initialValue: ReaderPagerObservable(viewModel: home.viewModel))
    }

    var body: some View {
        let selected = home.selectedArticle
        let pages = pagerPages(selected: selected)
        TabView(selection: selectionBinding(pages: pages, selectedId: selected?.id)) {
            // Plain Swift ids, so a body evaluation never reads a row through the Kotlin bridge.
            ForEach(pages.ids, id: \.self) { id in
                let isSelected = id == selected?.id
                // `readerContents`' own rule, resolved per page: the selection's fully-loaded row
                // wins over the held copy, which is what a page shows once swiped away from.
                let cached = home.articleContents[id]
                ReaderPageView(
                    row: isSelected ? selected?.toReaderRow() : cached,
                    revision: isSelected ? selected.map(ObjectIdentifier.init) : cached.map(ObjectIdentifier.init),
                    // Each page names its own feed: a neighbour in All Feeds may belong to another one.
                    feedName: (isSelected ? selected?.feed_id : cached?.feed_id).flatMap { home.feedsById[$0]?.displayTitle() },
                    feedFaviconUrl: (isSelected ? selected?.feed_id : cached?.feed_id).flatMap { home.feedsById[$0]?.favicon_url },
                    preferences: preferences
                )
                .tag(Optional(id))
                .onAppear {
                    if !isSelected && cached == nil { home.viewModel.requestArticleContent(id: id) }
                }
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
        .task { await pager.startObserving() }
        // Hydrates both neighbours as soon as the selection moves, so the page a swipe uncovers is
        // already there rather than loading as it slides in.
        .task(id: selected?.id) {
            for id in neighbourIds(of: selected?.id, in: pages) where home.articleContents[id] == nil {
                home.viewModel.requestArticleContent(id: id)
            }
        }
        // A long reading session's bodies must not stay resident once the reader is gone.
        .onDisappear { home.viewModel.clearArticleContents() }
    }

    /// The pages the pager shows — `readerPages`' rule. The common case, a selection that is in
    /// `pagerArticles`, is decided here from `pager.index`, sparing every body evaluation a round
    /// trip of the whole list through the Kotlin bridge; only the fallback asks `readerPages`, for
    /// the one-page list holding just the selection while it is not in the list (yet — before the
    /// first emission — or any more), so the pager never clamps onto — and selects — another article.
    private func pagerPages(selected: Articles?) -> ReaderPagerPages {
        guard let selected, pager.index.indexById[selected.id] == nil else {
            return ReaderPagerPages(rows: pager.rows, index: pager.index)
        }
        let rows = ReaderPagingKt.readerPages(pages: pager.rows, selected: selected)
        return ReaderPagerPages(rows: rows, index: ReaderPagerIndex(rows: rows))
    }

    /// The pager's page follows the selection; a settled swipe is the one thing that moves the
    /// selection the other way (`settledPageSelects`).
    private func selectionBinding(pages: ReaderPagerPages, selectedId: String?) -> Binding<String?> {
        Binding(
            get: { selectedId },
            set: { settledId in
                guard ReaderPagingKt.settledPageSelects(settledId: settledId, selectedId: selectedId, isSwipeTarget: true),
                      let settledId, let index = pages.indexById[settledId] else { return }
                home.selectArticle(pages.rows[index])
            }
        )
    }

    private func neighbourIds(of id: String?, in pages: ReaderPagerPages) -> [String] {
        guard let id, let index = pages.indexById[id] else { return [] }
        return [index - 1, index + 1].filter(pages.ids.indices.contains).map { pages.ids[$0] }
    }
}

/// The pager's rows, their ids and each one's position — always from the same emission (or the
/// same fallback list), so an index from `indexById` addresses `rows` and `ids` alike.
private struct ReaderPagerPages {
    let rows: [ArticleListRow]
    let index: ReaderPagerIndex

    var ids: [String] { index.ids }
    var indexById: [String: Int] { index.indexById }
}
#endif
