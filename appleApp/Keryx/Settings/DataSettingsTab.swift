import KeryxShared
import SwiftUI
import UniformTypeIdentifiers

struct DataSettingsTab: View {
    let preferences: PreferencesObservable
    let opmlTransfer: OpmlTransferObservable

    @State private var isExporting = false
    @State private var isImporting = false
    @State private var exportDocument: OpmlDocument?

    var body: some View {
        Form {
            Picker(L("settings_cache"), selection: cacheRetentionBinding) {
                Text(L("settings_days7")).tag(Optional(7))
                Text(L("settings_days30")).tag(Optional(30))
                Text(L("settings_days90")).tag(Optional(90))
                Text(L("settings_unlimited")).tag(Optional<Int>.none)
            }
            .pickerStyle(.menu)

            Picker(L("settings_read_timeout"), selection: readTimeoutBinding) {
                Text(L("settings_seconds10")).tag(Int32(10))
                Text(L("settings_seconds30")).tag(Int32(30))
                Text(L("settings_seconds60")).tag(Int32(60))
            }
            .pickerStyle(.menu)

            #if os(iOS)
            // One row per action, as iOS Settings lays out its own actions: two buttons side by side
            // in a single row wrapped their titles at a phone width.
            Section(L("settings_data_management")) {
                Button {
                    startImport()
                } label: {
                    Label(L("settings_import_opml"), systemImage: "arrow.down.doc")
                }
                .disabled(opmlTransfer.isBusy)
                Button {
                    startExport()
                } label: {
                    Label(L("settings_export_opml"), systemImage: "arrow.up.doc")
                }
                .disabled(opmlTransfer.isBusy)
                if opmlTransfer.isBusy {
                    ProgressView()
                }
                if let statusMessage = opmlTransfer.statusMessage {
                    Text(statusMessage)
                        .font(.caption)
                        .foregroundStyle(opmlTransfer.statusIsError ? .red : .secondary)
                }
            }
            #else
            // A labeled row rather than a `Section`, so its label sits in the same right-aligned label
            // column as the pickers above instead of heading the buttons.
            LabeledContent(L("settings_data_management")) {
                VStack(alignment: .leading) {
                    HStack {
                        Button(L("settings_import_opml")) {
                            startImport()
                        }
                        .disabled(opmlTransfer.isBusy)
                        Button(L("settings_export_opml")) {
                            startExport()
                        }
                        .disabled(opmlTransfer.isBusy)
                        if opmlTransfer.isBusy {
                            ProgressView().controlSize(.small)
                        }
                    }
                    if let statusMessage = opmlTransfer.statusMessage {
                        Text(statusMessage)
                            .font(.caption)
                            .foregroundStyle(opmlTransfer.statusIsError ? .red : .secondary)
                    }
                }
            }
            #endif
        }
        .settingsFormPadding()
        .fileExporter(
            isPresented: $isExporting,
            document: exportDocument,
            contentType: opmlContentType,
            defaultFilename: "keryx"
        ) { result in
            opmlTransfer.reportExportResult(result)
        }
        // The multiple-selection overload, purely because it is the one with `onCancellation` — the
        // import has already begun (busy) when the panel opens, so dismissing it must end it.
        .fileImporter(
            isPresented: $isImporting,
            allowedContentTypes: [opmlContentType, .xml],
            allowsMultipleSelection: false
        ) { result in
            switch result {
            case .success(let urls):
                if let url = urls.first {
                    opmlTransfer.importOpml(from: url)
                } else {
                    opmlTransfer.reportImportCancelled()
                }
            case .failure:
                opmlTransfer.reportImportPanelFailure()
            }
        } onCancellation: {
            opmlTransfer.reportImportCancelled()
        }
        // Carries out an import/export asked for outside this tab (the File menu, an opened .opml
        // file) here, so it gets the same panel, spinner and result as the buttons above — matching
        // Compose's `DataTab`. Waits while another operation runs (`takeRequest()` is `nil` then); a
        // taken import the controller still refuses is put back by `importDocument(_:)`, never lost.
        .task(id: RequestTrigger(pending: opmlTransfer.pendingRequest != nil, busy: opmlTransfer.isBusy)) {
            guard let request = opmlTransfer.takeRequest() else { return }
            switch onEnum(of: request) {
            case .importFile: startImport()
            case .exportFile: startExport()
            case .importDocument(let document): opmlTransfer.importDocument(document)
            }
        }
        // Shows a finished result once — including one that finished while Settings was closed.
        .task(id: opmlTransfer.result != nil) {
            opmlTransfer.showPendingResult()
        }
    }

    private struct RequestTrigger: Equatable {
        let pending: Bool
        let busy: Bool
    }

    private func startImport() {
        guard opmlTransfer.beginImport() else { return }
        isImporting = true
    }

    private func startExport() {
        guard let document = opmlTransfer.prepareExport() else { return }
        exportDocument = document
        isExporting = true
    }

    private var cacheRetentionBinding: Binding<Int?> {
        Binding(
            get: { preferences.cacheRetentionDays },
            set: { preferences.controller.updateCacheRetention(days: $0.map { KotlinInt(int: Int32($0)) }) }
        )
    }

    private var readTimeoutBinding: Binding<Int32> {
        Binding(
            get: { Int32(preferences.readTimeoutSeconds) },
            set: { preferences.controller.updateReadTimeout(seconds: $0) }
        )
    }
}
