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
        // Falls back to a one-page list holding just the selection while it is not in the list
        // (yet, or any more), so the pager never clamps onto — and selects — another article.
        let pages = ReaderPagingKt.readerPages(pages: home.pagerArticles, selected: selected)
        TabView(selection: selectionBinding(pages: pages, selectedId: selected?.id)) {
            ForEach(pages, id: \.id) { page in
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

    /// The pager's page follows the selection; a settled swipe is the one thing that moves the
    /// selection the other way (`settledPageSelects`).
    private func selectionBinding(pages: [ArticleListRow], selectedId: String?) -> Binding<String?> {
        Binding(
            get: { selectedId },
            set: { settledId in
                guard ReaderPagingKt.settledPageSelects(settledId: settledId, selectedId: selectedId, isSwipeTarget: true),
                      let row = pages.first(where: { $0.id == settledId }) else { return }
                home.viewModel.selectArticle(article: row)
            }
        )
    }

    private func neighbourIds(of id: String?, in pages: [ArticleListRow]) -> [String] {
        guard let id, let index = pages.firstIndex(where: { $0.id == id }) else { return [] }
        return [index - 1, index + 1].filter(pages.indices.contains).map { pages[$0].id }
    }
}
#endif
