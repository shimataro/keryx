import KeryxShared
import Observation

/// Mirrors `NotificationCenter.items` (the notification center's history) as an `@Observable`
/// property, and carries the shared `NotificationAlerts` that decides which newly raised
/// warning/error still needs announcing (iOS's alert toast — see `AlertToastAnnouncement`).
@MainActor
@Observable
final class NotificationCenterObservable {
    let center: NotificationCenter
    @ObservationIgnored let alerts: NotificationAlerts

    private(set) var items: [AppNotification] = []

    init(center: NotificationCenter, alerts: NotificationAlerts) {
        self.center = center
        self.alerts = alerts
    }

    func startObserving() async {
        for await v in center.items { items = v }
    }

    func dismiss(id: String) { center.dismiss(id: id) }
    func dismissAll() { center.dismissAll() }
}
