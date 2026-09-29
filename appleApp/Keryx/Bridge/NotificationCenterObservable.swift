import KeryxShared
import Observation

/// Mirrors `NotificationCenter.items` (the bell icon's history) as an `@Observable` property.
@MainActor
@Observable
final class NotificationCenterObservable {
    let center: NotificationCenter

    private(set) var items: [AppNotification] = []

    init(center: NotificationCenter) {
        self.center = center
    }

    func startObserving() async {
        for await v in center.items { items = v }
    }

    func dismiss(id: String) { center.dismiss(id: id) }
    func dismissAll() { center.dismissAll() }
}
