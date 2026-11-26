import KeryxShared

/// A tag's or folder's display name together with the ordinal that tells it apart from same-named
/// rows — the native copy of `:shared`'s `OrdinalName` (`NameOrdinals.kt`), which decides both.
/// `text` is the one rendering every display of such a name goes through.
struct NameLabel: Equatable {
    let name: String
    /// `nil` for a uniquely named row, and for the first of its name.
    let ordinal: Int32?

    init(name: String, ordinal: Int32?) {
        self.name = name
        self.ordinal = ordinal
    }

    init(_ kotlin: OrdinalName) {
        self.init(name: kotlin.name, ordinal: kotlin.ordinal?.int32Value)
    }

    /// The name, or "name (n)" in the app's localized pattern.
    var text: String { nameWithOrdinal(name, ordinal) }
}

/// Every tag's and folder's `NameLabel`, from one snapshot of `HomeViewModel.tagFolderLabels`: a name
/// and its ordinal arrive together, so a rename that makes two rows share a name can never be shown
/// with the new name and the old ordinal. The one source the sidebar, the context menus, the delete
/// alerts and the menu bar render tag and folder names from.
struct TagFolderLabelSet: Equatable {
    let tags: [String: NameLabel]
    let folders: [String: NameLabel]

    static let empty = TagFolderLabelSet(tags: [:], folders: [:])

    init(tags: [String: NameLabel], folders: [String: NameLabel]) {
        self.tags = tags
        self.folders = folders
    }

    init(_ kotlin: TagFolderLabels) {
        self.init(
            tags: kotlin.tags.mapValues { NameLabel($0) },
            folders: kotlin.folders.mapValues { NameLabel($0) }
        )
    }

    /// The label of `tag`; its own bare name while this snapshot has no entry for it yet.
    func label(for tag: Tags) -> NameLabel { tags[tag.id] ?? NameLabel(name: tag.name, ordinal: nil) }

    /// The label of `folder`; its own bare name while this snapshot has no entry for it yet.
    func label(for folder: Folders) -> NameLabel { folders[folder.id] ?? NameLabel(name: folder.name, ordinal: nil) }

    func name(of tag: Tags) -> String { label(for: tag).text }
    func name(of folder: Folders) -> String { label(for: folder).text }
}
