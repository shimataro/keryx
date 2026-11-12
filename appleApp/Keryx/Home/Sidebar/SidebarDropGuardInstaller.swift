#if os(macOS)
import AppKit
import ObjectiveC
import OSLog

/// Adds the guard's methods to the classes of the outline and its data source — SwiftUI's own
/// `SwiftUIOutlineListView` and `OutlineListCoordinator` — once each, and attaches a handler to the
/// sidebar's outline (see `SidebarDropGuard`). The methods act only on an outline carrying a handler,
/// and only on a feed or folder drag; for everything else they forward to the original
/// implementation. Adding to the class rather than swapping the instance onto a subclass of its own
/// keeps the guard working whether KVO (which swaps the instance onto a subclass, e.g. for
/// accessibility) observes it first or later.
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

    /// The handler attached to `outline`, if it is the guarded sidebar's.
    static func handler(of outline: AnyObject) -> SidebarDropHandler? {
        objc_getAssociatedObject(outline, &handlerKey) as? SidebarDropHandler
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

    private static func addOutlineOverrides(to cls: AnyClass) -> Bool {
        let perform = #selector(NSDraggingDestination.performDragOperation(_:))
        typealias Perform = @convention(c) (AnyObject, Selector, NSDraggingInfo) -> Bool
        let performOriginal = Original(perform, of: cls)
        let performBlock: @convention(block) (AnyObject, NSDraggingInfo) -> Bool = { receiver, info in
            // A feed or folder drop never reaches SwiftUI, even one the guard cannot place.
            let handled: Bool? = MainActor.assumeIsolated {
                guard SidebarDropHandler.kind(of: info) != nil, let outline = receiver as? NSOutlineView,
                      let handler = handler(of: outline) else { return nil }
                return handler.perform()
            }
            return handled ?? performOriginal.imp(as: Perform.self)?(receiver, perform, info) ?? false
        }

        let expand = #selector(NSOutlineView.expandItem(_:))
        typealias Expand = @convention(c) (AnyObject, Selector, AnyObject?) -> Void
        let expandOriginal = Original(expand, of: cls)
        let expandBlock: @convention(block) (AnyObject, AnyObject?) -> Void = { receiver, item in
            let refused = MainActor.assumeIsolated {
                guard let outline = receiver as? NSOutlineView, let handler = handler(of: outline) else { return false }
                return handler.refusesExpansion(of: item, in: outline)
            }
            if refused { return }
            expandOriginal.imp(as: Expand.self)?(receiver, expand, item)
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
            let operation: NSDragOperation? = MainActor.assumeIsolated {
                guard let kind = SidebarDropHandler.kind(of: info), let handler = handler(of: outline) else { return nil }
                // Only a drag from this app is the sidebar's own (AppKit gives another app's drag no
                // source). The item a drop resolves to is the one the last drag here started with, which
                // a cancelled drag leaves behind, so another app's drag that merely names our types
                // would move or tag that feed. Refused here, not handed to SwiftUI either.
                guard info.draggingSource != nil else { return [] }
                return handler.validate(outline, dragging: kind, proposedItem: item, childIndex: childIndex)
            }
            return operation?.rawValue ?? original.imp(as: Validate.self)?(receiver, validate, outline, info, item, childIndex) ?? 0
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
