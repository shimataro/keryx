/// Decides when the iOS article list goes back to the top after a filter switch
/// (`ArticleListView`).
///
/// The switch is not acted on at once: `HomeObservable.filter` and the rows arrive on separate flows,
/// so right after the switch the list can still hold the previous filter's rows. A scroll aimed at
/// one of them would land on the wrong list (and, as a `List`'s `scrollTo` did, past the end of the new
/// rows when they landed in that same update, crashing `UICollectionView`). So the switch is
/// remembered, and carried out the first time the rows on screen belong to the new filter — once,
/// so a later refresh landing in the list never scrolls it.
struct ArticleListTopReset {
    /// What the list shows, as far as this decision needs it.
    struct Input: Equatable {
        /// The selected filter (`ArticleListView.filterKey`).
        var filterKey: String
        /// Whether the rows on screen belong to `filterKey`'s filter
        /// (`HomeObservable.articleRowsMatchFilter` / `searchRowsMatchFilter`).
        var rowsMatchFilter: Bool
        /// The first row on screen.
        var firstRowId: String?
    }

    /// nil until the first update: the filter the list is created under is not a switch — the first
    /// composition keeps its position (a restored selection is brought into view instead).
    private var lastFilterKey: String?
    private var pending = false

    /// - Returns: The row to scroll to the top now, or nil to leave the scroll position alone.
    mutating func update(_ input: Input) -> String? {
        if let lastFilterKey, lastFilterKey != input.filterKey { pending = true }
        lastFilterKey = input.filterKey
        guard pending, input.rowsMatchFilter else { return nil }
        pending = false
        return input.firstRowId
    }
}
