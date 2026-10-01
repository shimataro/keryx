import KeryxShared
import Observation

#if os(iOS)
/// Mirrors `HomeViewModel.pagerArticles` — the rows the iOS reader's swipe pager pages through (the
/// search results while a search is active, so a swipe steps exactly as J/K does) — for
/// `ArticlePagerView`, which owns one and starts `startObserving()` from its own `.task`.
///
/// Deliberately not part of `HomeObservable`, which observes for the app's whole life: that flow is
/// `WhileSubscribed`, so it only runs — re-comparing the article list on every article write — while
/// something collects it, and only the pager on screen needs it.
@MainActor
@Observable
final class ReaderPagerObservable: ObservableAssignment {
    let viewModel: HomeViewModel

    /// The latest emission's rows, applied together with the `index` built from them so the two
    /// never disagree. Not observed: the pager's body depends only on the ids and their positions
    /// (`index`); a row itself is read only when a settled swipe selects it. Always assigned, since
    /// an emission with the same ids still carries each row's current read/star state.
    @ObservationIgnored private(set) var rows: [ArticleListRow] = []
    /// `rows`' ids and positions, assigned only when the ids change.
    private(set) var index = ReaderPagerIndex.empty

    /// The in-flight background build. A newer emission cancels the older one, and a result is
    /// applied only if it is still the latest emission's (`generation`), so an older emission's
    /// rows can never land over a newer one's.
    @ObservationIgnored private var build: (task: Task<Void, Never>, generation: Int)?
    @ObservationIgnored private var generation = 0

    init(viewModel: HomeViewModel) {
        self.viewModel = viewModel
    }

    /// Collects `pagerArticles` until the calling task is cancelled (the pager leaving the screen),
    /// which is what lets the flow stop again.
    func startObserving() async {
        for await v in viewModel.pagerArticles {
            rebuild(v)
        }
        build?.task.cancel()
        build = nil
    }

    private func rebuild(_ newRows: [ArticleListRow]) {
        build?.task.cancel()
        generation += 1
        let generation = generation
        let previous = index
        let task = Task { [weak self] in
            let built = await ReaderPagerIndex.buildInBackground(newRows, reusing: previous)
            guard let self, !Task.isCancelled, build?.generation == generation else { return }
            build = nil
            rows = newRows
            assignIfChanged(\.index, built)
        }
        build = (task, generation)
    }
}
#endif
