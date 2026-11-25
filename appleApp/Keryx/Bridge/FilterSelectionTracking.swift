import KeryxShared

/// What `HomeObservable.assignFilterSelection` decides for one `FilterSelection` delivery.
enum FilterSelectionChange: Equatable {
    /// The filter did not change.
    case unchanged
    /// The filter changed under the same selection generation: a merge re-keyed the selected tag or
    /// folder onto another id. The same list under a new id — nothing the user did.
    case rekeyed
    /// The user selected another filter: the generation moved with it.
    case switched

    /// Whether the article list's pull in flight belongs to a list that is no longer on screen.
    var abandonsPull: Bool { self == .switched }
}

/// Tracks the generation of the user's filter selection (`FilterSelection.generation`) and classifies
/// each delivery against it. Pure, so the decision is tested without a view model.
struct FilterSelectionTracker: Equatable {
    private(set) var generation: Int64 = 0

    /// Records `generation` and classifies the delivery. A new generation is recorded even when the
    /// filter is equal, so the next delivery is compared against the latest one.
    mutating func apply(generation newGeneration: Int64, filterChanged: Bool) -> FilterSelectionChange {
        let moved = newGeneration != generation
        generation = newGeneration
        guard filterChanged else { return .unchanged }
        return moved ? .switched : .rekeyed
    }
}

/// Whether a pull-to-refresh is still pending for a list, judged at each delivery of
/// `HomeViewModel.pullRefreshingFilters`.
enum PullMembership {
    /// `true` while `current` is among `pending`: its pull has not finished. Compared with Kotlin
    /// `equals` (see `kotlinValuesEqual`).
    static func isPending<Pending: Sequence>(_ current: AnyObject, in pending: Pending) -> Bool {
        pending.contains { kotlinValuesEqual($0 as AnyObject, current) }
    }
}
