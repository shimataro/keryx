import KeryxShared

/// The tag, folder and feed id re-keys a merge has published (`HomeViewModel.idRemap`), as the
/// sidebar needs them: to tell that a row key it holds from before a re-key and the key the row has
/// now name the same row. A key is `feedListRowSelectionKey`'s string (`folder:ID`, `tag:ID`,
/// `feed-in-folder:FEED`, `feed-in-tag:TAG:FEED`, ...); the tag, folder and feed ids in it move.
///
/// A held key is never rewritten, only compared through this map: the map and the lists a merge
/// changes reach the UI on separate flows, in either order, and two ids the map joins are the same
/// row whichever of them the lists currently use.
struct SidebarIdRemap: Equatable {
    /// Old tag id → the id the tag has now.
    let tags: [String: String]
    /// Old folder id → the id the folder has now.
    let folders: [String: String]
    /// Old feed id → the id the feed has now (a merge folded it into another feed).
    let feeds: [String: String]

    static let empty = SidebarIdRemap(tags: [:], folders: [:], feeds: [:])

    init(tags: [String: String], folders: [String: String], feeds: [String: String] = [:]) {
        self.tags = tags
        self.folders = folders
        self.feeds = feeds
    }

    init(_ kotlin: IdRemap) {
        self.init(tags: kotlin.tagIds, folders: kotlin.folderIds, feeds: kotlin.feedIds)
    }

    private static let folderPrefix = "folder:"
    private static let tagPrefix = "tag:"
    private static let feedInFolderPrefix = "feed-in-folder:"
    private static let feedInTagPrefix = "feed-in-tag:"

    /// `key` with its tag, folder or feed ids brought up to date; `key` itself when nothing moved.
    func current(_ key: String) -> String {
        if key.hasPrefix(Self.folderPrefix) {
            let id = String(key.dropFirst(Self.folderPrefix.count))
            return folders[id].map { Self.folderPrefix + $0 } ?? key
        }
        if key.hasPrefix(Self.tagPrefix) {
            let id = String(key.dropFirst(Self.tagPrefix.count))
            return tags[id].map { Self.tagPrefix + $0 } ?? key
        }
        if key.hasPrefix(Self.feedInFolderPrefix) {
            let id = String(key.dropFirst(Self.feedInFolderPrefix.count))
            return feeds[id].map { Self.feedInFolderPrefix + $0 } ?? key
        }
        if key.hasPrefix(Self.feedInTagPrefix) {
            // `feed-in-tag:TAG:FEED`: ids never contain a colon, so the first one after the prefix splits them.
            let rest = key.dropFirst(Self.feedInTagPrefix.count)
            guard let colon = rest.firstIndex(of: ":") else { return key }
            let tag = String(rest[..<colon])
            let feed = String(rest[rest.index(after: colon)...])
            let newTag = tags[tag] ?? tag
            let newFeed = feeds[feed] ?? feed
            return newTag == tag && newFeed == feed ? key : "\(Self.feedInTagPrefix)\(newTag):\(newFeed)"
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
