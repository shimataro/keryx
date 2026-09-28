import CoreGraphics
import Foundation
import KeryxShared

/// Swift-native mirror of which row/header is currently the drop target, used only for `@State`
/// identity (which row's highlight to clear on `dropExited`) — the bridged `FeedListDropTarget`
/// Kotlin types aren't `Equatable` across the existential.
enum FeedListHoverKey: Equatable {
    case folder(String)
    case noFolder
    case feed(String)
    case tag(String)

    /// A folder, "No folder" or tag header — the rows a feed is dropped *onto* (moved into the
    /// folder, or tagged) rather than *between*.
    var isHeader: Bool {
        switch self {
        case .folder, .noFolder, .tag: return true
        case .feed: return false
        }
    }
}

/// How the hovered row answers a drag, following macOS's own source-list conventions
/// (`NSOutlineView`'s drop feedback): an insertion line between rows, a highlight on the row the
/// item would be dropped onto, or nothing at all where releasing would do nothing.
enum FeedListDropFeedback: Equatable {
    /// Releasing here does nothing — the drag is refused, so a release slides the item back.
    case invalid
    /// Draw the insertion line at the resolved boundary.
    case insertion
    /// Highlight the hovered header itself; no insertion line.
    case dropOn
}

/// Resolves the feedback for one hover from the shared `resolveFeedListDropHighlight` result.
/// A feed over a header is "dropped onto" it (moved into that folder / the unfoldered group, or
/// tagged): Compose draws the folder's front-insertion line there as well, but macOS's drop-on
/// convention is the highlight alone. A folder over a folder header reorders, so it stays an
/// insertion.
func feedListDropFeedback(
    isFeedDrag: Bool,
    hoverKey: FeedListHoverKey,
    boundary: DropBoundary?,
    attachTagId: String?
) -> FeedListDropFeedback {
    if boundary == nil && attachTagId == nil { return .invalid }
    return isFeedDrag && hoverKey.isHeader ? .dropOn : .insertion
}

/// Which half of a row the pointer is over — the top half inserts before the row, the bottom half
/// after it. A row whose height isn't known yet resolves to the top half.
func feedListRowHalf(locationY: CGFloat, rowHeight: CGFloat) -> FeedListRowHalf {
    rowHeight > 0 && locationY >= rowHeight / 2 ? .bottom : .top
}

/// Whether an insertion line at `boundary` sits at the nested (feed) level rather than the
/// top (folder) level — mirrors Compose's own `InsertionMarker.indented`.
func isNestedDropBoundary(_ boundary: DropBoundary) -> Bool {
    switch onEnum(of: boundary) {
    case .beforeFeed, .appendFeeds: return true
    case .beforeFolder, .appendFolders: return false
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
