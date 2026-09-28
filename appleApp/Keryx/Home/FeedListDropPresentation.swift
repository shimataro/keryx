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

    /// The pasteboard type the drag is published under. Feeds and folders get distinct types so
    /// each `ForEach`'s `.onInsert(of:)` names only the kind it accepts, and the outline itself
    /// then refuses — and draws no insertion line for — a folder between feeds or a feed between
    /// folders.
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

/// Swift-native mirror of which row a dragged feed is currently over, used only for `@State`
/// identity (which row's highlight to clear on `dropExited`) — the bridged `FeedListDropTarget`
/// Kotlin types aren't `Equatable` across the existential. Only the rows a feed is dropped *onto*
/// are listed; dropping between rows is the outline's own `.onInsert`.
enum FeedListHoverKey: Equatable {
    case folder(String)
    case noFolder
    case tag(String)
}

/// How a row a dragged item is *over* (rather than between) answers it. Insertion between rows is
/// the outline's own `.onInsert`, so only the drop-onto case is decided here.
enum FeedListDropFeedback: Equatable {
    /// Releasing here does nothing — the drag is refused, so a release slides the item back.
    case invalid
    /// Highlight the hovered row: the feed would be moved into that folder or the unfoldered group,
    /// or tagged.
    case dropOn
}

/// A feed dropped onto a folder row, tag row or the "No folder" header lands in it; a folder is
/// never dropped *onto* a row — it is reordered between folders instead.
func feedListDropFeedback(for kind: FeedListDragPayload.Kind) -> FeedListDropFeedback {
    kind == .feed ? .dropOn : .invalid
}

/// A `ForEach` in the sidebar that items can be inserted into, with its rows in display order.
enum FeedListInsertGroup {
    /// The feeds of one folder, or the unfoldered feeds when `folderId` is `nil`.
    case feeds(folderId: String?, feedIds: [String])
    /// The folders themselves.
    case folders(folderIds: [String])

    /// Whether a dragged item of `kind` can be inserted here — each group's `.onInsert(of:)`
    /// already names only its own kind's type; this re-checks it before acting.
    func accepts(_ kind: FeedListDragPayload.Kind) -> Bool {
        switch self {
        case .feeds: return kind == .feed
        case .folders: return kind == .folder
        }
    }
}

/// Translates an `.onInsert` position into the shared drop target and half
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

/// The system's spring-loading preference (System Settings > Accessibility > Pointer Control),
/// read from the global defaults domain: `nil` when spring-loading is turned off, otherwise the
/// configured hover delay before a collapsed folder opens mid-drag. Neither key is written until
/// the user changes the setting, so an absent key means the system default (enabled, 0.5 s). iOS
/// has no such setting, so it always gets that default.
///
/// - Parameter lookup: Reads one defaults key — injectable because every `UserDefaults` instance,
///   even a throwaway suite, also searches the global domain this reads, so a test could not
///   otherwise isolate itself from the machine's own setting.
func springLoadingDelay(lookup: (String) -> Any? = { UserDefaults.standard.object(forKey: $0) }) -> Duration? {
    if let enabled = lookup("com.apple.springing.enabled") as? Bool, !enabled {
        return nil
    }
    // `defaults write -g` stores whatever type it's given, so accept a string as well as a number.
    let raw = lookup("com.apple.springing.delay")
    let seconds = (raw as? NSNumber)?.doubleValue ?? (raw as? String).flatMap(Double.init) ?? 0.5
    return .milliseconds(Int((seconds * 1000).rounded()))
}
