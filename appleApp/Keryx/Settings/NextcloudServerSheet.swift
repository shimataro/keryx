import KeryxShared
import SwiftUI

/// Asks for the Nextcloud server to connect to and checks it before the sign-in starts — opened by
/// every Nextcloud connect route (Setup, and the Cloud Sync tab's connect, switch and reconnect).
/// The state machine is `NextcloudServerController`, shared with Compose's own
/// `NextcloudServerDialog`; this view only renders it. Says up front what the sign-in grants and how
/// to revoke or narrow it. Cancelling connects nothing and tears nothing down.
struct NextcloudServerSheet: View {
    let prompt: NextcloudServerPrompt
    let onClose: () -> Void

    @State private var server: NextcloudServerObservable
    @FocusState private var urlFieldFocused: Bool

    init(prompt: NextcloudServerPrompt, makeController: (String) -> NextcloudServerController, onClose: @escaping () -> Void) {
        self.prompt = prompt
        self.onClose = onClose
        self._server = State(initialValue: NextcloudServerObservable(controller: makeController(prompt.initialUrl)))
    }

    var body: some View {
        #if os(iOS)
        NavigationStack {
            Form {
                Section {
                    urlField
                        .keyboardType(.URL)
                        .textInputAutocapitalization(.never)
                        .autocorrectionDisabled()
                    statusLine
                } footer: {
                    Text(L("nextcloud_server_scope"))
                }
            }
            .navigationTitle(L("nextcloud_server_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("common_cancel"), role: .cancel) { onClose() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    confirmButton
                }
            }
        }
        .presentationDetents([.medium, .large])
        .task { await server.startObserving() }
        #else
        VStack(alignment: .leading, spacing: 12) {
            Text(L("nextcloud_server_title"))
                .font(.headline)
            Text(L("nextcloud_server_scope"))
                .font(.caption)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
            urlField
                .textFieldStyle(.roundedBorder)
                .autocorrectionDisabled()
            statusLine
            HStack {
                Spacer()
                Button(L("common_cancel"), role: .cancel) { onClose() }
                    .keyboardShortcut(.cancelAction)
                confirmButton
                    .keyboardShortcut(.defaultAction)
            }
        }
        .padding()
        .frame(width: 420)
        .task { await server.startObserving() }
        #endif
    }

    private var urlField: some View {
        TextField(
            L("nextcloud_server_hint"),
            text: Binding(get: { server.state.url }, set: { server.controller.setUrl(url: $0) })
        )
        .focused($urlFieldFocused)
        .onSubmit { submit() }
        .task { urlFieldFocused = true }
    }

    private var confirmButton: some View {
        Button(L("nextcloud_server_continue")) { submit() }
            .disabled(!server.state.confirmEnabled)
    }

    /// The check in flight, or why the last one failed.
    @ViewBuilder
    private var statusLine: some View {
        if server.state.checking {
            HStack(spacing: 6) {
                ProgressView().controlSize(.small)
                Text(L("nextcloud_server_checking")).font(.caption)
            }
        } else if let error = server.state.error {
            Text(errorKindMessage(error))
                .font(.caption)
                .foregroundStyle(.red)
        }
    }

    private func submit() {
        let controller = server.controller
        let onConfirmed = prompt.onConfirmed
        let close = onClose
        Task { @MainActor in
            if let request = try? await controller.submit() {
                close()
                onConfirmed(request)
            }
        }
    }
}
