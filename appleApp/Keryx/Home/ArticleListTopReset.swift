/// Decides when the iOS article list goes back to the top after a filter switch
/// (`ArticleListView`).
///
/// The switch is not acted on at once: `HomeObservable.filter` and the rows arrive on separate flows,
/// so right after the switch the list can still hold the previous filter's rows. A scroll aimed at one
/// of them is resolved against the rows the list has when SwiftUI carries it out; when the new rows
/// land in that same update and the target is past their end (the previous list's first row, into a
/// new list with no rows), `UICollectionView` raises an exception and the app crashes. So the switch is
/// remembered, and carried out the first time the rows on screen belong to the new filter — once,
/// so a later refresh landing in the list never scrolls it.
struct ArticleListTopReset {
    /// What the list shows, as far as this decision needs it.
    struct Input: Equatable {
        /// The generation of the user's filter selection (`HomeObservable.filterGeneration`): it moves on
        /// a switch to another filter, not when a merge re-keys the selected tag or folder, so a re-key
        /// is never taken for a switch.
        var selectionKey: Int64
        /// Whether the rows on screen belong to the selected filter
        /// (`HomeObservable.articleRowsMatchFilter` / `searchRowsMatchFilter`).
        var rowsMatchFilter: Bool
        /// The first row on screen.
        var firstRowId: String?
    }

    /// nil until the first update: the filter the list is created under is not a switch — the first
    /// composition keeps its position (a restored selection is brought into view instead).
    private var lastSelectionKey: Int64?
    private var pending = false

    /// - Returns: The row to scroll to the top now, or nil to leave the scroll position alone.
    mutating func update(_ input: Input) -> String? {
        if let lastSelectionKey, lastSelectionKey != input.selectionKey { pending = true }
        lastSelectionKey = input.selectionKey
        guard pending, input.rowsMatchFilter else { return nil }
        pending = false
        return input.firstRowId
    }
}
