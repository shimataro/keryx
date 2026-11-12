import Foundation
import KeryxShared
import UniformTypeIdentifiers

/// What a feed-list drag carries — the dragged row's kind and id, resolved on the drop side into
/// the shared `FeedListDraggedItem` the drop-resolution functions take.
struct FeedListDragPayload: Codable, Equatable {
    enum Kind: String, Codable {
        case feed
        case folder
    }
    let kind: Kind
    let id: String

    /// The pasteboard type the drag is published under. Feeds and folders get distinct types, so
    /// `SidebarDropGuard` tells which kind a drag carries from the pasteboard alone.
    var contentType: UTType {
        switch kind {
        case .feed: return feedListFeedDragType
        case .folder: return feedListFolderDragType
        }
    }

    func toShared() -> FeedListDraggedItem {
        switch kind {
        case .feed: return FeedListDraggedItemFeed(feedId: id)
        case .folder: return FeedListDraggedItemFolder(folderId: id)
        }
    }
}

/// Exported (`UTExportedTypeDeclarations` in `project.yml`) so a drag carrying anything else —
/// plain text from another app, say — is never taken for a feed-list reorder.
let feedListFeedDragType = UTType(exportedAs: "works.merc.keryx.app.feedlistitem.feed")
let feedListFolderDragType = UTType(exportedAs: "works.merc.keryx.app.feedlistitem.folder")

/// A `ForEach` in the sidebar that items can be inserted into, with its rows in display order.
enum FeedListInsertGroup {
    /// The feeds of one folder, or the unfoldered feeds when `folderId` is `nil`.
    case feeds(folderId: String?, feedIds: [String])
    /// The folders themselves.
    case folders(folderIds: [String])

    /// Whether a dragged item of `kind` can be inserted here.
    func accepts(_ kind: FeedListDragPayload.Kind) -> Bool {
        switch self {
        case .feeds: return kind == .feed
        case .folders: return kind == .folder
        }
    }
}

/// Translates an insertion position into the shared drop target and half
/// (`resolveFeedListDropAction`'s input): inserting before row `offset` is that row's top half,
/// inserting after the last row is the last row's bottom half, and a group with no rows is its own
/// header (the folder's row, or the "No folder" header). `nil` when there is nothing to insert
/// relative to — an empty folder list.
func feedListInsertTarget(
    in group: FeedListInsertGroup,
    at offset: Int
) -> (target: FeedListDropTarget, half: FeedListRowHalf)? {
    switch group {
    case .feeds(let folderId, let feedIds):
        if feedIds.isEmpty {
            let header: FeedListDropTarget = folderId.map { FeedListDropTargetFolderHeader(folderId: $0) }
                ?? FeedListDropTargetNoFolderHeader.shared
            return (header, .top)
        }
        return offset < feedIds.count
            ? (FeedListDropTargetFeedRow(feedId: feedIds[max(offset, 0)]), .top)
            : (FeedListDropTargetFeedRow(feedId: feedIds[feedIds.count - 1]), .bottom)
    case .folders(let folderIds):
        guard !folderIds.isEmpty else { return nil }
        return offset < folderIds.count
            ? (FeedListDropTargetFolderHeader(folderId: folderIds[max(offset, 0)]), .top)
            : (FeedListDropTargetFolderHeader(folderId: folderIds[folderIds.count - 1]), .bottom)
    }
}

/// Inserting `dragging` at `offset` in `group`: the shared action that drop resolves to through
/// `resolveFeedListDropAction` (the same rules the Compose app applies), or `nil` when the item does
/// not belong in `group` or there is nowhere to insert it.
///
/// The slots just before and just after a dragged folder are the same place, where nothing moves.
/// `feedListInsertTarget` would describe either from the dragged folder's own row, which the shared
/// rules refuse as a folder dropped onto itself — leaving no insertion line there, while the slot on
/// its other side shows one. So such a slot is described from the neighbouring folder instead
/// (the next one's top half, else the previous one's bottom half), which resolves to a reorder that
/// leaves the order as it is.
func feedListInsertAction(
    into group: FeedListInsertGroup,
    at offset: Int,
    dragging: FeedListDragPayload,
    index: FeedListDropIndex
) -> FeedListDropAction? {
    guard group.accepts(dragging.kind) else { return nil }
    let insert: (target: FeedListDropTarget, half: FeedListRowHalf)?
    if case .folders(let folderIds) = group, let own = folderIds.firstIndex(of: dragging.id), offset == own || offset == own + 1 {
        if own + 1 < folderIds.count {
            insert = (FeedListDropTargetFolderHeader(folderId: folderIds[own + 1]), .top)
        } else if own > 0 {
            insert = (FeedListDropTargetFolderHeader(folderId: folderIds[own - 1]), .bottom)
        } else {
            insert = nil
        }
    } else {
        insert = feedListInsertTarget(in: group, at: offset)
    }
    guard let insert else { return nil }
    return FeedListDragKt.resolveFeedListDropAction(
        item: dragging.toShared(), target: insert.target, half: insert.half, index: index
    )
}

/// A row of the outline behind the macOS sidebar's `List`, as `SidebarDropGuard` reads it — what a
/// drop is proposed onto or into.
enum SidebarOutlineRow: Equatable {
    /// The Folders section (its header row).
    case foldersSection
    /// The folder at `index` in the Folders section.
    case folder(index: Int)
    /// A feed shown in the folder at `folderIndex`.
    case feedInFolder(folderIndex: Int)
    /// The "No folder" section (its header row).
    case noFolderSection
    /// A feed in no folder.
    case unfolderedFeed
    /// The tag at `index` in the Tags section.
    case tag(index: Int)
    /// A feed's copy shown under the tag at `tagIndex`.
    case feedInTag(tagIndex: Int)
    /// Anything else, including a sidebar laid out other than expected.
    case other
}

/// Where a drop on the macOS sidebar lands — the row the outline highlights or the slot its
/// insertion line marks.
enum SidebarDropDestination: Equatable {
    /// Slot `index` among the folders.
    case folderSlot(index: Int)
    /// Onto the folder at `index`: the feed moves into it.
    case ontoFolder(index: Int)
    /// Onto the "No folder" header: the feed leaves its folder.
    case ontoNoFolder
    /// Onto the tag at `index`: the feed is tagged.
    case ontoTag(index: Int)
    /// Slot `offset` among the feeds of the folder at `index`.
    case intoFolder(index: Int, offset: Int)
    /// Slot `offset` among the feeds in no folder.
    case intoNoFolder(offset: Int)
}

/// The child index `NSOutlineView` proposes for a drop *onto* a row rather than between rows
/// (`NSOutlineViewDropOnItemIndex`).
let sidebarDropOntoRowIndex = -1

/// Where a dragged item of `kind` lands when the outline proposes `row` at `childIndex`, or `nil`
/// to refuse it. Every proposal gets a destination of its own here, so the sidebar always shows the
/// line or highlight of exactly where the item will go:
///
/// - A folder only ever goes between folders: a slot of the Folders section as proposed, its header
///   is the first slot, and anywhere on or in a folder — its own row, its feeds — is the slot just
///   after that folder.
/// - A feed dropped onto a folder, the "No folder" header or a tag goes into it; onto a feed's copy
///   under a tag, it goes to that tag. Between rows it goes into a folder's feeds or the
///   unfoldered ones, at a slot that exists (`childIndex` at most `childCount`).
/// - Anything else is refused — a feed row's own inside, say, which the outline proposes but which
///   holds nothing.
///
/// - Parameter childCount: How many children the proposed row has.
func sidebarDropDestination(
    dragging kind: FeedListDragPayload.Kind,
    proposed row: SidebarOutlineRow,
    childIndex: Int,
    childCount: Int
) -> SidebarDropDestination? {
    switch kind {
    case .folder:
        switch row {
        case .foldersSection: return .folderSlot(index: max(childIndex, 0))
        case .folder(let index), .feedInFolder(let index): return .folderSlot(index: index + 1)
        default: return nil
        }
    case .feed:
        if childIndex == sidebarDropOntoRowIndex {
            switch row {
            case .folder(let index): return .ontoFolder(index: index)
            case .noFolderSection: return .ontoNoFolder
            case .tag(let index), .feedInTag(let index): return .ontoTag(index: index)
            default: return nil
            }
        }
        guard (0...childCount).contains(childIndex) else { return nil }
        switch row {
        case .folder(let index): return .intoFolder(index: index, offset: childIndex)
        case .noFolderSection: return .intoNoFolder(offset: childIndex)
        default: return nil
        }
    }
}

/// The system's spring-loading preference (System Settings > Accessibility > Pointer Control),
/// read from the global defaults domain: `nil` when spring-loading is turned off, otherwise the
/// configured hover delay before a collapsed folder opens mid-drag. Neither key is written until
/// the user changes the setting, so an absent key means the system default (enabled, 0.5 s). iOS
/// has no such setting, so it always gets that default without reading anything: these keys are
/// written by the system, not the app, which the privacy manifest's UserDefaults reason (CA92.1)
/// does not cover. The Mac App Store build skips the read for the same reason and uses the same
/// default; only the macOS build from GitHub Releases reads them.
///
/// - Parameter lookup: Reads one defaults key — injectable because every `UserDefaults` instance,
///   even a throwaway suite, also searches the global domain this reads, so a test could not
///   otherwise isolate itself from the machine's own setting.
func springLoadingDelay(lookup: (String) -> Any? = systemSpringLoadingDefault) -> Duration? {
    if let enabled = lookup("com.apple.springing.enabled") as? Bool, !enabled {
        return nil
    }
    // `defaults write -g` stores whatever type it's given, so accept a string as well as a number.
    let raw = lookup("com.apple.springing.delay")
    let seconds = (raw as? NSNumber)?.doubleValue ?? (raw as? String).flatMap(Double.init) ?? 0.5
    return .milliseconds(Int((seconds * 1000).rounded()))
}

/// `springLoadingDelay`'s default lookup: the global defaults domain on the macOS build from GitHub
/// Releases, nothing on iOS or the Mac App Store build.
private func systemSpringLoadingDefault(_ key: String) -> Any? {
    #if os(macOS) && !MAC_APP_STORE
    UserDefaults.standard.object(forKey: key)
    #else
    nil
    #endif
}
