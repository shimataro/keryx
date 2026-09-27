import KeryxShared
import SwiftUI

struct CloudSyncSettingsTab: View {
    let oauthCoordinator: OAuthSessionCoordinator
    let cloudSync: CloudSyncObservable

    @State private var confirmDisconnect: CloudStorageType?
    @State private var confirmReset = false
    @State private var confirmAbort: CloudStorageType?
    @State private var confirmSwitch: CloudStorageType?

    var body: some View {
        Form {
            Text(L("settings_cloud_sync_hint"))
                .font(.caption)
                .foregroundStyle(.secondary)

            ForEach(cloudSync.availableCloudTypes, id: \.self) { type in
                row(for: type)
            }
        }
        .padding()
        .alert(
            confirmDisconnect.map { LF("settings_cloud_disconnect_confirm_title", L(titleKey($0))) } ?? "",
            isPresented: Binding(get: { confirmDisconnect != nil }, set: { if !$0 { confirmDisconnect = nil } })
        ) {
            Button(L("common_delete"), role: .destructive) { cloudSync.controller.disconnect() }
            Button(L("common_cancel"), role: .cancel) {}
        } message: {
            Text(L("settings_cloud_disconnect_confirm_body"))
        }
        .alert(
            L("settings_cloud_reset_confirm_title"),
            isPresented: $confirmReset
        ) {
            Button(L("settings_cloud_reset_confirm_action"), role: .destructive) { cloudSync.controller.resetCloudData() }
            Button(L("common_cancel"), role: .cancel) {}
        } message: {
            Text(L("settings_cloud_reset_confirm_body"))
        }
        .alert(
            confirmAbort.map { LF("settings_cloud_abort_connect_confirm_title", L(titleKey($0))) } ?? "",
            isPresented: Binding(get: { confirmAbort != nil }, set: { if !$0 { confirmAbort = nil } })
        ) {
            Button(L("common_abort"), role: .destructive) {
                cloudSync.controller.cancelConnect()
                oauthCoordinator.cancelActiveSession()
            }
            Button(L("common_cancel"), role: .cancel) {}
        } message: {
            Text(L("settings_cloud_abort_connect_confirm_body"))
        }
        .alert(
            confirmSwitch.map { LF("settings_cloud_switch_confirm_title", L(titleKey($0))) } ?? "",
            isPresented: Binding(get: { confirmSwitch != nil }, set: { if !$0 { confirmSwitch = nil } })
        ) {
            Button(L("settings_cloud_switch_confirm_action")) {
                if let type = confirmSwitch { cloudSync.controller.switchTo(newType: type) }
            }
            Button(L("common_cancel"), role: .cancel) {}
        } message: {
            Text(confirmSwitch.map { LF("settings_cloud_switch_confirm_body", L(titleKey($0))) } ?? "")
        }
    }

    @ViewBuilder
    private func row(for type: CloudStorageType) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack {
                Text(L(titleKey(type))).font(.headline)
                Spacer()
                actionButton(for: type)
            }

            if cloudSync.connectingType == type {
                HStack(spacing: 6) {
                    ProgressView()
                    Text(L("setup_connecting"))
                    if cloudSync.canCancelConnect {
                        Button(L("common_cancel")) { confirmAbort = type }
                    }
                }
            } else if cloudSync.initialSyncingType == type {
                HStack(spacing: 6) {
                    ProgressView()
                    Text(L("settings_cloud_syncing"))
                }
            } else if cloudSync.connectedType == type {
                if cloudSync.syncing {
                    Text(syncPhaseText)
                        .font(.caption)
                        .foregroundStyle(.secondary)
                } else if let lastSynced = cloudSync.lastSyncedAtText {
                    Text(LF("settings_last_synced", lastSynced))
                        .font(.caption)
                        .foregroundStyle(.secondary)
                }
                if let error = cloudSync.lastSyncError {
                    Text(errorKindMessage(error))
                        .font(.caption)
                        .foregroundStyle(.red)
                }
                if cloudSync.disconnecting {
                    Text(L("settings_cloud_disconnecting")).font(.caption).foregroundStyle(.secondary)
                }
                HStack {
                    Button(L("home_sync")) { cloudSync.controller.syncNow() }
                        .disabled(!cloudSync.canSyncNow)
                    if cloudSync.lastSyncAuthFailed {
                        Button(L("settings_cloud_reconnect")) { cloudSync.controller.reconnect() }
                    }
                    Button(L("settings_cloud_reset")) { confirmReset = true }
                        .disabled(cloudSync.resetting)
                }
            }
        }
        .padding(.vertical, 6)
    }

    @ViewBuilder
    private func actionButton(for type: CloudStorageType) -> some View {
        if cloudSync.connectedType == type {
            Button(L(disconnectKey(type))) { confirmDisconnect = type }
        } else if cloudSync.connectingType == nil && cloudSync.initialSyncingType == nil {
            Button(L(connectKey(type))) {
                if cloudSync.connectedType != nil {
                    confirmSwitch = type
                } else {
                    cloudSync.controller.connect(type: type)
                }
            }
        }
    }

    private var syncPhaseText: String {
        switch cloudSync.syncPhase {
        case .idle: return ""
        case .checking: return L("settings_cloud_phase_checking")
        case .downloading: return L("settings_cloud_phase_downloading")
        case .merging: return L("settings_cloud_phase_merging")
        case .indexing: return L("settings_cloud_phase_indexing")
        case .preparing: return L("settings_cloud_phase_preparing")
        case .uploading: return L("settings_cloud_phase_uploading")
        case .archiving: return L("settings_cloud_phase_archiving")
        }
    }


    private func titleKey(_ type: CloudStorageType) -> String {
        switch type {
        case .dropbox: return "setup_dropbox"
        case .googleDrive: return "setup_google_drive"
        case .onedrive: return "setup_onedrive"
        }
    }

    private func connectKey(_ type: CloudStorageType) -> String {
        switch type {
        case .dropbox: return "settings_dropbox_connect"
        case .googleDrive: return "settings_google_drive_connect"
        case .onedrive: return "settings_onedrive_connect"
        }
    }

    private func disconnectKey(_ type: CloudStorageType) -> String {
        switch type {
        case .dropbox: return "settings_dropbox_disconnect"
        case .googleDrive: return "settings_google_drive_disconnect"
        case .onedrive: return "settings_onedrive_disconnect"
        }
    }
}
