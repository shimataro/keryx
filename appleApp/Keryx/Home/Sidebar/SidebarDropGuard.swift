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
/// - The outline's `expandItem(_:)` lets a drag spring open only what it can drop into: a dragged
///   feed opens a folder, a dragged folder opens nothing. Spring-loading goes through
///   `expandItem(_:)`, which SwiftUI's outline overrides to record the row as expanded before
///   expanding it; refusing it there keeps that record unchanged. SwiftUI applies its own expansion
///   state another way, so an expansion it asks for during a drag (a sync) still happens.
///
/// SwiftUI's own drop handling is never called for a feed or a folder, and the `List` carries no
/// drop modifier of its own: the drag types are registered here. Every other drag (from another app)
/// goes to the original implementations.
///
/// This relies on SwiftUI's internals: that the `List` is an `NSOutlineView` whose data source answers
/// `validateDrop`, and whose root items are the `List`'s sections. Where the outline cannot be read as
/// expected, a drop is refused; where the guard cannot be installed, no drag type is registered, so
/// sidebar drag and drop does nothing — either way nothing reaches SwiftUI. `SidebarDropGuardTests`
/// checks these assumptions against the SwiftUI the tests run on.
struct SidebarDropGuard: NSViewRepresentable {
    /// How many folders the Folders section shows.
    let folderCount: Int
    /// How many tags the Tags section shows.
    let tagCount: Int
    /// The shared action a drop at a destination takes, or `nil` where it would do nothing.
    let resolve: (SidebarDropDestination) -> FeedListDropAction?
    /// Applies a drop's action.
    let apply: (FeedListDropAction) -> Void

    func makeNSView(context: Context) -> NSView {
        GuardAnchorView()
    }

    func updateNSView(_ view: NSView, context: Context) {
        guard let anchor = view as? GuardAnchorView else { return }
        anchor.handler.folderCount = folderCount
        anchor.handler.tagCount = tagCount
        anchor.handler.resolve = resolve
        anchor.handler.apply = apply
        anchor.installWhenInHierarchy()
    }
}

/// The current values a guarded outline decides and applies drops with — refreshed on every update —
/// and the drag in progress.
@MainActor
final class SidebarDropHandler: NSObject {
    var folderCount = 0
    var tagCount = 0
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
        let reader = SidebarDropOutline(outline, folderCount: folderCount, tagCount: tagCount)
        guard let destination = sidebarDropDestination(
            dragging: kind,
            proposed: reader.classify(item),
            childIndex: childIndex,
            childCount: outline.numberOfChildren(ofItem: item)
        ), let action = resolve(destination), let place = reader.place(destination) else {
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

    /// Whether the drag in progress keeps `item` from springing open — anything but a folder under a
    /// dragged feed, the only row a drag can drop into.
    func refusesExpansion(of item: AnyObject?, in outline: NSOutlineView) -> Bool {
        guard let draggedKind else { return false }
        guard draggedKind == .feed else { return true }
        if case .folder = SidebarDropOutline(outline, folderCount: folderCount, tagCount: tagCount).classify(item) { return false }
        return true
    }
}

/// The `List`'s root items, in order: every section is one — All/Starred, Folders (only while there
/// are folders), "No folder", Tags (only while there are tags). Mirrors the sections
/// `FeedListView+SourceList.swift` builds; change both together.
enum SidebarRootSection: CaseIterable {
    case smart, folders, noFolder, tags

    /// The root items the `List` shows for `folderCount` folders and `tagCount` tags.
    static func roots(folderCount: Int, tagCount: Int) -> [SidebarRootSection] {
        allCases.filter { section in
            switch section {
            case .smart, .noFolder: return true
            case .folders: return folderCount > 0
            case .tags: return tagCount > 0
            }
        }
    }
}

/// Reads the `List`'s outline by its root sections (`SidebarRootSection`). Where the roots or a
/// section's children don't add up, the sections it can't be sure of are left unread, so nothing in
/// them is a drop target.
@MainActor
struct SidebarDropOutline {
    let outline: NSOutlineView
    let foldersSection: AnyObject?
    let noFolderSection: AnyObject?
    let tagsSection: AnyObject?

    init(_ outline: NSOutlineView, folderCount: Int, tagCount: Int) {
        self.outline = outline
        let roots = SidebarRootSection.roots(folderCount: folderCount, tagCount: tagCount)
        guard outline.numberOfChildren(ofItem: nil) == roots.count else {
            (foldersSection, noFolderSection, tagsSection) = (nil, nil, nil)
            return
        }
        // The root item of `section`, if the `List` shows it and it holds `count` children (any
        // number when `nil`).
        func root(_ section: SidebarRootSection, holding count: Int?) -> AnyObject? {
            guard let index = roots.firstIndex(of: section) else { return nil }
            let item = outline.child(index, ofItem: nil) as AnyObject
            guard let count else { return item }
            return outline.numberOfChildren(ofItem: item) == count ? item : nil
        }
        foldersSection = root(.folders, holding: folderCount)
        noFolderSection = root(.noFolder, holding: nil)
        tagsSection = root(.tags, holding: tagCount)
    }

    func classify(_ proposed: Any?) -> SidebarOutlineRow {
        guard let item = proposed as AnyObject? else { return .other }
        if Self.same(item, foldersSection) { return .foldersSection }
        if Self.same(item, noFolderSection) { return .noFolderSection }
        guard let parent = outline.parent(forItem: item) as AnyObject? else { return .other }
        if Self.same(parent, foldersSection) { return .folder(index: outline.childIndex(forItem: item)) }
        if Self.same(parent, noFolderSection) { return .unfolderedFeed }
        if Self.same(parent, tagsSection) { return .tag(index: outline.childIndex(forItem: item)) }
        let grandparent = outline.parent(forItem: parent) as AnyObject?
        if Self.same(grandparent, foldersSection) { return .feedInFolder(folderIndex: outline.childIndex(forItem: parent)) }
        if Self.same(grandparent, tagsSection) { return .feedInTag(tagIndex: outline.childIndex(forItem: parent)) }
        return .other
    }

    /// The outline's own terms for `destination`: the item and child index to point it at.
    func place(_ destination: SidebarDropDestination) -> (item: Any, childIndex: Int)? {
        let onto = sidebarDropOntoRowIndex
        switch destination {
        case .folderSlot(let index): return foldersSection.map { ($0, index) }
        case .ontoFolder(let index): return child(index, of: foldersSection).map { ($0, onto) }
        case .ontoNoFolder: return noFolderSection.map { ($0, onto) }
        // A feed's copy stands for its tag, so the tag's own row is the one highlighted.
        case .ontoTag(let index), .ontoFeedInTag(let index): return child(index, of: tagsSection).map { ($0, onto) }
        case .intoFolder(let index, let offset): return child(index, of: foldersSection).map { ($0, offset) }
        case .intoNoFolder(let offset): return noFolderSection.map { ($0, offset) }
        }
    }

    private func child(_ index: Int, of section: AnyObject?) -> AnyObject? {
        guard let section, (0..<outline.numberOfChildren(ofItem: section)).contains(index) else { return nil }
        return outline.child(index, ofItem: section) as AnyObject
    }

    private static func same(_ lhs: AnyObject?, _ rhs: AnyObject?) -> Bool {
        guard let lhs, let rhs else { return false }
        return lhs === rhs
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

    func installWhenInHierarchy() {
        // SwiftUI may add the outline after this view, so look again on the next turn.
        DispatchQueue.main.async { [weak self] in
            guard let self, self.window != nil, let outline = self.nearestOutline() else { return }
            SidebarDropGuardInstaller.install(on: outline, handler: self.handler)
        }
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
