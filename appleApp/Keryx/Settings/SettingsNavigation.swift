import Observation

/// Which Settings tab is selected — owned by `AppModel` rather than by `SettingsView` itself, so a
/// bell-row `ShowSettingsTab` action (`NotificationBell.swift`) can pick the tab *before* the
/// Settings window exists. A request made while that window was closed would otherwise be lost: a
/// view created after the request has no change to observe, and would open on its own default tab.
/// Matches Compose's own `SettingsDialog.kt`, which opens on the requested `initialTab`.
@MainActor
@Observable
final class SettingsNavigation {
    /// The tab ids `SettingsView` tags its tabs with — the same ids `AppNotification.kt`'s own
    /// `AppNotificationAction.ShowSettingsTab` carries.
    enum Tab {
        static let general = "general"
        static let notifications = "notifications"
        static let cloudSync = "cloud_sync"
        static let data = "data"
        /// Raised by the shared code (`SchemaVersionException`, a new app version) but has no tab
        /// in this app (no in-app updater; see `docs/app-architecture.md`'s "Apple targets in
        /// `:shared`").
        static let updates = "updates"
    }

    var selectedTab = Tab.general

    /// Whether the iOS Settings sheet is shown — iOS has no `Settings` scene, so Settings is a sheet
    /// over the main window there (`KeryxApp`). Unused on macOS.
    var isSheetPresented = false

    /// Selects the tab a notification asked for. "updates" falls back to Cloud Sync, which shows
    /// the same sync failure that action is raised for (`SchemaVersionException`).
    func show(tabId: String) {
        selectedTab = tabId == Tab.updates ? Tab.cloudSync : tabId
    }

    /// The tab `SettingsView` actually shows for `tab`: Cloud Sync only exists when at least one
    /// provider is configured in this build, and an unknown id has no tab at all — both land on
    /// General instead of selecting a tab that isn't there.
    static func visibleTab(_ tab: String, cloudSyncAvailable: Bool) -> String {
        switch tab {
        case Tab.general, Tab.notifications, Tab.data:
            return tab
        case Tab.cloudSync where cloudSyncAvailable:
            return tab
        default:
            return Tab.general
        }
    }

    /// The navigation path the iOS Settings sheet opens with: its tab list alone for General,
    /// otherwise that tab pushed onto it — so a bell-row `ShowSettingsTab` lands directly on the
    /// requested tab rather than on the list.
    static func initialPath(_ tab: String, cloudSyncAvailable: Bool) -> [String] {
        let visible = visibleTab(tab, cloudSyncAvailable: cloudSyncAvailable)
        return visible == Tab.general ? [] : [visible]
    }
}
