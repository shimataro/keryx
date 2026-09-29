import KeryxShared

/// The iOS sidebar's sections, in display order — the same grouping as the macOS source list
/// (`FeedListView+SourceList.swift`) and Compose's own feed list: All/Starred, the folders, the
/// always-present "No folder" group, then the tags.
enum SidebarSection: String, Hashable, Sendable {
    /// All / Starred — no header.
    case smart
    case folders
    case noFolder
    case tags
}

/// One item of the iOS sidebar's collection view — a row or a section header. Built from strings
/// alone (never a bridged Kotlin value) so it is `Sendable`, as the diffable data source's item
/// identifiers must be under Swift 6.
///
/// Section headers are ordinary items (the first item of their section) rather than supplementary
/// views, because only an item can be a drop destination.
enum SidebarItemID: Hashable, Sendable {
    case all
    case starred
    /// The collapsible Folders / Tags header.
    case sectionHeader(SidebarSection)
    /// The "No folder" header — a drop target for moving a feed out of every folder, never
    /// selectable (there is no `ArticleFilter` for "every unfoldered feed").
    case noFolderHeader
    case folder(String)
    /// A feed in its folder group, or among the unfoldered feeds.
    case feed(String)
    case tag(String)
    /// A feed's copy nested under an expanded tag.
    case feedInTag(feedId: String, tagId: String)

    /// The row's `feedListRowSelectionKey` — `nil` for a header, which is never selected.
    var selectionKey: String? {
        switch self {
        case .all: return "all"
        case .starred: return "starred"
        case .sectionHeader, .noFolderHeader: return nil
        case .folder(let id): return "folder:\(id)"
        case .feed(let id): return "feed-in-folder:\(id)"
        case .tag(let id): return "tag:\(id)"
        case .feedInTag(let feedId, let tagId): return "feed-in-tag:\(tagId):\(feedId)"
        }
    }

    /// The shared selection this row stands for — `nil` for a header.
    var rowSelection: FeedListRowSelection? {
        switch self {
        case .all: return FeedListRowSelectionAll()
        case .starred: return FeedListRowSelectionStarred()
        case .sectionHeader, .noFolderHeader: return nil
        case .folder(let id): return FeedListRowSelectionFolder(folderId: id)
        case .feed(let id): return FeedListRowSelectionFeedInFolderGroup(feedId: id)
        case .tag(let id): return FeedListRowSelectionTag(tagId: id)
        case .feedInTag(let feedId, let tagId): return FeedListRowSelectionFeedInTag(feedId: feedId, tagId: tagId)
        }
    }

    /// The item rendering `selection`.
    init(_ selection: FeedListRowSelection) {
        switch onEnum(of: selection) {
        case .all: self = .all
        case .starred: self = .starred
        case .folder(let f): self = .folder(f.folderId)
        case .tag(let t): self = .tag(t.tagId)
        case .feedInFolderGroup(let f): self = .feed(f.feedId)
        case .feedInTag(let f): self = .feedInTag(feedId: f.feedId, tagId: f.tagId)
        }
    }

    /// What dragging this row carries — `nil` for a row that is never dragged: All/Starred, the
    /// headers and the tags (which are only ever dropped onto).
    var dragPayload: FeedListDragPayload? {
        switch self {
        case .feed(let id), .feedInTag(let id, _): return FeedListDragPayload(kind: .feed, id: id)
        case .folder(let id): return FeedListDragPayload(kind: .folder, id: id)
        case .all, .starred, .sectionHeader, .noFolderHeader, .tag: return nil
        }
    }
}
