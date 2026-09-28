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

    var body: some View {
        TabView {
            GeneralSettingsTab(preferences: preferences)
                .tabItem { Text(L("settings_tab_general")) }

            NotificationsSettingsTab(preferences: preferences)
                .tabItem { Text(L("settings_tab_notifications")) }

            CloudSyncSettingsTab(oauthCoordinator: oauthCoordinator, cloudSync: cloudSync)
                .tabItem { Text(L("settings_cloud_sync")) }

            DataSettingsTab(preferences: preferences, opmlTransfer: opmlTransfer)
                .tabItem { Text(L("settings_tab_data")) }
        }
        .frame(minWidth: 520, minHeight: 420)
        .task { await preferences.startObserving() }
        .task { await cloudSync.startObserving() }
    }
}
