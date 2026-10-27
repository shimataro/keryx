import KeryxShared
import SwiftUI

#if os(iOS)
/// The iOS reader's swipe pager: a horizontal swipe moves to the next/previous article in the
/// order the list itself shows (`HomeViewModel.pagerArticles`, the same order J/K steps through) —
/// see `external-spec.md` §9. The page turn itself is `ReaderPageController`, a `UIPageViewController`
/// (see its own doc for why not a page-style `TabView`).
///
/// Only a page the swipe actually came to rest on is selected — and so marked read. A page shown
/// beside it is only hydrated (`requestArticleContent`), never selected, and a selection made
/// elsewhere (the article list, J/K) just moves the pager to it.
struct ArticlePagerView: View {
    let home: HomeObservable
    let preferences: PreferencesObservable
    /// See `ReaderPageController.restoredScroll`.
    let restoredScroll: ReaderScrollPosition?
    /// See `ReaderPageController.onScroll`.
    let onScroll: (String, Double) -> Void

    /// Collects `pagerArticles` only while this pager is on screen (`.task` below), so the flow's
    /// `WhileSubscribed` sharing stops it whenever the reader is gone.
    @State private var pager: ReaderPagerObservable

    init(
        home: HomeObservable,
        preferences: PreferencesObservable,
        restoredScroll: ReaderScrollPosition?,
        onScroll: @escaping (String, Double) -> Void
    ) {
        self.home = home
        self.preferences = preferences
        self.restoredScroll = restoredScroll
        self.onScroll = onScroll
        self._pager = State(initialValue: ReaderPagerObservable(viewModel: home.viewModel))
    }

    var body: some View {
        // The selection itself, so a settled swipe is never pulled back to the previous page while
        // the new one's body is still loading; that page keeps showing its held copy (or just the
        // background) until then.
        let selectedId = home.selectedArticleId
        let loaded = home.loadedArticle.flatMap { $0.id == selectedId ? $0 : nil }
        let pages = ReaderPagerPages.resolve(pagerRows: pager.rows, index: pager.index, selected: home.selectedArticleRow)
        let input = { (id: String) in pageInput(id: id, selectedId: selectedId, loaded: loaded) }
        // Resolved here, in the body, so a change to any page kept on hand updates the pager.
        let inputs = Dictionary(uniqueKeysWithValues: pages.retainedIds(around: selectedId).map { ($0, input($0)) })
        ReaderPageController(
            pages: pages,
            selectedId: selectedId,
            inputs: inputs,
            input: input,
            preferences: preferences,
            restoredScroll: restoredScroll,
            onScroll: onScroll,
            onSettle: { settle(on: $0, pages: pages, selectedId: selectedId) }
        )
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

    /// What page `id` shows: `ReaderPageSource`'s rule, with each page naming its own feed (a
    /// neighbour in All Feeds may belong to another one). A neighbour's held copy is requested by the
    /// `.task(id: selectedId)` above; its input follows once the copy lands.
    private func pageInput(id: String, selectedId: String?, loaded: Articles?) -> ReaderPageInput {
        let isSelected = id == selectedId
        let cached = home.articleContents[id]
        let source = ReaderPageSource.resolve(isSelected: isSelected, loaded: loaded, cached: cached)
        return ReaderPageInput(
            row: source.row,
            revision: source.revision,
            feedName: source.feedId.flatMap { home.feedsById[$0]?.displayTitle() },
            feedFaviconUrl: source.feedId.flatMap { home.feedsById[$0]?.favicon_url }
        )
    }

    /// The pager's page follows the selection; a settled swipe is the one thing that moves the
    /// selection the other way (`settledPageSelects`). Returns whether it did.
    private func settle(on settledId: String, pages: ReaderPagerPages, selectedId: String?) -> Bool {
        guard ReaderPagingKt.settledPageSelects(settledId: settledId, selectedId: selectedId, isSwipeTarget: true),
              let index = pages.indexById[settledId] else { return false }
        home.selectArticle(pages.rows[index])
        return true
    }
}
#endif
