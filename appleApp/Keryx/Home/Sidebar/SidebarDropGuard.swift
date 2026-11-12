#if os(macOS)
import AppKit
import ObjectiveC
import OSLog
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
///   (`sidebarDropDestination`), and when the shared rules make something of it (`canDrop`), points
///   the outline there (`setDropItem`) and answers "move". The outline then draws the insertion line
///   or the row highlight itself.
/// - The outline's `performDragOperation:` applies that destination (`drop`).
/// - The outline's `expandItem(_:expandChildren:)` keeps a dragged folder from springing a folder
///   open — folders are only ordered, never dropped into. A dragged feed still springs one open, which
///   the outline does on its own.
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
    /// Whether a drop at a destination does something — the shared rules resolve an action for it.
    let canDrop: (SidebarDropDestination) -> Bool
    /// Applies a drop at a destination.
    let drop: (SidebarDropDestination) -> Void

    func makeNSView(context: Context) -> NSView {
        GuardAnchorView()
    }

    func updateNSView(_ view: NSView, context: Context) {
        guard let anchor = view as? GuardAnchorView else { return }
        anchor.handler.folderCount = folderCount
        anchor.handler.tagCount = tagCount
        anchor.handler.canDrop = canDrop
        anchor.handler.drop = drop
        anchor.installWhenInHierarchy()
    }
}

/// The current values a guarded outline decides and applies drops with — refreshed on every update —
/// and the drag in progress.
@MainActor
final class SidebarDropHandler: NSObject {
    var folderCount = 0
    var tagCount = 0
    var canDrop: (SidebarDropDestination) -> Bool = { _ in false }
    var drop: (SidebarDropDestination) -> Void = { _ in }

    /// Where the drag in progress would land, as last pointed out to the outline.
    private(set) var destination: SidebarDropDestination?
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
        ), canDrop(destination), let place = reader.place(destination) else {
            destination = nil
            return []
        }
        outline.setDropItem(place.item, dropChildIndex: place.childIndex)
        self.destination = destination
        return .move
    }

    /// Applies the drop the last validation pointed out; `false` when there is none.
    func perform() -> Bool {
        defer { dragEnded() }
        guard let destination, canDrop(destination) else { return false }
        drop(destination)
        return true
    }

    func dragEnded() {
        destination = nil
        draggedKind = nil
    }

    /// A dragged folder never springs a folder open.
    var refusesExpansion: Bool { draggedKind == .folder }
}

/// Reads the `List`'s outline: every section is a root item, in the `List`'s order — All/Starred,
/// Folders (only while there are folders), "No folder", Tags (only while there are tags). Where the
/// roots or a section's children don't add up, the sections it can't be sure of are left unread, so
/// nothing in them is a drop target.
@MainActor
struct SidebarDropOutline {
    let outline: NSOutlineView
    let foldersSection: AnyObject?
    let noFolderSection: AnyObject?
    let tagsSection: AnyObject?

    init(_ outline: NSOutlineView, folderCount: Int, tagCount: Int) {
        self.outline = outline
        let expectedRoots = 2 + (folderCount > 0 ? 1 : 0) + (tagCount > 0 ? 1 : 0)
        guard outline.numberOfChildren(ofItem: nil) == expectedRoots else {
            (foldersSection, noFolderSection, tagsSection) = (nil, nil, nil)
            return
        }
        func section(_ index: Int, holding count: Int?) -> AnyObject? {
            let item = outline.child(index, ofItem: nil) as AnyObject
            guard let count else { return item }
            return outline.numberOfChildren(ofItem: item) == count ? item : nil
        }
        let noFolderIndex = folderCount > 0 ? 2 : 1
        foldersSection = folderCount > 0 ? section(1, holding: folderCount) : nil
        noFolderSection = section(noFolderIndex, holding: nil)
        tagsSection = tagCount > 0 ? section(noFolderIndex + 1, holding: tagCount) : nil
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
        case .ontoTag(let index): return child(index, of: tagsSection).map { ($0, onto) }
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

/// Adds the guard's methods to the classes of the outline and its data source — SwiftUI's own
/// `SwiftUIOutlineListView` and `OutlineListCoordinator` — once each, and attaches a handler to the
/// sidebar's outline. The methods act only on an outline carrying a handler, and only on a feed or
/// folder drag; for everything else they forward to the original implementation. Adding to the class
/// rather than swapping the instance onto a subclass of its own keeps the guard working whether KVO
/// (which swaps the instance onto a subclass, e.g. for accessibility) observes it first or later.
@MainActor
enum SidebarDropGuardInstaller {
    private static var handlerKey: UInt8 = 0
    private static var guardedClasses: Set<ObjectIdentifier> = []
    private static var reportedFailures: Set<String> = []
    private static let logger = Logger(subsystem: Bundle.main.bundleIdentifier ?? "Keryx", category: "SidebarDropGuard")

    /// Guards `outline` and its data source, then registers the sidebar's drag types — only once both
    /// are guarded, so a half-guarded outline never takes a drop. `true` when it is guarded.
    @discardableResult
    static func install(on outline: NSOutlineView, handler: SidebarDropHandler) -> Bool {
        guard let dataSource = outline.dataSource as? NSObject else {
            report("The sidebar outline has no data source")
            return false
        }
        guard guardClass(of: outline, adding: addOutlineOverrides),
              guardClass(of: dataSource, adding: addDataSourceOverrides) else { return false }
        objc_setAssociatedObject(outline, &handlerKey, handler, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        outline.registerForDraggedTypes([
            NSPasteboard.PasteboardType(feedListFeedDragType.identifier),
            NSPasteboard.PasteboardType(feedListFolderDragType.identifier),
        ])
        return true
    }

    /// Whether `outline` is guarded: it carries a handler, and its class and its data source's class
    /// have the guard's methods.
    static func isGuarded(_ outline: NSOutlineView) -> Bool {
        guard handler(of: outline) != nil, let dataSource = outline.dataSource as? NSObject else { return false }
        return [outline as NSObject, dataSource].allSatisfy { guardedClasses.contains(ObjectIdentifier(type(of: $0))) }
    }

    /// Adds `addOverrides`' methods to `object`'s own class — the one it reports, not a KVO subclass
    /// it may be observed through (Swift's `type(of:)` skips those) — unless already added.
    private static func guardClass(of object: NSObject, adding addOverrides: (AnyClass) -> Bool) -> Bool {
        let cls: AnyClass = type(of: object)
        if guardedClasses.contains(ObjectIdentifier(cls)) { return true }
        guard addOverrides(cls) else {
            report("\(NSStringFromClass(cls)) lacks a method the guard overrides")
            return false
        }
        guardedClasses.insert(ObjectIdentifier(cls))
        return true
    }

    private static func handler(of outline: AnyObject) -> SidebarDropHandler? {
        objc_getAssociatedObject(outline, &handlerKey) as? SidebarDropHandler
    }

    private static func addOutlineOverrides(to cls: AnyClass) -> Bool {
        let perform = #selector(NSDraggingDestination.performDragOperation(_:))
        typealias Perform = @convention(c) (AnyObject, Selector, NSDraggingInfo) -> Bool
        let performOriginal = Original(perform, of: cls)
        let performBlock: @convention(block) (AnyObject, NSDraggingInfo) -> Bool = { receiver, info in
            // A feed or folder drop never reaches SwiftUI, even one the guard cannot place.
            if SidebarDropHandler.kind(of: info) != nil,
               let handler = MainActor.assumeIsolated({ handler(of: receiver) }) {
                return MainActor.assumeIsolated { handler.perform() }
            }
            return performOriginal.imp(as: Perform.self)?(receiver, perform, info) ?? false
        }

        let expand = #selector(NSOutlineView.expandItem(_:expandChildren:))
        typealias Expand = @convention(c) (AnyObject, Selector, AnyObject?, Bool) -> Void
        let expandOriginal = Original(expand, of: cls)
        let expandBlock: @convention(block) (AnyObject, AnyObject?, Bool) -> Void = { receiver, item, children in
            if MainActor.assumeIsolated({ handler(of: receiver)?.refusesExpansion ?? false }) { return }
            expandOriginal.imp(as: Expand.self)?(receiver, expand, item, children)
        }

        return install(performBlock, as: perform, in: cls, replacing: performOriginal)
            && install(expandBlock, as: expand, in: cls, replacing: expandOriginal)
            && addDragEnd(#selector(NSDraggingDestination.draggingExited(_:)), to: cls)
            && addDragEnd(#selector(NSDraggingDestination.draggingEnded(_:)), to: cls)
    }

    /// Ends the guard's drag when the drag leaves the outline or the session ends, then forwards to
    /// the original method, if there is one.
    private static func addDragEnd(_ selector: Selector, to cls: AnyClass) -> Bool {
        typealias End = @convention(c) (AnyObject, Selector, NSDraggingInfo?) -> Void
        let original = Original(selector, of: cls)
        let block: @convention(block) (AnyObject, NSDraggingInfo?) -> Void = { receiver, info in
            MainActor.assumeIsolated { handler(of: receiver)?.dragEnded() }
            original.imp(as: End.self)?(receiver, selector, info)
        }
        return install(block, as: selector, in: cls, replacing: original, encoding: "v@:@")
    }

    private static func addDataSourceOverrides(to cls: AnyClass) -> Bool {
        let validate = #selector(NSOutlineViewDataSource.outlineView(_:validateDrop:proposedItem:proposedChildIndex:))
        typealias Validate = @convention(c) (AnyObject, Selector, NSOutlineView, NSDraggingInfo, AnyObject?, Int) -> UInt
        let original = Original(validate, of: cls)
        let block: @convention(block) (AnyObject, NSOutlineView, NSDraggingInfo, AnyObject?, Int) -> UInt = {
            receiver, outline, info, item, childIndex in
            if let kind = SidebarDropHandler.kind(of: info),
               let handler = MainActor.assumeIsolated({ handler(of: outline) }) {
                return MainActor.assumeIsolated {
                    handler.validate(outline, dragging: kind, proposedItem: item, childIndex: childIndex).rawValue
                }
            }
            return original.imp(as: Validate.self)?(receiver, validate, outline, info, item, childIndex) ?? 0
        }
        return install(block, as: validate, in: cls, replacing: original)
    }

    /// What a guard method forwards to: the class's own implementation it replaced, or — where the
    /// class only inherits the method — the superclass's, looked up on every call as `super` would be,
    /// so a later change to it is followed.
    private final class Original: @unchecked Sendable {
        let selector: Selector
        let superclass: AnyClass?
        /// The type encoding of the method, wherever it is implemented; `nil` when nothing implements it.
        let encoding: UnsafePointer<CChar>?
        /// Set when the class implements the method itself and the guard replaces it.
        var replaced: IMP?

        init(_ selector: Selector, of cls: AnyClass) {
            self.selector = selector
            superclass = class_getSuperclass(cls)
            encoding = class_getInstanceMethod(cls, selector).flatMap(method_getTypeEncoding)
        }

        func imp<F>(as type: F.Type) -> F? {
            if let replaced { return unsafeBitCast(replaced, to: type) }
            guard let superclass, class_getInstanceMethod(superclass, selector) != nil else { return nil }
            return unsafeBitCast(class_getMethodImplementation(superclass, selector), to: type)
        }
    }

    /// Puts `block` in `cls` as its `selector`: added where `cls` only inherits the method, or
    /// replacing `cls`'s own implementation, which `original` then keeps to forward to. Fails where
    /// nothing implements the method and no `fallback` encoding is given.
    private static func install(
        _ block: Any,
        as selector: Selector,
        in cls: AnyClass,
        replacing original: Original,
        encoding fallback: String? = nil
    ) -> Bool {
        let imp = imp_implementationWithBlock(block)
        let added: Bool
        if let encoding = original.encoding {
            added = class_addMethod(cls, selector, imp, encoding)
        } else if let fallback {
            added = class_addMethod(cls, selector, imp, fallback)
        } else {
            return false
        }
        if added { return true }
        // `cls` implements the method itself: swap ours in and keep its own for forwarding.
        guard let method = class_getInstanceMethod(cls, selector) else { return false }
        original.replaced = method_setImplementation(method, imp)
        return true
    }

    /// Logs a failure to guard the sidebar once per message, as a fault: sidebar drag and drop does
    /// nothing until SwiftUI's internals are what the guard expects again.
    private static func report(_ message: String) {
        guard reportedFailures.insert(message).inserted else { return }
        logger.fault("Sidebar drag and drop is off: \(message, privacy: .public)")
    }
}
#endif
