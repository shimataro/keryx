import KeryxShared
import SwiftUI

/// The Settings window (macOS `Settings` scene, reached via Cmd+, / the app menu) — General,
/// Notifications, Cloud Sync, Data. On iOS, a sheet over the main window, opened from the sidebar's
/// gear button (`KeryxApp`). See `docs/db-schema.md`'s `local_settings.json` for the
/// backing fields and `SyncPhase/ErrorKind` for the cloud-sync tab's own vocabulary.
struct SettingsView: View {
    let sdk: KeryxSdk
    let preferences: PreferencesObservable
    let cloudSync: CloudSyncObservable
    let oauthCoordinator: OAuthSessionCoordinator
    let opmlTransfer: OpmlTransferObservable
    let settingsNavigation: SettingsNavigation

    #if os(iOS)
    /// The pushed tab, if any — seeded from `settingsNavigation.selectedTab` each time the sheet opens.
    @State private var path: [String] = []

    /// iOS's own Settings shape: a list of the sections, each pushed as a page of its own, in a sheet
    /// closed by Done — rather than macOS's tabbed window.
    var body: some View {
        let cloudSyncAvailable = !cloudSync.availableCloudTypes.isEmpty
        NavigationStack(path: $path) {
            List {
                sectionLink(SettingsNavigation.Tab.general, titleKey: "settings_tab_general", systemImage: "gearshape")
                sectionLink(SettingsNavigation.Tab.notifications, titleKey: "settings_tab_notifications", systemImage: "bell")
                if cloudSyncAvailable {
                    sectionLink(SettingsNavigation.Tab.cloudSync, titleKey: "settings_cloud_sync", systemImage: "cloud")
                }
                sectionLink(SettingsNavigation.Tab.data, titleKey: "settings_tab_data", systemImage: "externaldrive")
            }
            .navigationTitle(L("settings_title"))
            .navigationBarTitleDisplayMode(.inline)
            .navigationDestination(for: String.self) { tab in
                tabContent(tab)
            }
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("apple_settings_done")) { settingsNavigation.isSheetPresented = false }
                }
            }
        }
        .onAppear {
            path = SettingsNavigation.initialPath(settingsNavigation.selectedTab, cloudSyncAvailable: cloudSyncAvailable)
        }
        .task { await preferences.startObserving() }
        .task { await cloudSync.startObserving() }
    }

    private func sectionLink(_ tab: String, titleKey: String, systemImage: String) -> some View {
        NavigationLink(value: tab) {
            Label(L(titleKey), systemImage: systemImage)
        }
    }

    @ViewBuilder
    private func tabContent(_ tab: String) -> some View {
        switch tab {
        case SettingsNavigation.Tab.notifications:
            NotificationsSettingsTab(preferences: preferences)
                .navigationTitle(L("settings_tab_notifications"))
        case SettingsNavigation.Tab.cloudSync:
            CloudSyncSettingsTab(oauthCoordinator: oauthCoordinator, cloudSync: cloudSync)
                .navigationTitle(L("settings_cloud_sync"))
        case SettingsNavigation.Tab.data:
            DataSettingsTab(preferences: preferences, opmlTransfer: opmlTransfer)
                .navigationTitle(L("settings_tab_data"))
        default:
            GeneralSettingsTab(preferences: preferences)
                .navigationTitle(L("settings_tab_general"))
        }
    }
    #else
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
    #endif
}
