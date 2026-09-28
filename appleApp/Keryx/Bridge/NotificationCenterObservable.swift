import KeryxShared
import Observation

/// Mirrors `NotificationCenter.items` (the bell icon's history) as an `@Observable` property.
@MainActor
@Observable
final class NotificationCenterObservable {
    let center: NotificationCenter

    private(set) var items: [AppNotification] = []

    /// A `ShowSettingsTab` action's target tab id ("general"/"cloud_sync"/"updates" — see
    /// `AppNotification.kt`'s own `AppNotificationAction.ShowSettingsTab`), plus a token that bumps
    /// on every request so `SettingsView`'s own `.onChange` re-navigates even to the tab it's
    /// already on — matching Compose's own `tabRequestToken` (`SettingsDialog.kt`), since a plain
    /// value change wouldn't otherwise fire twice for the same tab id.
    private(set) var settingsTabRequest: SettingsTabRequest?

    init(center: NotificationCenter) {
        self.center = center
    }

    func startObserving() async {
        for await v in center.items { items = v }
    }

    func dismiss(id: String) { center.dismiss(id: id) }
    func dismissAll() { center.dismissAll() }

    func requestSettingsTab(_ tabId: String) {
        settingsTabRequest = SettingsTabRequest(tabId: tabId, token: (settingsTabRequest?.token ?? 0) + 1)
    }
}

struct SettingsTabRequest: Equatable {
    let tabId: String
    let token: Int
}
