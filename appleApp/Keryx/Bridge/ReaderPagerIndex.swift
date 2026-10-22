import KeryxShared

/// The reader pager's page ids, in list order, plus each one's position — resolved once per
/// `HomeViewModel.pagerArticles` emission (`ReaderPagerObservable`), so the pager's body iterates
/// plain Swift strings and looks pages up without a scan or a trip through the Kotlin bridge.
///
/// Platform-independent (only the iOS pager uses it) so the macOS test run covers it too.
struct ReaderPagerIndex: Equatable, Sendable {
    let ids: [String]
    let indexById: [String: Int]

    static let empty = ReaderPagerIndex(ids: [])

    /// Indexes `ids`. A repeated id keeps its last position, as the pager has always resolved one.
    init(ids: [String]) {
        var index = [String: Int](minimumCapacity: ids.count)
        for (i, id) in ids.enumerated() { index[id] = i }
        self.ids = ids
        self.indexById = index
    }

    init(rows: [ArticleListRow]) {
        self.init(ids: rows.map(\.id))
    }

    /// `indexById` is derived from `ids` alone, so comparing `ids` is enough.
    static func == (lhs: ReaderPagerIndex, rhs: ReaderPagerIndex) -> Bool {
        lhs.ids == rhs.ids
    }

    /// The index of `rows`, returning `previous` itself when its ids are unchanged (an emission
    /// that only changed a row's read/star state) so the dictionary is not rebuilt for nothing.
    static func build(_ rows: [ArticleListRow], reusing previous: ReaderPagerIndex) -> ReaderPagerIndex {
        let ids = rows.map(\.id)
        return ids == previous.ids ? previous : ReaderPagerIndex(ids: ids)
    }

    /// `build`, off the main actor: reading every row's id through the Kotlin bridge would
    /// otherwise hold the main thread for every article write while the reader is on screen.
    @concurrent
    static func buildInBackground(_ rows: [ArticleListRow], reusing previous: ReaderPagerIndex) async -> ReaderPagerIndex {
        build(rows, reusing: previous)
    }
}

/// The pager's rows, their ids and each one's position — always from the same emission (or the
/// same fallback list), so an index from `indexById` addresses `rows` and `ids` alike.
struct ReaderPagerPages {
    let rows: [ArticleListRow]
    let index: ReaderPagerIndex

    var ids: [String] { index.ids }
    var indexById: [String: Int] { index.indexById }

    /// The pages the pager shows — `readerPages`' rule. The common case, a selection that is in the
    /// list (`index`), is decided here from the index, sparing every body evaluation a round trip of
    /// the whole list through the Kotlin bridge; only the fallback asks `readerPages`, for the
    /// one-page list holding just the selection while it is not in the list (yet — before the first
    /// emission — or any more), so the pager never clamps onto — and selects — another article.
    static func resolve(pagerRows: [ArticleListRow], index: ReaderPagerIndex, selected: ArticleListRow?) -> ReaderPagerPages {
        guard let selected, index.indexById[selected.id] == nil else {
            return ReaderPagerPages(rows: pagerRows, index: index)
        }
        let rows = ReaderPagingKt.readerPages(pages: pagerRows, selected: selected)
        return ReaderPagerPages(rows: rows, index: ReaderPagerIndex(rows: rows))
    }

    /// The ids of the pages either side of `id`, whose bodies are hydrated ahead of a swipe; none
    /// when `id` is nil or is not one of the pages.
    func neighbourIds(of id: String?) -> [String] {
        guard let id, let index = indexById[id] else { return [] }
        return [index - 1, index + 1].filter(ids.indices.contains).map { ids[$0] }
    }
}

/// What one pager page shows, resolved from where its content may come from — `readerContents`' own
/// rule, per page: the selection's fully-loaded row wins over the held copy, which is what a page
/// shows once swiped away from.
struct ReaderPageSource {
    let row: ArticleReaderRow?
    /// Identifies the instance `row` came from, so the page's document is rebuilt only when a new one
    /// is actually published (`ReaderPageView.revision`).
    let revision: ObjectIdentifier?
    /// The feed the page's article belongs to — each page names its own feed, since a neighbour in
    /// All Feeds may belong to another one.
    let feedId: String?

    /// - Parameters:
    ///   - isSelected: Whether this page is the selection.
    ///   - loaded: The selection's fully loaded article, or nil; only ever used by the selected page.
    ///   - cached: The held copy of this page's article, or nil if none has been hydrated.
    static func resolve(isSelected: Bool, loaded: Articles?, cached: ArticleReaderRow?) -> ReaderPageSource {
        let own = isSelected ? loaded : nil
        return ReaderPageSource(
            row: own?.toReaderRow() ?? cached,
            revision: own.map(ObjectIdentifier.init) ?? cached.map(ObjectIdentifier.init),
            feedId: own?.feed_id ?? cached?.feed_id
        )
    }
}
