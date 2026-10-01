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
