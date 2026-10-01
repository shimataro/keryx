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

/// What `OpmlTransferObservable` needs from Kotlin's `OpmlTransferController` — pulled out as a
/// protocol (rather than depending on the class directly) purely so `OpmlTransferObservableTests` can
/// substitute a fake and exercise the request/busy/result handling below without a running
/// `KeryxSdk`. `Sendable`: `importOpml(from:)` captures `self.controller` into a plain `Task { }`,
/// which requires everything it captures to be `Sendable` (see `KotlinSendableBridging.swift` for the
/// Kotlin class's own conformance).
protocol OpmlTransferring: AnyObject, Sendable {
    func tryBegin(operation: OpmlOperation) -> Bool
    func finish(result: OpmlResult?)
    func exportDocument() -> String
    func importResult(xml: String?) async throws -> OpmlResult
    func request(request: OpmlRequest)
    func consumeRequest() -> OpmlRequest?
    func clearResult()
}

extension OpmlTransferController: OpmlTransferring {}

/// Mirrors Kotlin's `OpmlTransferController` — the one OPML busy/result/request state every route
/// shares (the Data settings tab, the File menu, an `.opml` file the app was opened with), exactly as
/// Compose's `SettingsViewModel`/`DataTab` do. One instance, owned by `AppModel`.
///
/// The File menu and an opened file only `request(_:)` an operation; a Home-only view then shows
/// Settings on the Data tab (`OpmlRequestPresenter`), and `DataSettingsTab` carries the request out
/// (`takeRequest()`), so every route gets the same file panel, spinner and result in the same place.
/// `isBusy`/`result`/`pendingRequest` are kept current by `startObserving(_:)` (started once by
/// `AppModel`), and also updated right away by this class's own calls, so a view never sees a stale
/// value between a call and the flow's next emission.
@MainActor
@Observable
final class OpmlTransferObservable {
    let controller: OpmlTransferring
    private(set) var isBusy = false
    /// The last finished operation's outcome, until a view shows it (`takeResultForDisplay()`) — kept
    /// while Settings is closed, so it is shown once on the next visit to the Data tab.
    private(set) var result: OpmlResult?
    private(set) var pendingRequest: OpmlRequest?
    /// The result text currently shown under the Data tab's buttons.
    private(set) var statusMessage: String?
    private(set) var statusIsError = false

    init(controller: OpmlTransferring) {
        self.controller = controller
    }

    /// Started once, for the app's lifetime, by `AppModel` (the menu and the Settings window both
    /// read this, with or without the main window open).
    func startObserving(_ source: OpmlTransferController) async {
        async let t1: () = observeBusy(source)
        async let t2: () = observeResult(source)
        async let t3: () = observePendingRequest(source)
        _ = await (t1, t2, t3)
    }

    private func observeBusy(_ source: OpmlTransferController) async {
        for await v in source.busy { isBusy = v.boolValue }
    }

    private func observeResult(_ source: OpmlTransferController) async {
        for await v in source.result { result = v }
    }

    private func observePendingRequest(_ source: OpmlTransferController) async {
        for await v in source.pendingRequest { pendingRequest = v }
    }

    /// Asks for an import/export to be carried out by the Data settings tab (the File menu's items).
    func request(_ request: OpmlRequest) {
        controller.request(request: request)
        pendingRequest = request
    }

    /// Takes the waiting request for the Data tab to carry out — `nil` while an operation is running
    /// (the request then waits for it to finish).
    func takeRequest() -> OpmlRequest? {
        let taken = controller.consumeRequest()
        if taken != nil { pendingRequest = nil }
        return taken
    }

    /// The document to hand `.fileExporter`, or `nil` while another operation is running. The export
    /// itself is recorded only once the save panel reports back (`reportExportResult(_:)`): the
    /// `FileDocument` exporter has no cancellation callback on the supported OS versions, so holding
    /// the busy state across the panel could leave it stuck after a dismissal.
    func prepareExport() -> OpmlDocument? {
        guard !isBusy else { return nil }
        return OpmlDocument(text: controller.exportDocument())
    }

    func reportExportResult(_ outcome: Swift.Result<URL, any Error>) {
        guard begin(.exporting) else { return }
        switch outcome {
        case .success: finish(OpmlResultExported.shared)
        case .failure: finish(OpmlResultExportFailed.shared)
        }
    }

    /// Begins an import before the open panel is shown, so the busy state (and the disabled menu
    /// items) covers the panel too, matching Compose. `false` while another operation is running.
    func beginImport() -> Bool {
        begin(.importing)
    }

    /// The open panel was dismissed: the import ends with nothing to show.
    func reportImportCancelled() {
        finish(nil)
    }

    /// The open panel itself failed.
    func reportImportPanelFailure() {
        finish(OpmlResultImportFailed.shared)
    }

    /// Imports the file the open panel returned; the import must already have been begun
    /// (`beginImport()`). Returns the running task so a caller (in practice,
    /// `OpmlTransferObservableTests`) can await its completion.
    @discardableResult
    func importOpml(from url: URL) -> Task<Void, Never> {
        let accessing = url.startAccessingSecurityScopedResource()
        let xml = try? String(contentsOf: url, encoding: .utf8)
        if accessing { url.stopAccessingSecurityScopedResource() }
        return runImport(xml: xml)
    }

    /// Imports an already-read document (an `.opml` file the app was opened with; `nil` when reading
    /// it failed, which ends as an import failure). Returns `nil` when another operation is running.
    @discardableResult
    func importDocument(xml: String?) -> Task<Void, Never>? {
        guard begin(.importing) else { return nil }
        return runImport(xml: xml)
    }

    /// Turns a finished result into the status text under the Data tab's buttons, once, and clears it
    /// so it is not shown again on a later visit.
    func showPendingResult() {
        guard let result else { return }
        let (message, isError) = Self.statusText(for: result)
        statusMessage = message
        statusIsError = isError
        self.result = nil
        controller.clearResult()
    }

    /// The text for `result` — matching Compose's `opmlImportedText` (added and failed counts both
    /// shown when any feed failed).
    static func statusText(for result: OpmlResult) -> (String, Bool) {
        switch onEnum(of: result) {
        case .exported: return (L("settings_export_success"), false)
        case .exportFailed: return (L("settings_export_error"), true)
        case .importFailed: return (L("settings_import_error"), true)
        case .imported(let imported):
            let added = LF("settings_import_success", Int64(imported.added))
            guard imported.failed > 0 else { return (added, false) }
            return ("\(added) / \(LF("settings_import_failed", Int64(imported.failed)))", true)
        }
    }

    private func begin(_ operation: OpmlOperation) -> Bool {
        guard controller.tryBegin(operation: operation) else { return false }
        isBusy = true
        result = nil
        return true
    }

    private func finish(_ outcome: OpmlResult?) {
        controller.finish(result: outcome)
        result = outcome
        isBusy = false
    }

    private func runImport(xml: String?) -> Task<Void, Never> {
        let controller = self.controller
        return Task {
            let outcome: OpmlResult
            do {
                outcome = try await controller.importResult(xml: xml)
            } catch {
                outcome = OpmlResultImportFailed.shared
            }
            finish(outcome)
        }
    }
}
