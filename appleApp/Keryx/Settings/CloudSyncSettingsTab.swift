import KeryxShared
import SwiftUI

/// Cloud sync tab: provider connect/disconnect/switch, with the four confirmation dialogs, and a
/// standing "Sync Now" button — mirrors Compose's own `CloudSyncTabContent`/`CloudProviderRow`
/// (`CloudSyncTab.kt`). The providers share one `GroupBox`, the counterpart of Compose's own
/// `SettingsCard` around its provider rows.
struct CloudSyncSettingsTab: View {
    let oauthCoordinator: OAuthSessionCoordinator
    let cloudSync: CloudSyncObservable

    @State private var confirmDisconnect: CloudStorageType?
    @State private var confirmReset = false
    @State private var confirmAbort: CloudStorageType?
    @State private var confirmSwitch: CloudStorageType?

    /// Gates "reset"/"switch provider"/"reconnect" — each would race a connect/initial-sync/
    /// disconnect still in flight. See `CloudProviderRow`'s own KDoc (`CloudSyncTab.kt`).
    private var idleEnabled: Bool {
        cloudSync.connectingType == nil && cloudSync.initialSyncingType == nil && !cloudSync.disconnecting
    }

    /// Gates "disconnect" — looser than `idleEnabled`: leaving stays available through the initial
    /// sync a fresh connect starts, since it's always a safe exit.
    private var leaveEnabled: Bool {
        cloudSync.connectingType == nil && !cloudSync.disconnecting
    }

    var body: some View {
        Form {
            #if os(iOS)
            // The grouped form's own sections, not a `GroupBox` inside one: that nested a second
            // inset box in the section and squeezed the rows.
            Section {
                ForEach(cloudSync.availableCloudTypes, id: \.self) { type in
                    row(for: type)
                }
            } footer: {
                Text(L("settings_cloud_sync_hint"))
            }

            Section {
                syncNowButton
            }
            #else
            GroupBox {
                VStack(alignment: .leading, spacing: 0) {
                    ForEach(Array(cloudSync.availableCloudTypes.enumerated()), id: \.element) { index, type in
                        if index > 0 { Divider() }
                        row(for: type)
                    }
                }
            }

            // Explains the rows above, so it sits right under them in the control column.
            Text(L("settings_cloud_sync_hint"))
                .font(.caption)
                .foregroundStyle(.secondary)

            syncNowButton
            #endif
        }
        .settingsFormPadding()
        .alert(
            confirmDisconnect.map { LF("settings_cloud_disconnect_confirm_title", $0.brandLabel) } ?? "",
            isPresented: Binding(get: { confirmDisconnect != nil }, set: { if !$0 { confirmDisconnect = nil } })
        ) {
            if let type = confirmDisconnect {
                Button(L(disconnectKey(type)), role: .destructive) { cloudSync.controller.disconnect() }
            }
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
            confirmAbort.map { LF("settings_cloud_abort_connect_confirm_title", $0.brandLabel) } ?? "",
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
            confirmSwitch.map { LF("settings_cloud_switch_confirm_title", $0.brandLabel) } ?? "",
            isPresented: Binding(get: { confirmSwitch != nil }, set: { if !$0 { confirmSwitch = nil } })
        ) {
            Button(L("settings_cloud_switch_confirm_action")) {
                if let type = confirmSwitch { cloudSync.controller.switchTo(newType: type) }
            }
            Button(L("common_cancel"), role: .cancel) {}
        } message: {
            // Names the *currently connected* provider (what's being left), not the target — the
            // title above already names the target. Matches Compose's own body, which reads
            // `connectedType` live rather than the tapped row's own type
            // (`CloudSyncTab.kt`'s `confirmingSwitchTo` dialog).
            Text(cloudSync.connectedType.map { LF("settings_cloud_switch_confirm_body", $0.brandLabel) } ?? "")
        }
    }

    /// Brand icon and name leading, actions trailing, with the status and error lines stacked
    /// beneath. The name truncates to one line so the buttons always keep their intrinsic width —
    /// the same split as Compose's own `CloudProviderRow`.
    private func row(for type: CloudStorageType) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(spacing: CloudSyncRowMetrics.iconSpacing) {
                type.brandIcon
                    .resizable()
                    .scaledToFit()
                    .frame(width: CloudSyncRowMetrics.iconSize, height: CloudSyncRowMetrics.iconSize)
                    // Decorative: the name beside it is what VoiceOver reads.
                    .accessibilityHidden(true)
                Text(type.brandLabel)
                    .lineLimit(1)
                    .truncationMode(.tail)
                Spacer(minLength: 8)
                actionButtons(for: type)
            }
            statusLine(for: type)
            errorLine(for: type)
        }
        .padding(.vertical, CloudSyncRowMetrics.verticalPadding)
    }

    /// One provider action. On iOS an icon-only button — the same as Android's own touch-primary
    /// `ProviderActionButton` (`CloudSyncTab.kt`) — with the label kept for VoiceOver, a 44pt hit
    /// area, and the borderless style a form row needs so a tap on the row does not fire every
    /// button in it. macOS keeps a labelled push button, as Compose does with a mouse.
    private func actionButton(
        _ title: String,
        systemImage: String,
        destructive: Bool = false,
        action: @escaping () -> Void
    ) -> some View {
        #if os(iOS)
        Button(action: action) {
            Label(title, systemImage: systemImage)
                .labelStyle(.iconOnly)
                .font(.title3)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.borderless)
        .tint(destructive ? .red : nil)
        #else
        Button(title, action: action)
        #endif
    }

    @ViewBuilder
    private func actionButtons(for type: CloudStorageType) -> some View {
        if cloudSync.connectedType == type {
            // One recovery action, never both — mutually exclusive, since resetting the cloud data
            // needs a working authorization of its own, exactly what an auth failure broke. See
            // `CloudProviderRow`'s own KDoc (`CloudSyncTab.kt`).
            let recoveryEnabled = idleEnabled && !cloudSync.resetting
            let disconnectEnabled = leaveEnabled && !cloudSync.resetting
            HStack(spacing: CloudSyncRowMetrics.actionSpacing) {
                if cloudSync.lastSyncAuthFailed {
                    actionButton(L("settings_cloud_reconnect"), systemImage: "arrow.clockwise") {
                        cloudSync.controller.reconnect()
                    }
                    .disabled(!recoveryEnabled)
                } else {
                    actionButton(L("settings_cloud_reset"), systemImage: "trash", destructive: true) {
                        confirmReset = true
                    }
                    .disabled(!recoveryEnabled)
                }
                actionButton(L(disconnectKey(type)), systemImage: "rectangle.portrait.and.arrow.right") {
                    confirmDisconnect = type
                }
                .disabled(!disconnectEnabled)
            }
        } else if cloudSync.connectingType == type && cloudSync.canCancelConnect {
            // Still waiting on the OAuth browser redirect — offer an explicit abort.
            actionButton(L("common_abort"), systemImage: "xmark.circle") { confirmAbort = type }
        } else {
            // Idle, or past the cancellable window (finishing up: saving tokens/settings/syncing) —
            // the connect/switch button itself shows a spinner rather than swapping to a separate
            // abort button, matching Compose's own `busy = connecting` on this same button.
            Button {
                if cloudSync.connectedType != nil {
                    confirmSwitch = type
                } else {
                    cloudSync.controller.connect(type: type)
                }
            } label: {
                #if os(iOS)
                // Icon-only, like `actionButton`; the spinner takes the icon's place.
                Label {
                    Text(L(connectKey(type)))
                } icon: {
                    if cloudSync.connectingType == type {
                        ProgressView()
                    } else {
                        Image(systemName: "link")
                    }
                }
                .labelStyle(.iconOnly)
                .font(.title3)
                .frame(width: 44, height: 44)
                .contentShape(Rectangle())
                #else
                HStack(spacing: 6) {
                    if cloudSync.connectingType == type {
                        ProgressView().controlSize(.small)
                    }
                    Text(L(connectKey(type)))
                }
                #endif
            }
            #if os(iOS)
            .buttonStyle(.borderless)
            #endif
            .disabled(!idleEnabled || cloudSync.resetting)
        }
    }

    /// Priority order: live progress (disconnecting, or the running sync's own phase) — only ever
    /// non-null for the connected row — then the last-synced subtitle, then nothing. Mirrors
    /// Compose's own `cloudProviderRowStatusText` (`CloudSyncTab.kt`).
    @ViewBuilder
    private func statusLine(for type: CloudStorageType) -> some View {
        if cloudSync.connectedType == type {
            if cloudSync.disconnecting {
                Text(L("settings_cloud_disconnecting")).font(.caption).foregroundStyle(.secondary)
            } else if cloudSync.syncing {
                HStack(spacing: 6) {
                    ProgressView().controlSize(.small)
                    Text(syncPhaseText).font(.caption).foregroundStyle(.secondary)
                }
            } else if let lastSynced = cloudSync.lastSyncedAtText {
                Text(LF("settings_last_synced", lastSynced)).font(.caption).foregroundStyle(.secondary)
            }
        } else if cloudSync.initialSyncingType == type {
            HStack(spacing: 6) {
                ProgressView().controlSize(.small)
                Text(L("settings_cloud_syncing")).font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    /// An in-progress sync failure takes precedence over a stale "last connect attempt failed"
    /// message — it describes the live state of a working connection, matching Compose's own
    /// `lastSyncErrorText ?: (setup_auth_failed if failed)` (`CloudProviderRow`).
    @ViewBuilder
    private func errorLine(for type: CloudStorageType) -> some View {
        if cloudSync.connectedType == type, let error = cloudSync.lastSyncError {
            Text(errorKindMessage(error)).font(.caption).foregroundStyle(.red)
        } else if cloudSync.connectFailedType == type {
            Text(L("setup_auth_failed")).font(.caption).foregroundStyle(.red)
        }
    }

    /// The same manual sync as Home's toolbar button, placed where a sync failure is reported (a
    /// sync-error notification opens this tab), so retrying needs no trip back to Home. Always
    /// present, only disabled when unavailable — matches Compose's own `SyncNowButton`
    /// (`CloudSyncTab.kt`).
    private var syncNowButton: some View {
        Button {
            cloudSync.controller.syncNow()
        } label: {
            HStack(spacing: 6) {
                if cloudSync.syncing && cloudSync.connectedType != nil {
                    ProgressView().controlSize(.small)
                } else {
                    Image(systemName: "icloud")
                }
                Text(L("menu_feed_sync_now"))
            }
        }
        .disabled(!cloudSync.canSyncNow)
    }

    private var syncPhaseText: String {
        switch cloudSync.syncPhase {
        case .idle: return L("settings_cloud_syncing")
        case .checking: return L("settings_cloud_phase_checking")
        case .downloading: return L("settings_cloud_phase_downloading")
        case .merging: return L("settings_cloud_phase_merging")
        case .indexing: return L("settings_cloud_phase_indexing")
        case .preparing: return L("settings_cloud_phase_preparing")
        case .uploading: return L("settings_cloud_phase_uploading")
        case .archiving: return L("settings_cloud_phase_archiving")
        }
    }

    private func connectKey(_ type: CloudStorageType) -> String {
        switch type {
        case .dropbox: return "settings_dropbox_connect"
        case .googleDrive: return "settings_google_drive_connect"
        case .onedrive: return "settings_onedrive_connect"
        default: return ""
        }
    }

    private func disconnectKey(_ type: CloudStorageType) -> String {
        switch type {
        case .dropbox: return "settings_dropbox_disconnect"
        case .googleDrive: return "settings_google_drive_disconnect"
        case .onedrive: return "settings_onedrive_disconnect"
        default: return ""
        }
    }
}

/// A provider row's spacing: roomier on iOS, where each row is a touch target in a grouped form
/// (Android's own `CloudProviderRow` has the same breathing room), than in the macOS `GroupBox`.
private enum CloudSyncRowMetrics {
    #if os(iOS)
    static let iconSize: CGFloat = 28
    static let iconSpacing: CGFloat = 12
    static let actionSpacing: CGFloat = 4
    static let verticalPadding: CGFloat = 8
    #else
    static let iconSize: CGFloat = 20
    static let iconSpacing: CGFloat = 8
    static let actionSpacing: CGFloat = 8
    static let verticalPadding: CGFloat = 6
    #endif
}
