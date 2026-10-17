/// What the iOS toolbar's status area says while background work runs (`RefreshStatusLabel`): a
/// feed refresh or a cloud sync, whichever route started it — the pull, ⌘R, startup maintenance, a
/// background refresh or an automatic sync. Nothing while idle, so the area stays empty and the
/// bottom toolbar disappears (`external-spec.md` §9). Kept free of SwiftUI so the standalone
/// `KeryxTests` bundle can compile it directly.
enum RefreshStatus {
    case refreshing
    case syncing

    /// The status for `ActivitySnapshot.refreshIndicatorShown` / `.syncing` — the same two flags the
    /// toolbar's own Refresh / Sync buttons spin on, which never both hold for the same phase. A
    /// refresh wins should both ever be true.
    static func from(refreshIndicatorShown: Bool, syncing: Bool) -> RefreshStatus? {
        if refreshIndicatorShown { return .refreshing }
        if syncing { return .syncing }
        return nil
    }
}
