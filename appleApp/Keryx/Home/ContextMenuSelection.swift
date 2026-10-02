import SwiftUI

#if os(macOS)
import AppKit

/// Tracks which sidebar/article row is currently under the pointer, and selects it the moment a
/// right-click or Control-click actually occurs — not merely when SwiftUI evaluates the row's
/// `.contextMenu` builder. `contextMenu(menuItems:)` takes a non-escaping closure, so anything run
/// inside it (previously a bare `let _ = selectForContextMenu(...)`) executes synchronously as part
/// of constructing the row's View *value* — on every body re-evaluation, not only when the menu is
/// actually requested. A local `NSEvent` monitor is the only reliable way to learn "the menu is
/// really about to open" before SwiftUI opens it.
/// Not `@MainActor`-isolated (an `EnvironmentKey.defaultValue` must be constructible in a
/// nonisolated context) — every call site is SwiftUI view code on the main thread regardless
/// (`.onHover`, `.onAppear`/`.onDisappear`), and the `NSEvent` local-monitor handler itself always
/// runs on the main run loop.
final class ContextMenuSelectionTracker {
    private var hoveredId: AnyHashable?
    private var hoveredSelect: (() -> Void)?
    private var monitor: Any?

    func hoverEntered(_ id: AnyHashable, select: @escaping () -> Void) {
        hoveredId = id
        hoveredSelect = select
    }

    func hoverExited(_ id: AnyHashable) {
        guard hoveredId == id else { return }
        hoveredId = nil
        hoveredSelect = nil
    }

    /// The hovered view now draws another row without the pointer having left it — a hosted article
    /// cell reused for another article (`ArticleTableView`). Re-registers the new row so a
    /// right-click selects the row actually drawn under the pointer, not the one the cell showed
    /// before.
    func hoveredRowChanged(from oldId: AnyHashable, to newId: AnyHashable, select: @escaping () -> Void) {
        hoverExited(oldId)
        hoverEntered(newId, select: select)
    }

    /// What a right-click/Control-click does: selects the hovered row, if any. Called by the event
    /// monitor; separate from it so the hover bookkeeping can be tested without a real event.
    func contextClicked() {
        hoveredSelect?()
    }

    /// Started once by `HomeView` for as long as it's on screen. Never consumes the event — the
    /// click still reaches SwiftUI afterward, which is what actually opens the context menu.
    func startMonitoring() {
        guard monitor == nil else { return }
        monitor = NSEvent.addLocalMonitorForEvents(matching: [.rightMouseDown, .leftMouseDown]) { [weak self] event in
            guard let self else { return event }
            let isContextClick = event.type == .rightMouseDown
                || (event.type == .leftMouseDown && event.modifierFlags.contains(.control))
            if isContextClick {
                contextClicked()
            }
            return event
        }
    }

    func stopMonitoring() {
        if let monitor {
            NSEvent.removeMonitor(monitor)
        }
        monitor = nil
    }
}

private struct ContextMenuSelectionTrackerKey: EnvironmentKey {
    // A computed property (not a stored `static let`) — `HomeView` always overrides this via
    // `.environment(...)` with its own instance, so this default is never actually exercised, and a
    // per-access instance sidesteps Swift 6's "non-Sendable mutable global state" diagnostic that a
    // shared `static let` of a non-`Sendable` class would trigger here.
    static var defaultValue: ContextMenuSelectionTracker { ContextMenuSelectionTracker() }
}

extension EnvironmentValues {
    var contextMenuSelectionTracker: ContextMenuSelectionTracker {
        get { self[ContextMenuSelectionTrackerKey.self] }
        set { self[ContextMenuSelectionTrackerKey.self] = newValue }
    }
}

extension View {
    /// Selects a row when it's right-clicked/Control-clicked while hovered, matching Compose's own
    /// `onOpen = { if (!selected) onClick() }` — see `ContextMenuSelectionTracker`'s own doc for why
    /// this can't simply be a side effect inside `.contextMenu`'s builder.
    ///
    /// This is the one place a row's hover is tracked: `pointerIsOver`, when given, mirrors exactly
    /// the hover the tracker selects from, so a row that predicts its right-click's selection from it
    /// (`ArticleRowView`) always predicts it for the row the right-click will select.
    func selectsOnContextMenu(
        id: AnyHashable,
        pointerIsOver: Binding<Bool>? = nil,
        perform select: @escaping () -> Void
    ) -> some View {
        modifier(SelectsOnContextMenuModifier(id: id, pointerIsOver: pointerIsOver, select: select))
    }
}

private struct SelectsOnContextMenuModifier: ViewModifier {
    @Environment(\.contextMenuSelectionTracker) private var tracker
    let id: AnyHashable
    let pointerIsOver: Binding<Bool>?
    let select: () -> Void
    @State private var hovering = false

    func body(content: Content) -> some View {
        content
            .onHover { isOver in
                setHovering(isOver)
                if isOver {
                    tracker.hoverEntered(id, select: select)
                } else {
                    tracker.hoverExited(id)
                }
            }
            // A hosted article cell is reused for another article without the pointer necessarily
            // leaving it, so no hover event arrives: the tracker is moved to the new row (with the
            // new row's own select closure — this action is from the current body), and the
            // binding stays true, since the pointer is still over the row now drawn.
            .onChange(of: id) { oldId, newId in
                guard hovering else { return }
                tracker.hoveredRowChanged(from: oldId, to: newId, select: select)
                pointerIsOver?.wrappedValue = true
            }
            // A row removed from under the pointer gets no hover-exit either.
            .onDisappear {
                if hovering { tracker.hoverExited(id) }
                setHovering(false)
            }
    }

    private func setHovering(_ isOver: Bool) {
        hovering = isOver
        pointerIsOver?.wrappedValue = isOver
    }
}
#else
extension View {
    /// No-op on iOS: there is no right-click, and the long-press gesture that would open a context
    /// menu there is deferred to the iOS port (`external-spec.md`'s "iOS / iPadOS: Planned").
    /// `pointerIsOver` is never set: iOS has no hover that selects anything.
    func selectsOnContextMenu(
        id: AnyHashable,
        pointerIsOver: Binding<Bool>? = nil,
        perform select: @escaping () -> Void
    ) -> some View {
        self
    }
}
#endif
