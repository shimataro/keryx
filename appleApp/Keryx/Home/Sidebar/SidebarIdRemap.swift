import KeryxShared

/// The tag/folder id re-keys a sync merge has published (`HomeViewModel.tagFolderIdRemap`), as the
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

    init(_ kotlin: TagFolderIdRemap) {
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
}
