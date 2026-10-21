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
        // The selection itself, so a settled swipe is never pulled back to the previous page while
        // the new one's body is still loading; that page keeps showing its held copy (or just the
        // background) until then.
        let selectedId = home.selectedArticleId
        let loaded = home.loadedArticle.flatMap { $0.id == selectedId ? $0 : nil }
        let pages = ReaderPagerPages.resolve(pagerRows: pager.rows, index: pager.index, selected: home.selectedArticleRow)
        TabView(selection: selectionBinding(pages: pages, selectedId: selectedId)) {
            // Plain Swift ids, so a body evaluation never reads a row through the Kotlin bridge.
            ForEach(pages.ids, id: \.self) { id in
                let isSelected = id == selectedId
                let cached = home.articleContents[id]
                let source = ReaderPageSource.resolve(isSelected: isSelected, loaded: loaded, cached: cached)
                ReaderPageView(
                    row: source.row,
                    revision: source.revision,
                    // Each page names its own feed: a neighbour in All Feeds may belong to another one.
                    feedName: source.feedId.flatMap { home.feedsById[$0]?.displayTitle() },
                    feedFaviconUrl: source.feedId.flatMap { home.feedsById[$0]?.favicon_url },
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
        .task(id: selectedId) {
            for id in pages.neighbourIds(of: selectedId) where home.articleContents[id] == nil {
                home.viewModel.requestArticleContent(id: id)
            }
        }
        // A long reading session's bodies must not stay resident once the reader is gone.
        .onDisappear { home.viewModel.clearArticleContents() }
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
}
#endif
