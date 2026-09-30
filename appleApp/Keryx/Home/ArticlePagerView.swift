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

    var body: some View {
        let selected = home.selectedArticle
        let pages = pagerPages(selected: selected)
        TabView(selection: selectionBinding(pages: pages, selectedId: selected?.id)) {
            ForEach(pages.rows, id: \.id) { page in
                let isSelected = page.id == selected?.id
                // `readerContents`' own rule, resolved per page: the selection's fully-loaded row
                // wins over the held copy, which is what a page shows once swiped away from.
                let cached = home.articleContents[page.id]
                ReaderPageView(
                    row: isSelected ? selected?.toReaderRow() : cached,
                    revision: isSelected ? selected.map(ObjectIdentifier.init) : cached.map(ObjectIdentifier.init),
                    preferences: preferences
                )
                .tag(Optional(page.id))
                .onAppear {
                    if !isSelected && cached == nil { home.viewModel.requestArticleContent(id: page.id) }
                }
            }
        }
        .tabViewStyle(.page(indexDisplayMode: .never))
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
    /// `pagerArticles`, is decided here from `pagerIndexById`, sparing every body evaluation a
    /// round trip of the whole list through the Kotlin bridge; only the fallback asks `readerPages`,
    /// for the one-page list holding just the selection while it is not in the list (yet, or any
    /// more), so the pager never clamps onto — and selects — another article.
    private func pagerPages(selected: Articles?) -> ReaderPagerPages {
        guard let selected, home.pagerIndexById[selected.id] == nil else {
            return ReaderPagerPages(rows: home.pagerArticles, indexById: home.pagerIndexById)
        }
        let rows = ReaderPagingKt.readerPages(pages: home.pagerArticles, selected: selected)
        return ReaderPagerPages(rows: rows)
    }

    /// The pager's page follows the selection; a settled swipe is the one thing that moves the
    /// selection the other way (`settledPageSelects`).
    private func selectionBinding(pages: ReaderPagerPages, selectedId: String?) -> Binding<String?> {
        Binding(
            get: { selectedId },
            set: { settledId in
                guard ReaderPagingKt.settledPageSelects(settledId: settledId, selectedId: selectedId, isSwipeTarget: true),
                      let settledId, let index = pages.indexById[settledId] else { return }
                home.viewModel.selectArticle(article: pages.rows[index])
            }
        )
    }

    private func neighbourIds(of id: String?, in pages: ReaderPagerPages) -> [String] {
        guard let id, let index = pages.indexById[id] else { return [] }
        return [index - 1, index + 1].filter(pages.rows.indices.contains).map { pages.rows[$0].id }
    }
}

/// The pager's rows and each one's position.
private struct ReaderPagerPages {
    let rows: [ArticleListRow]
    let indexById: [String: Int]

    init(rows: [ArticleListRow], indexById: [String: Int]) {
        self.rows = rows
        self.indexById = indexById
    }

    /// Indexes `rows` itself — only ever the short fallback list.
    init(rows: [ArticleListRow]) {
        var index = [String: Int](minimumCapacity: rows.count)
        for (i, row) in rows.enumerated() { index[row.id] = i }
        self.init(rows: rows, indexById: index)
    }
}
#endif
