import KeryxShared
import SwiftUI
import UniformTypeIdentifiers

private let opmlContentType = UTType(filenameExtension: "opml", conformingTo: .xml) ?? .xml

private struct OpmlDocument: FileDocument {
    static var readableContentTypes: [UTType] { [opmlContentType, .xml] }
    var text: String

    init(text: String) { self.text = text }

    init(configuration: ReadConfiguration) throws {
        guard let data = configuration.file.regularFileContents else {
            throw CocoaError(.fileReadCorruptFile)
        }
        text = String(decoding: data, as: UTF8.self)
    }

    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}

struct DataSettingsTab: View {
    let preferences: PreferencesObservable
    let opml: OpmlTransfer

    @State private var isExporting = false
    @State private var isImporting = false
    @State private var exportDocument: OpmlDocument?
    @State private var statusMessage: String?
    @State private var statusIsError = false

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
                    Button(L("settings_export_opml")) {
                        exportDocument = OpmlDocument(text: opml.exportOpml())
                        isExporting = true
                    }
                }
                if let statusMessage {
                    Text(statusMessage)
                        .font(.caption)
                        .foregroundStyle(statusIsError ? .red : .secondary)
                }
            }
        }
        .padding()
        .fileExporter(
            isPresented: $isExporting,
            document: exportDocument,
            contentType: opmlContentType,
            defaultFilename: "keryx-feeds"
        ) { result in
            switch result {
            case .success:
                statusMessage = L("settings_export_success")
                statusIsError = false
            case .failure:
                statusMessage = L("settings_export_error")
                statusIsError = true
            }
        }
        .fileImporter(isPresented: $isImporting, allowedContentTypes: [opmlContentType, .xml]) { result in
            switch result {
            case .success(let url):
                importOpml(from: url)
            case .failure:
                statusMessage = L("settings_import_error")
                statusIsError = true
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

    private func importOpml(from url: URL) {
        guard url.startAccessingSecurityScopedResource() else {
            statusMessage = L("settings_import_error")
            statusIsError = true
            return
        }
        defer { url.stopAccessingSecurityScopedResource() }
        guard let xml = try? String(contentsOf: url, encoding: .utf8) else {
            statusMessage = L("settings_import_error")
            statusIsError = true
            return
        }
        Task {
            do {
                let outcome = try await opml.importOpml(xml: xml)
                if outcome.failed > 0 {
                    statusMessage = LF("apple_opml_import_failed", Int64(outcome.failed))
                    statusIsError = true
                } else {
                    statusMessage = LF("apple_opml_import_success", Int64(outcome.added))
                    statusIsError = false
                }
            } catch {
                statusMessage = L("settings_import_error")
                statusIsError = true
            }
        }
    }
}
