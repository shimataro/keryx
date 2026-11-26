import KeryxShared

/// The tag/folder id re-keys a sync merge has published (`HomeViewModel.idRemap`), as the
/// sidebar needs them: to tell that a row key it holds from before a re-key and the key the row has
/// now name the same row. A key is `feedListRowSelectionKey`'s string (`folder:ID`, `tag:ID`,
/// `feed-in-tag:TAG:FEED`, ...), and only the tag and folder ids in it move.
///
/// A held key is never rewritten, only compared through this map: the map and the lists a merge
/// changes reach the UI on separate flows, in either order, and two ids the map joins are the same
/// row whichever of them the lists currently use.
struct SidebarIdRemap: Equatable {
    /// Old tag id → the id the tag has now.
    let tags: [String: String]
    /// Old folder id → the id the folder has now.
    let folders: [String: String]

    static let empty = SidebarIdRemap(tags: [:], folders: [:])

    init(tags: [String: String], folders: [String: String]) {
        self.tags = tags
        self.folders = folders
    }

    init(_ kotlin: IdRemap) {
        self.init(tags: kotlin.tagIds, folders: kotlin.folderIds)
    }

    /// `key` with its tag or folder id brought up to date; `key` itself when nothing moved.
    func current(_ key: String) -> String {
        for (old, new) in folders where key == "folder:\(old)" { return "folder:\(new)" }
        for (old, new) in tags {
            if key == "tag:\(old)" { return "tag:\(new)" }
            let nested = "feed-in-tag:\(old):"
            if key.hasPrefix(nested) { return "feed-in-tag:\(new):" + key.dropFirst(nested.count) }
        }
        return key
    }

    /// Whether `a` and `b` are the same row, however many re-keys apart their ids were taken.
    func sameRow(_ a: String, _ b: String) -> Bool { current(a) == current(b) }

    /// Whether `keys` has a row that is `key`'s.
    func contains(_ keys: [String], row key: String) -> Bool {
        let wanted = current(key)
        return keys.contains { current($0) == wanted }
    }

    /// The key in `keys` that is `key`'s row, if any.
    func match(in keys: [String], for key: String) -> String? {
        let wanted = current(key)
        return keys.first { current($0) == wanted }
    }

    /// What an in-place rename whose row was missing from `keysAtCatchUp` does once this map — the
    /// one a catch-up from the database published — is in hand.
    ///
    /// The absence is judged on `keysAtCatchUp`, the keys the catch-up was started for: the map was
    /// read after them, so it covers every re-key they show. Keys delivered meanwhile
    /// (`currentKeys`) can show a later re-key the map missed, and judging on them could take the
    /// row for gone; so when they differ nothing is decided here, and their own change (which runs
    /// its own catch-up) decides instead.
    func renameRowDecision(
        key: String,
        keysAtCatchUp: [String],
        currentKeys: [String]
    ) -> RenameRowDecision {
        guard currentKeys == keysAtCatchUp else { return .recheck }
        return contains(keysAtCatchUp, row: key) ? .keep : .end
    }
}

/// `SidebarIdRemap.renameRowDecision`'s answer.
enum RenameRowDecision: Equatable {
    /// The row is there: the rename stays open.
    case keep
    /// The row is gone: the rename ends.
    case end
    /// The keys changed during the catch-up: nothing is decided here; their change asks again.
    case recheck
}
