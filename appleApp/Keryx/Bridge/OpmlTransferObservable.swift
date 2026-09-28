import Foundation
import KeryxShared
import Observation
import SwiftUI
import UniformTypeIdentifiers

let opmlContentType: UTType = UTType(filenameExtension: "opml", conformingTo: .xml) ?? .xml

/// A `FileDocument` wrapping the OPML text `.fileExporter` writes and `.fileImporter`'s own
/// `OpmlTransferObservable.importOpml(from:)` path reads independently of.
struct OpmlDocument: FileDocument {
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

/// Shared OPML import/export status and busy-guard — used by both the Data settings tab
/// (`DataSettingsTab.swift`) and the File menu's Import/Export commands (`HomeCommands.swift`), one
/// instance owned by `AppModel`, matching Compose's own single `SettingsViewModel` routing both
/// entry points through the same busy/result state (`AppMenuBar.kt`'s menu items call the same
/// `SettingsViewModel.importOpml`/`exportOpml` the Data tab's own buttons do — see
/// `SettingsViewModel.kt:138-189`).
@MainActor
@Observable
final class OpmlTransferObservable {
    let opml: OpmlTransfer
    private(set) var isBusy = false
    private(set) var statusMessage: String?
    private(set) var statusIsError = false

    init(opml: OpmlTransfer) {
        self.opml = opml
    }

    /// The document to hand `.fileExporter`, or `nil` while an import/export is already running —
    /// mirrors Compose's own busy guard against a second run overlapping the first
    /// (`SettingsViewModel.kt`).
    func exportDocument() -> OpmlDocument? {
        guard !isBusy else { return nil }
        return OpmlDocument(text: opml.exportOpml())
    }

    func reportExportResult(_ result: Swift.Result<URL, any Error>) {
        switch result {
        case .success:
            statusMessage = L("settings_export_success")
            statusIsError = false
        case .failure:
            statusMessage = L("settings_export_error")
            statusIsError = true
        }
    }

    func reportImportPanelFailure() {
        statusMessage = L("settings_import_error")
        statusIsError = true
    }

    func importOpml(from url: URL) {
        guard !isBusy else { return }
        guard url.startAccessingSecurityScopedResource() else {
            statusMessage = L("settings_import_error")
            statusIsError = true
            return
        }
        isBusy = true
        Task {
            defer {
                url.stopAccessingSecurityScopedResource()
                isBusy = false
            }
            guard let xml = try? String(contentsOf: url, encoding: .utf8) else {
                statusMessage = L("settings_import_error")
                statusIsError = true
                return
            }
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
