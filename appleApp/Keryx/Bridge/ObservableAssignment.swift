/// An `@Observable` bridge whose properties are assigned only when their value actually changes.
///
/// `@Observable` invalidates every view reading a property on each write, equal value or not, and
/// a `StateFlow` mirrored into it re-delivers equal values often (a refresh re-emitting the same
/// unread counts, `selectFilter` assigning ahead of its own observer's echo). Conforming classes
/// write through `assignIfChanged` so those writes are skipped instead.
@MainActor
protocol ObservableAssignment: AnyObject {}

extension ObservableAssignment {
    /// Writes `value` to `keyPath` unless it already holds an equal value. Returns whether it wrote.
    @discardableResult
    func assignIfChanged<Value: Equatable>(_ keyPath: ReferenceWritableKeyPath<Self, Value>, _ value: Value) -> Bool {
        guard self[keyPath: keyPath] != value else { return false }
        self[keyPath: keyPath] = value
        return true
    }
}

/// Coalesces every `markDirty()` made before its scheduled run into one call of `action`, so several
/// invalidations landing back to back — the six sidebar-structure flows' first emissions at launch —
/// rebuild once instead of once each.
@MainActor
final class CoalescedAction {
    private var pending = false
    private let schedule: (@escaping @MainActor () -> Void) -> Void
    private let action: @MainActor () -> Void

    /// `schedule` defers its argument past the current MainActor job; the default enqueues it as a
    /// new MainActor task. Tests pass one that holds the closure until they run it.
    init(
        schedule: @escaping (@escaping @MainActor () -> Void) -> Void = { work in Task { @MainActor in work() } },
        action: @escaping @MainActor () -> Void
    ) {
        self.schedule = schedule
        self.action = action
    }

    var isPending: Bool { pending }

    func markDirty() {
        guard !pending else { return }
        pending = true
        schedule { [weak self] in self?.flush() }
    }

    /// Runs a pending `action` now rather than at its scheduled time (a no-op when nothing is
    /// pending), for a caller that must read the rebuilt state synchronously.
    func flush() {
        guard pending else { return }
        pending = false
        action()
    }
}
