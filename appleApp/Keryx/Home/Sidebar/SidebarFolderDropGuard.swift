#if os(macOS)
import AppKit
import ObjectiveC
import SwiftUI

/// Takes a folder dropped between folders before SwiftUI's `List` handles it, because SwiftUI
/// crashes on it.
///
/// The sidebar's `List` is an `NSOutlineView` underneath, whose data source is SwiftUI's own
/// `OutlineListCoordinator`. Dropping a folder between folders reaches its
/// `outlineView(_:acceptDrop:item:childIndex:)`, whose `performDropInsertion` then traps on an
/// out-of-range index (`HeterogeneousCollection.element(at:)`, macOS 26) — whenever the folder rows
/// carry a `DisclosureGroup`, a `.badge`, a context menu or an `.onDrop`, all of which these rows need.
/// That is a bug in SwiftUI; see "macOS: dropping a folder between folders crashed inside SwiftUI"
/// in `docs/known-issues.md`.
///
/// So this outline instance alone is given a runtime subclass — the way KVO does it — whose
/// `performDragOperation:` applies a drop carrying a folder itself and never passes it on.
/// Everything else stays SwiftUI's: validating the drop and drawing the insertion line (the folders'
/// `.onInsert` still drives both), and every drop that does not carry a folder. Where the folder
/// lands is read from the row under the pointer (`feedListFolderGuardOffset`), and the move goes
/// through the same `insert(into:at:)` path the `.onInsert` would have taken.
///
/// This relies on SwiftUI's internals — that the `List` is an `NSOutlineView` whose root items are
/// the `List`'s sections — so wherever that does not hold, the guard does nothing for the drop
/// rather than guess: the drop is refused, never passed to SwiftUI.
struct SidebarFolderDropGuard: NSViewRepresentable {
    /// How many folders the Folders section shows.
    let folderCount: Int
    /// Moves the dragged folder to `offset` among the folders.
    let insertFolder: (Int) -> Void

    func makeNSView(context: Context) -> NSView {
        GuardAnchorView()
    }

    func updateNSView(_ view: NSView, context: Context) {
        guard let anchor = view as? GuardAnchorView else { return }
        anchor.handler.folderCount = folderCount
        anchor.handler.insertFolder = insertFolder
        anchor.installWhenInHierarchy()
    }
}

/// The current values a guarded outline applies a folder drop with; refreshed on every update.
@MainActor
final class SidebarFolderDropHandler: NSObject {
    var folderCount = 0
    var insertFolder: (Int) -> Void = { _ in }

    /// Applies the folder drop described by `info`. Always handles it — a drop it cannot place is
    /// refused (returns `false`) rather than handed to SwiftUI.
    func performFolderDrop(on outline: NSOutlineView, _ info: NSDraggingInfo) -> Bool {
        let point = outline.convert(info.draggingLocation, from: nil)
        let row = outline.row(at: point)
        guard row >= 0 else { return false }
        // The outline is flipped: a larger y is lower in the row.
        let bottomHalf = point.y > outline.rect(ofRow: row).midY
        let dropRow = SidebarFolderDropGuardOutline(outline, folderCount: folderCount).classify(row: row)
        guard let offset = feedListFolderGuardOffset(row: dropRow, bottomHalf: bottomHalf, folderCount: folderCount)
        else { return false }
        insertFolder(offset)
        return true
    }
}

/// Reads the `List`'s outline: every section is a root item (the headerless All/Starred one first),
/// so the Folders section is the second root item whenever there are folders.
@MainActor
private struct SidebarFolderDropGuardOutline {
    let outline: NSOutlineView
    let foldersSection: AnyObject?

    init(_ outline: NSOutlineView, folderCount: Int) {
        self.outline = outline
        let rootCount = outline.numberOfChildren(ofItem: nil)
        if folderCount > 0, rootCount > 1 {
            let candidate = outline.child(1, ofItem: nil) as AnyObject
            foldersSection = outline.numberOfChildren(ofItem: candidate) == folderCount ? candidate : nil
        } else {
            foldersSection = nil
        }
    }

    func classify(row: Int) -> FeedListFolderDropRow {
        guard let foldersSection, let item = outline.item(atRow: row) as AnyObject? else { return .other }
        if item === foldersSection { return .foldersHeader }
        let parent = outline.parent(forItem: item) as AnyObject?
        if parent === foldersSection { return .folder(index: outline.childIndex(forItem: item)) }
        if let parent, outline.parent(forItem: parent) as AnyObject? === foldersSection {
            return .feedInFolder(folderIndex: outline.childIndex(forItem: parent))
        }
        if parent == nil, outline.childIndex(forItem: item) == outline.childIndex(forItem: foldersSection) + 1 {
            return .afterFolders
        }
        return .other
    }
}

/// A zero-size view behind the `List` that finds the outline it sits in and guards it once.
private final class GuardAnchorView: NSView {
    @MainActor let handler = SidebarFolderDropHandler()

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        installWhenInHierarchy()
    }

    func installWhenInHierarchy() {
        // SwiftUI may add the outline after this view, so look again on the next turn.
        DispatchQueue.main.async { [weak self] in
            guard let self, self.window != nil, let outline = self.nearestOutline() else { return }
            SidebarFolderDropGuardInstaller.install(on: outline, handler: self.handler)
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

/// Creates the runtime subclass and swaps one outline instance onto it.
@MainActor
enum SidebarFolderDropGuardInstaller {
    private static let subclassPrefix = "KeryxFolderDropGuarded_"
    private static var handlerKey: UInt8 = 0

    static func install(on outline: NSOutlineView, handler: SidebarFolderDropHandler) {
        objc_setAssociatedObject(outline, &handlerKey, handler, .OBJC_ASSOCIATION_RETAIN_NONATOMIC)
        guard let current = object_getClass(outline) else { return }
        let currentName = NSStringFromClass(current)
        if currentName.hasPrefix(subclassPrefix) { return }
        // Someone else (KVO, say) has already swapped this instance onto a class of its own and hides
        // it behind `-class`. Subclassing theirs would break their restoring the instance later, so
        // leave the outline as it is — no worse than having no guard.
        guard currentName == outline.className else { return }
        guard let guarded = guardedSubclass(of: current) else { return }
        object_setClass(outline, guarded)
    }

    private static func guardedSubclass(of base: AnyClass) -> AnyClass? {
        let name = subclassPrefix + NSStringFromClass(base)
        if let existing = NSClassFromString(name) { return existing }
        let selector = #selector(NSDraggingDestination.performDragOperation(_:))
        let classSelector = NSSelectorFromString("class")
        guard let method = class_getInstanceMethod(base, selector),
              let classMethod = class_getInstanceMethod(base, classSelector),
              let subclass = objc_allocateClassPair(base, name, 0) else { return nil }
        typealias PerformDrag = @convention(c) (AnyObject, Selector, NSDraggingInfo) -> Bool
        let folderType = NSPasteboard.PasteboardType(feedListFolderDragType.identifier)
        let override: @convention(block) (AnyObject, NSDraggingInfo) -> Bool = { receiver, info in
            guard info.draggingPasteboard.types?.contains(folderType) == true,
                  let outline = receiver as? NSOutlineView else {
                // Looked up on every call, as `super` would be, so a later change to the base
                // class's method is followed rather than the one captured here.
                let inherited = unsafeBitCast(class_getMethodImplementation(base, selector), to: PerformDrag.self)
                return inherited(receiver, selector, info)
            }
            // A folder drop never reaches SwiftUI, even one the handler cannot place.
            guard let handler = objc_getAssociatedObject(outline, &handlerKey) as? SidebarFolderDropHandler else {
                return false
            }
            return MainActor.assumeIsolated { handler.performFolderDrop(on: outline, info) }
        }
        class_addMethod(subclass, selector, imp_implementationWithBlock(override), method_getTypeEncoding(method))
        // Report the original class, as KVO does, so `-class`, `isMemberOfClass:` and `className`
        // see the outline exactly as before.
        let reportBase: @convention(block) (AnyObject) -> AnyClass = { _ in base }
        class_addMethod(
            subclass, classSelector, imp_implementationWithBlock(reportBase), method_getTypeEncoding(classMethod)
        )
        objc_registerClassPair(subclass)
        return subclass
    }
}
#endif
