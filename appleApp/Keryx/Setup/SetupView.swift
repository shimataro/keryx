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

            // Two equal-weight option cards capped at Compose's own `widthIn(max = 420.dp)`
            // (`SetupScreen.kt`), so the choices stay a centered column however wide the window is.
            VStack(spacing: 12) {
                optionCard(title: L("setup_local_only"), desc: L("setup_local_desc")) {
                    Button {
                        setup.controller.chooseLocalOnly { onDone() }
                    } label: {
                        Text(L("setup_local_action"))
                            .frame(maxWidth: .infinity)
                    }
                }

                // Hidden entirely (not just emptied) when no provider is configured for this build —
                // matches Compose's own `SetupScreen.kt:137`. The auto-local-only choice below covers
                // this same case, so this branch and that `.task` are never both relevant to the user.
                if !setup.controller.availableCloudTypes.isEmpty {
                    optionCard(title: L("setup_cloud_sync_section_title"), desc: L("setup_cloud_sync_section_desc")) {
                        ForEach(setup.controller.availableCloudTypes, id: \.self) { type in
                            Button {
                                setup.controller.connect(type: type) { onDone() }
                            } label: {
                                Label {
                                    Text(L(providerKey(type)))
                                } icon: {
                                    type.brandIcon
                                        .resizable()
                                        .scaledToFit()
                                        .frame(width: 18, height: 18)
                                        // Decorative: the button's own text names the provider.
                                        .accessibilityHidden(true)
                                }
                                .frame(maxWidth: .infinity)
                            }
                        }
                    }
                }
            }
            .buttonStyle(.bordered)
            .controlSize(.large)
            .disabled(setup.phase == .connecting)
            .frame(maxWidth: 420)

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
        // Skips Setup entirely when no cloud provider is configured for this build — matches
        // Compose's own auto-choice (`App.kt:43-54`), rather than showing a cloud section with no
        // providers in it and making the user pick "local only" for themselves regardless.
        .task {
            if setup.controller.availableCloudTypes.isEmpty {
                setup.controller.chooseLocalOnly { onDone() }
            }
        }
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

    /// Centered heading + description above full-width actions — the counterpart of Compose's own
    /// `KeryxRaisedSurface` cards (`SetupScreen.kt`).
    private func optionCard<Content: View>(
        title: String,
        desc: String,
        @ViewBuilder content: () -> Content
    ) -> some View {
        GroupBox {
            VStack(spacing: 4) {
                Text(title).font(.headline)
                Text(desc).font(.caption).foregroundStyle(.secondary)
                VStack(spacing: 8, content: content)
                    .padding(.top, 8)
            }
            .multilineTextAlignment(.center)
            .frame(maxWidth: .infinity)
            .padding(12)
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
