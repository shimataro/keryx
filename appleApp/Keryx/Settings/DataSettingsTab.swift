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
            .pickerStyle(.segmented)

            Picker(L("settings_read_timeout"), selection: readTimeoutBinding) {
                Text(L("settings_seconds10")).tag(Int32(10))
                Text(L("settings_seconds30")).tag(Int32(30))
                Text(L("settings_seconds60")).tag(Int32(60))
            }
            .pickerStyle(.segmented)

            Section(L("settings_data_management")) {
                HStack {
                    Button(L("settings_import_opml")) {
                        isImporting = true
                    }
                    .disabled(opmlTransfer.isBusy)
                    Button(L("settings_export_opml")) {
                        exportDocument = opmlTransfer.exportDocument()
                        isExporting = true
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
        .padding()
        .fileExporter(
            isPresented: $isExporting,
            document: exportDocument,
            contentType: opmlContentType,
            defaultFilename: "keryx"
        ) { result in
            opmlTransfer.reportExportResult(result)
        }
        .fileImporter(isPresented: $isImporting, allowedContentTypes: [opmlContentType, .xml]) { result in
            switch result {
            case .success(let url):
                opmlTransfer.importOpml(from: url)
            case .failure:
                opmlTransfer.reportImportPanelFailure()
            }
        }
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
