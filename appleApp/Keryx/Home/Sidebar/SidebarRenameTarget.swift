import KeryxShared

/// What the iOS rename sheet edits for one sidebar row: which entity, the name it starts from, and
/// the rules that differ per kind (the same ones the in-place editor follows on macOS —
/// `FeedListView.folderRenameEditor` / `tagRenameEditor` / `feedRenameEditor`). Kept free of SwiftUI so
/// the standalone `KeryxTests` bundle can compile it directly.
struct SidebarRenameTarget: Equatable {
    enum Kind: Equatable {
        case folder(id: String)
        case tag(id: String)
        case feed(id: String)
    }

    let kind: Kind
    /// The name the sheet opens with.
    let initialName: String
    /// Shown while the field is empty: the title a blanked feed name falls back to.
    let placeholder: String?
    /// A feed may be blanked (that clears its custom title); a folder or tag may not.
    let allowBlank: Bool
    /// The String Catalog key of the duplicate-name message, or `nil` where duplicates are allowed
    /// (feeds).
    let duplicateMessageKey: String?

    /// The target for `item`, or `nil` when it has no name to edit (All, Starred, a header) or no
    /// longer exists.
    static func resolve(
        _ item: SidebarItemID,
        folders: [Folders],
        tags: [Tags],
        feedsById: [String: Feeds]
    ) -> SidebarRenameTarget? {
        switch item {
        case .folder(let id):
            guard let folder = folders.first(where: { $0.id == id }) else { return nil }
            return SidebarRenameTarget(
                kind: .folder(id: id), initialName: folder.name, placeholder: nil, allowBlank: false,
                duplicateMessageKey: "home_folder_name_duplicate"
            )
        case .tag(let id):
            guard let tag = tags.first(where: { $0.id == id }) else { return nil }
            return SidebarRenameTarget(
                kind: .tag(id: id), initialName: tag.name, placeholder: nil, allowBlank: false,
                duplicateMessageKey: "home_tag_name_duplicate"
            )
        case .feed(let id), .feedInTag(let id, _):
            guard let feed = feedsById[id] else { return nil }
            return SidebarRenameTarget(
                kind: .feed(id: id), initialName: feed.displayTitle(), placeholder: feed.title, allowBlank: true,
                duplicateMessageKey: nil
            )
        case .all, .starred, .sectionHeader, .noFolderHeader:
            return nil
        }
    }
}
