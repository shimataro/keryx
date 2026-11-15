#if os(macOS)
import AppKit
import KeryxShared
import SwiftUI

/// Decides and applies every feed and folder drop on the macOS sidebar itself, instead of SwiftUI.
///
/// The sidebar's `List` is an `NSOutlineView` underneath, whose data source is SwiftUI's own
/// `OutlineListCoordinator`. SwiftUI's handling of a drop there is unreliable: it crashes applying a
/// folder dropped between folders, and also a feed proposed into a feed row, with an out-of-range
/// index (`HeterogeneousCollection.element(at:)`, macOS 26); it refuses a folder over a folder's
/// feeds; it moves the target elsewhere while refusing; it answers "copy", so the cursor carries a
/// "+". See "macOS: dropping a folder between folders crashed inside SwiftUI" in
/// `docs/known-issues.md`.
///
/// So the guard adds methods of its own to the classes of the outline and its data source
/// (`SidebarDropGuardInstaller`), which act only on the outline it guards:
///
/// - The data source's `outlineView(_:validateDrop:proposedItem:proposedChildIndex:)` reads what the
///   outline proposes (`SidebarDropOutline`), decides where the drop lands
///   (`sidebarDropDestination`), and when the shared rules make an action of it (`resolve`), points
///   the outline there (`setDropItem`) and answers "move". The outline then draws the insertion line
///   or the row highlight itself.
/// - The outline's `performDragOperation:` applies that action (`apply`) — only if the destination
///   still resolves to it, so a sidebar that changed under the drag (a sync) never sends the item
///   somewhere other than what was highlighted.
/// - The outline's `expandItem(_:)` keeps a dragged folder from springing anything open — folders
///   are only ordered, never dropped into; a dragged feed springs a folder or a tag open as usual.
///   Spring-loading goes through
///   `expandItem(_:)`, which SwiftUI's outline overrides to record the row as expanded before
///   expanding it; refusing it there keeps that record unchanged. SwiftUI applies its own expansion
///   state another way, so an expansion it asks for during a drag (a sync) still happens.
///
/// SwiftUI's own drop handling is never called for a feed or a folder, and the `List` carries no
/// drop modifier of its own: the drag types are registered here. Every other drag (from another app)
/// goes to the original implementations.
///
/// This relies on SwiftUI's internals: that the `List` is an `NSOutlineView` whose data source answers
/// `validateDrop`, whose items nest as the `List`'s sections and rows do, and whose row views hold each
/// row's content — where the rows' markers (`SidebarRowMarker`) are read. Where the outline cannot be read as
/// expected, a drop is refused; where the guard cannot be installed, no drag type is registered, so
/// sidebar drag and drop does nothing — either way nothing reaches SwiftUI. `SidebarDropGuardTests`
/// checks these assumptions against the SwiftUI the tests run on.
struct SidebarDropGuard: NSViewRepresentable {
    /// The folders' ids, in the order the Folders section shows them.
    let folderIds: [String]
    /// The tags' ids, in the order the Tags section shows them.
    let tagIds: [String]
    /// The shared action a drop at a destination takes, or `nil` where it would do nothing.
    let resolve: (SidebarDropDestination) -> FeedListDropAction?
    /// Applies a drop's action.
    let apply: (FeedListDropAction) -> Void

    func makeNSView(context: Context) -> NSView {
        GuardAnchorView()
    }

    func updateNSView(_ view: NSView, context: Context) {
        guard let anchor = view as? GuardAnchorView else { return }
        anchor.handler.folderIds = folderIds
        anchor.handler.tagIds = tagIds
        anchor.handler.resolve = resolve
        anchor.handler.apply = apply
        anchor.installWhenInHierarchy()
    }
}

/// The current values a guarded outline decides and applies drops with — refreshed on every update —
/// and the drag in progress.
@MainActor
final class SidebarDropHandler: NSObject {
    var folderIds: [String] = []
    var tagIds: [String] = []
    var resolve: (SidebarDropDestination) -> FeedListDropAction? = { _ in nil }
    var apply: (FeedListDropAction) -> Void = { _ in }

    /// Where the drag in progress would land and what it would do there, as last pointed out to the
    /// outline.
    private(set) var pending: (destination: SidebarDropDestination, action: FeedListDropAction)?
    /// What the drag in progress carries, from its first validation until it ends.
    private(set) var draggedKind: FeedListDragPayload.Kind?

    /// The kind of sidebar item `info` carries, or `nil` for any other drag.
    nonisolated static func kind(of info: NSDraggingInfo) -> FeedListDragPayload.Kind? {
        let types = info.draggingPasteboard.types ?? []
        if types.contains(NSPasteboard.PasteboardType(feedListFolderDragType.identifier)) { return .folder }
        if types.contains(NSPasteboard.PasteboardType(feedListFeedDragType.identifier)) { return .feed }
        return nil
    }

    /// Answers the outline's proposal of `item` at `childIndex`: points the outline at where the drop
    /// lands and answers "move", or refuses.
    func validate(
        _ outline: NSOutlineView,
        dragging kind: FeedListDragPayload.Kind,
        proposedItem item: Any?,
        childIndex: Int
    ) -> NSDragOperation {
        draggedKind = kind
        let reader = SidebarDropOutline(outline: outline, folderIds: folderIds, tagIds: tagIds) {
            SidebarDropGuardInstaller.report("A sidebar row carries no marker", refusing: "a drop onto it")
        }
        let row = reader.classify(item)
        guard let destination = sidebarDropDestination(
            dragging: kind,
            proposed: row,
            childIndex: childIndex,
            childCount: outline.numberOfChildren(ofItem: item)
        ), let action = resolve(destination), let place = reader.place(destination, proposed: item, row: row) else {
            pending = nil
            return []
        }
        outline.setDropItem(place.item, dropChildIndex: place.childIndex)
        pending = (destination, action)
        return .move
    }

    /// Applies the drop the last validation pointed out; `false` when there is none, or when the
    /// destination no longer resolves to the action that was highlighted.
    func perform() -> Bool {
        defer { dragEnded() }
        // Resolved again on purpose: a sync landing between the last validation and the drop can
        // change what a position points at, and the drop must not go anywhere but where it was shown.
        guard let pending, let current = resolve(pending.destination),
              (current as AnyObject).isEqual(pending.action as AnyObject) else { return false }
        apply(pending.action)
        return true
    }

    func dragEnded() {
        pending = nil
        draggedKind = nil
    }

    /// Whether the drag in progress keeps rows from springing open: a dragged folder opens nothing.
    var refusesExpansion: Bool { draggedKind == .folder }
}

/// Reads the `List`'s outline by the markers its rows carry (`SidebarRowMarker`), never by where a row
/// or a section sits — so a row SwiftUI adds of its own (before macOS 26, the sidebar's search field is
/// a root item ahead of the sections) or a reordered section changes nothing. A row is never created to
/// be read: SwiftUI fills a fresh row's content only on a later turn, so only the rows the outline
/// already has are read, and anything else is found from the proposed item and its outline
/// parent/children.
///
/// A section's own item is told by a marker on its header row ("No folder") or, where its header
/// carries none (Folders, Tags), by a marked child: those sections are shown only while they hold
/// something. A row that should carry a marker but has none readable is not a drop target.
@MainActor
struct SidebarDropOutline {
    let outline: NSOutlineView
    /// The folders' ids, in the order the Folders section shows them.
    let folderIds: [String]
    /// The tags' ids, in the order the Tags section shows them.
    let tagIds: [String]
    /// Called when a row that should be marked has no readable marker.
    var reportUnmarkedRow: () -> Void = {}

    /// The item `item`'s row is marked with, or `nil` where it has no row yet or carries no marker.
    func marker(of item: Any?) -> SidebarItemID? {
        let row = outline.row(forItem: item)
        guard row >= 0 else { return nil }
        let views = [
            outline.rowView(atRow: row, makeIfNecessary: false),
            outline.view(atColumn: 0, row: row, makeIfNecessary: false),
        ]
        return views.lazy.compactMap { $0.flatMap(Self.marker(in:)) }.first
    }

    private static func marker(in view: NSView) -> SidebarItemID? {
        if let marker = view as? SidebarRowMarkerView { return marker.item }
        return view.subviews.lazy.compactMap { marker(in: $0) }.first
    }

    func classify(_ proposed: Any?) -> SidebarOutlineRow {
        guard let item = proposed as AnyObject? else { return .other }
        guard let id = marker(of: item) else {
            // A section of its own, told by its children; or a row that ought to be marked.
            if outline.parent(forItem: item) == nil {
                if case .folder? = firstChildMarker(of: item) { return .foldersSection }
                return .other
            }
            reportUnmarkedRow()
            return .other
        }
        switch id {
        case .noFolderHeader:
            return .noFolderSection
        case .folder(let folderId):
            return index(of: folderId, in: folderIds, matching: item).map { .folder(index: $0) } ?? .other
        case .tag(let tagId):
            return index(of: tagId, in: tagIds, matching: item).map { .tag(index: $0) } ?? .other
        case .feedInTag(_, let tagId):
            return tagIds.firstIndex(of: tagId).map { .feedInTag(tagIndex: $0) } ?? .other
        case .feed:
            let parent = outline.parent(forItem: item)
            switch marker(of: parent) {
            case .folder(let folderId):
                return folderIds.firstIndex(of: folderId).map { .feedInFolder(folderIndex: $0) } ?? .other
            case .noFolderHeader:
                return .unfolderedFeed
            default:
                return .other
            }
        case .all, .starred, .sectionHeader:
            return .other
        }
    }

    /// The outline's own terms for `destination`, which a drop proposed at `proposed` (read as `row` by
    /// `classify`) resolved to: the item and child index to point it at.
    func place(
        _ destination: SidebarDropDestination,
        proposed: Any?,
        row: SidebarOutlineRow
    ) -> (item: Any, childIndex: Int)? {
        let onto = sidebarDropOntoRowIndex
        let item = proposed as AnyObject?
        switch destination {
        case .folderSlot(let index):
            return foldersSection(from: item, row: row).map { ($0, index) }
        case .ontoFolder(let index):
            return folder(index, from: item, row: row).map { ($0, onto) }
        case .intoFolder(let index, let offset):
            return folder(index, from: item, row: row).map { ($0, offset) }
        case .ontoNoFolder:
            return row == .noFolderSection ? item.map { ($0, onto) } : nil
        case .intoNoFolder(let offset):
            return row == .noFolderSection ? item.map { ($0, offset) } : nil
        // A feed's copy stands for its tag, so the tag's own row is the one highlighted.
        case .ontoTag(let index), .ontoFeedInTag(let index):
            let tags: AnyObject? = switch row {
            case .tag: outline.parent(forItem: item) as AnyObject?
            case .feedInTag: outline.parent(forItem: outline.parent(forItem: item)) as AnyObject?
            default: nil
            }
            return child(index, of: tags, markedAs: tagIds.indices.contains(index) ? .tag(tagIds[index]) : nil)
                .map { ($0, onto) }
        }
    }

    /// The Folders section's item, found from the proposed `item` read as `row`.
    private func foldersSection(from item: AnyObject?, row: SidebarOutlineRow) -> AnyObject? {
        switch row {
        case .foldersSection: return item
        case .folder: return outline.parent(forItem: item) as AnyObject?
        case .feedInFolder: return outline.parent(forItem: outline.parent(forItem: item)) as AnyObject?
        default: return nil
        }
    }

    private func folder(_ index: Int, from item: AnyObject?, row: SidebarOutlineRow) -> AnyObject? {
        guard folderIds.indices.contains(index) else { return nil }
        return child(index, of: foldersSection(from: item, row: row), markedAs: .folder(folderIds[index]))
    }

    /// The child at `index` of `section` — unless its row is marked as anything but `expected`.
    private func child(_ index: Int, of section: AnyObject?, markedAs expected: SidebarItemID?) -> AnyObject? {
        guard let section, let expected, (0..<outline.numberOfChildren(ofItem: section)).contains(index) else { return nil }
        let child = outline.child(index, ofItem: section) as AnyObject
        if let id = marker(of: child), id != expected { return nil }
        return child
    }

    /// The index of `id` in `ids`, provided `item` sits at that index among its siblings — so a row
    /// whose position disagrees with the sidebar's own order is not a drop target.
    private func index(of id: String, in ids: [String], matching item: AnyObject) -> Int? {
        guard let index = ids.firstIndex(of: id), outline.childIndex(forItem: item) == index else { return nil }
        return index
    }

    /// The marker of the first of `item`'s children that has one.
    private func firstChildMarker(of item: AnyObject) -> SidebarItemID? {
        (0..<outline.numberOfChildren(ofItem: item)).lazy
            .compactMap { marker(of: outline.child($0, ofItem: item)) }
            .first
    }
}

/// A zero-size view behind the `List` that finds the outline it sits in and guards it — again on
/// every update, so a data source SwiftUI replaces is guarded too.
private final class GuardAnchorView: NSView {
    @MainActor let handler = SidebarDropHandler()

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        installWhenInHierarchy()
    }

    /// The outline this view last guarded.
    private weak var guardedOutline: NSOutlineView?

    /// Until the outline is guarded, every layout tries again: SwiftUI may add the outline after this
    /// view, and a main-queue turn can come late (inside a nested run loop), while layout still runs.
    override func layout() {
        super.layout()
        if guardedOutline == nil { install() }
    }

    func installWhenInHierarchy() {
        // Right away where the outline is already there, and again on the next turn, in case SwiftUI
        // adds it after this view.
        install()
        DispatchQueue.main.async { [weak self] in self?.install() }
    }

    private func install() {
        guard window != nil, let outline = nearestOutline(),
              SidebarDropGuardInstaller.install(on: outline, handler: handler) else { return }
        guardedOutline = outline
    }

    /// The `NSOutlineView` nearest this view: the first one found under each ancestor in turn, so
    /// the sidebar's own outline is picked rather than any other in the window.
    private func nearestOutline() -> NSOutlineView? {
        var ancestor = superview
        while let current = ancestor {
            if let outline = Self.firstOutline(in: current) { return outline }
            ancestor = current.superview
        }
        return nil
    }

    private static func firstOutline(in view: NSView) -> NSOutlineView? {
        if let outline = view as? NSOutlineView { return outline }
        for subview in view.subviews {
            if let outline = firstOutline(in: subview) { return outline }
        }
        return nil
    }
}
#endif
