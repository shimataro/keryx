import KeryxShared
import SwiftUI

/// The Settings window (macOS `Settings` scene, reached via Cmd+, / the app menu) — General,
/// Notifications, Cloud Sync, Data. See `docs/db-schema.md`'s `local_settings.json` for the
/// backing fields and `SyncPhase/ErrorKind` for the cloud-sync tab's own vocabulary.
struct SettingsView: View {
    let sdk: KeryxSdk
    let preferences: PreferencesObservable
    let cloudSync: CloudSyncObservable
    let oauthCoordinator: OAuthSessionCoordinator
    let opmlTransfer: OpmlTransferObservable
    let notifications: NotificationCenterObservable

    @State private var selectedTab = "general"

    var body: some View {
        TabView(selection: $selectedTab) {
            GeneralSettingsTab(preferences: preferences)
                .tabItem { Text(L("settings_tab_general")) }
                .tag("general")

            NotificationsSettingsTab(preferences: preferences)
                .tabItem { Text(L("settings_tab_notifications")) }
                .tag("notifications")

            CloudSyncSettingsTab(oauthCoordinator: oauthCoordinator, cloudSync: cloudSync)
                .tabItem { Text(L("settings_cloud_sync")) }
                .tag("cloud_sync")

            DataSettingsTab(preferences: preferences, opmlTransfer: opmlTransfer)
                .tabItem { Text(L("settings_tab_data")) }
                .tag("data")
        }
        .frame(minWidth: 520, minHeight: 420)
        .task { await preferences.startObserving() }
        .task { await cloudSync.startObserving() }
        // A bell-row "show settings tab" action (`NotificationBell.swift`) navigates here even
        // while Settings is already open, on whichever tab id its own row named — matches Compose's
        // own re-navigating `tabRequestToken` (`SettingsDialog.kt`). "updates" has no tab in this
        // app (no in-app updater; see `docs/app-architecture.md`'s "Apple targets in `:shared`"), so
        // it falls back to "cloud_sync", which shows the same sync failure that action is raised
        // for (`SchemaVersionException`).
        .onChange(of: notifications.settingsTabRequest) { _, request in
            guard let request else { return }
            selectedTab = request.tabId == "updates" ? "cloud_sync" : request.tabId
        }
    }
}
