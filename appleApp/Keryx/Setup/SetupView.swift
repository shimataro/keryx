import KeryxShared
import SwiftUI

/// First-launch setup: local-only or connect a cloud provider — see `SetupController.kt`'s own
/// doc. Shown by `KeryxApp` while `SettingsRepository.isSetupComplete()` is false.
struct SetupView: View {
    let oauthCoordinator: OAuthSessionCoordinator
    let onDone: () -> Void

    @State private var setup: SetupObservable
    @State private var showAbortConfirm = false

    init(controller: SetupController, oauthCoordinator: OAuthSessionCoordinator, onDone: @escaping () -> Void) {
        self.oauthCoordinator = oauthCoordinator
        self.onDone = onDone
        self._setup = State(initialValue: SetupObservable(controller: controller))
    }

    var body: some View {
        VStack(spacing: 24) {
            Text(L("setup_title"))
                .font(.largeTitle.bold())
            Text(L("setup_choose_mode"))
                .foregroundStyle(.secondary)

            VStack(alignment: .leading, spacing: 8) {
                Button {
                    setup.controller.chooseLocalOnly { onDone() }
                } label: {
                    VStack(alignment: .leading) {
                        Text(L("setup_local_only")).font(.headline)
                        Text(L("setup_local_desc")).font(.caption).foregroundStyle(.secondary)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }
                .buttonStyle(.bordered)
            }

            VStack(alignment: .leading, spacing: 8) {
                Text(L("setup_cloud_sync_section_title")).font(.headline)
                Text(L("setup_cloud_sync_section_desc")).font(.caption).foregroundStyle(.secondary)

                ForEach(setup.controller.availableCloudTypes, id: \.self) { type in
                    Button {
                        setup.controller.connect(type: type) { onDone() }
                    } label: {
                        Text(L(providerKey(type)))
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
            }

            if setup.phase == .connecting {
                VStack(spacing: 8) {
                    ProgressView()
                    Text(L("setup_connecting"))
                    if setup.canCancelConnect {
                        Button(L("common_cancel")) { showAbortConfirm = true }
                    }
                }
            }

            if setup.phase == .error {
                Text(L("setup_auth_failed"))
                    .foregroundStyle(.red)
            }
        }
        .padding(40)
        .frame(minWidth: 480, minHeight: 420)
        .task { await setup.startObserving() }
        .alert(L("setup_abort_connect_confirm_title"), isPresented: $showAbortConfirm) {
            Button(L("common_abort"), role: .destructive) {
                setup.controller.cancelConnect()
                oauthCoordinator.cancelActiveSession()
            }
            Button(L("common_cancel"), role: .cancel) {}
        } message: {
            Text(L("setup_abort_connect_confirm_body"))
        }
    }

    private func providerKey(_ type: CloudStorageType) -> String {
        switch type {
        case .dropbox: return "setup_dropbox"
        case .googleDrive: return "setup_google_drive"
        case .onedrive: return "setup_onedrive"
        }
    }
}
