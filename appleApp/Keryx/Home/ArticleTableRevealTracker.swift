/// Decides, on each update of the macOS article table, whether the selected row is to be scrolled
/// into view (`ArticleTableView.Coordinator`).
///
/// Two triggers, deliberately:
/// - A selection the user makes is a new `revealRequest` (`HomeViewModel.selectionRevealRequests`).
///   It reveals the row at once.
/// - A selection restored from the previous session is never a request — it arrives with the first
///   rows, as `selectedId` already set. The table has no size yet on its first update, so it is
///   revealed once the table has been laid out.
///
/// Afterwards `selectedId` moving on its own (a selection handed back after a tombstone) is neither,
/// and scrolls nothing.
struct ArticleTableRevealTracker {
    enum Action: Equatable {
        /// Leave the scroll position alone.
        case none
        /// Reveal the selected row now.
        case now
        /// Reveal it once the table has been laid out (its first update runs before it has a size).
        case afterLayout
    }

    private var lastSelectedId: String?
    private var lastRevealRequest: Int
    private var hasUpdated = false

    /// - Parameter revealRequest: The request the table is created under: already acted on (or not a
    ///   request at all), so only a later change is the user selecting.
    init(revealRequest: Int) {
        lastRevealRequest = revealRequest
    }

    mutating func update(selectedId: String?, revealRequest: Int) -> Action {
        var action = Action.none
        if selectedId != lastSelectedId {
            lastSelectedId = selectedId
            if !hasUpdated { action = .afterLayout }
        }
        if revealRequest != lastRevealRequest {
            lastRevealRequest = revealRequest
            if hasUpdated { action = .now }
        }
        hasUpdated = true
        return action
    }
}
