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
    let settingsNavigation: SettingsNavigation

    var body: some View {
        TabView(selection: Binding(
            get: {
                SettingsNavigation.visibleTab(
                    settingsNavigation.selectedTab,
                    cloudSyncAvailable: !cloudSync.availableCloudTypes.isEmpty
                )
            },
            set: { settingsNavigation.selectedTab = $0 }
        )) {
            GeneralSettingsTab(preferences: preferences)
                .tabItem { Label(L("settings_tab_general"), systemImage: "gearshape") }
                .tag(SettingsNavigation.Tab.general)

            NotificationsSettingsTab(preferences: preferences)
                .tabItem { Label(L("settings_tab_notifications"), systemImage: "bell") }
                .tag(SettingsNavigation.Tab.notifications)

            // Only shown when at least one cloud provider is actually configured in this build —
            // matches Compose's own `SettingsDialog.kt`, which never adds this tab otherwise.
            if !cloudSync.availableCloudTypes.isEmpty {
                CloudSyncSettingsTab(oauthCoordinator: oauthCoordinator, cloudSync: cloudSync)
                    .tabItem { Label(L("settings_cloud_sync"), systemImage: "cloud") }
                    .tag(SettingsNavigation.Tab.cloudSync)
            }

            DataSettingsTab(preferences: preferences, opmlTransfer: opmlTransfer)
                .tabItem { Label(L("settings_tab_data"), systemImage: "externaldrive") }
                .tag(SettingsNavigation.Tab.data)
        }
        // A `Form` defaults to checkboxes on macOS; the Compose dialog uses switches (`SwitchRow`).
        .toggleStyle(.switch)
        // Width only: the Settings window then takes each tab's own height, resizing as tabs switch.
        .frame(width: 520)
        .task { await preferences.startObserving() }
        .task { await cloudSync.startObserving() }
    }
}
