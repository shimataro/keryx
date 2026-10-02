import Foundation
import KeryxShared
import Testing

/// Fakes `OpmlTransferring` — `KeryxTests` is standalone/non-hosted, so this substitutes for a
/// running `KeryxSdk`'s real `OpmlTransferController`, with the same rules in miniature: one
/// operation at a time, a request handed out only while nothing runs. `@unchecked Sendable` (not an
/// `actor`/`@MainActor` class): the Kotlin result types aren't themselves `Sendable`, and an actor's
/// isolated `importResult(xml:)` would be barred from returning one across the isolation boundary —
/// a restriction the real controller (a plain, non-isolated conformance) never hits. The mutable
/// state is protected by a lock instead.
final class FakeOpmlTransferring: OpmlTransferring, @unchecked Sendable {
    private let importOutcome: OpmlResult
    private let importDelayNanoseconds: UInt64
    private let lock = NSLock()
    private var running: OpmlOperation?
    private var lastResult: OpmlResult?
    private var pending: OpmlRequest?
    private var _importedXml: [String?] = []
    private var _finishedResults: [OpmlResult?] = []
    private var _clearCount = 0

    var importedXml: [String?] { lock.withLock { _importedXml } }
    var finishedResults: [OpmlResult?] { lock.withLock { _finishedResults } }
    var clearCount: Int { lock.withLock { _clearCount } }
    var isRunning: Bool { lock.withLock { running != nil } }
    var currentlyBusy: Bool { isRunning }
    var currentResult: OpmlResult? { lock.withLock { lastResult } }

    init(importOutcome: OpmlResult = OpmlResultImported(added: 0, failed: 0), importDelayNanoseconds: UInt64 = 0) {
        self.importOutcome = importOutcome
        self.importDelayNanoseconds = importDelayNanoseconds
    }

    func tryBegin(operation: OpmlOperation) -> Bool {
        lock.withLock {
            guard running == nil else { return false }
            running = operation
            lastResult = nil
            return true
        }
    }

    func finish(result: OpmlResult?) {
        lock.withLock {
            running = nil
            lastResult = result
            _finishedResults.append(result)
        }
    }

    func exportDocument() -> String { "<opml/>" }

    /// Throws only on cancellation (during the delay), like the real `importResult`.
    func importResult(xml: String?) async throws -> OpmlResult {
        if importDelayNanoseconds > 0 {
            try await Task.sleep(nanoseconds: importDelayNanoseconds)
        }
        lock.withLock { _importedXml.append(xml) }
        return xml == nil ? OpmlResultImportFailed.shared : importOutcome
    }

    /// The real `importDocument`'s shape: refused while running, otherwise begin → import → finish,
    /// finishing with no result when cancelled.
    func importDocument(xml: String?) async throws -> KotlinBoolean {
        guard tryBegin(operation: .importing) else { return KotlinBoolean(bool: false) }
        var outcome: OpmlResult?
        defer { finish(result: outcome) }
        outcome = try await importResult(xml: xml)
        return KotlinBoolean(bool: true)
    }

    func request(request: OpmlRequest) {
        lock.withLock { pending = request }
    }

    func consumeRequest() -> OpmlRequest? {
        lock.withLock {
            guard running == nil else { return nil }
            defer { pending = nil }
            return pending
        }
    }

    func clearResult() {
        lock.withLock { _clearCount += 1 }
    }
}

@MainActor
@Suite
struct OpmlTransferObservableTests {
    private func writeTempOpmlFile(_ text: String = "<opml></opml>") throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".opml")
        try text.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    @Test
    func busyCoversTheWholeImportIncludingThePanel() async throws {
        let fake = FakeOpmlTransferring(importDelayNanoseconds: 50_000_000)
        let observable = OpmlTransferObservable(controller: fake)
        let url = try writeTempOpmlFile()
        defer { try? FileManager.default.removeItem(at: url) }

        #expect(observable.beginImport())
        #expect(observable.isBusy, "busy from the moment the open panel is shown")
        #expect(observable.prepareExport() == nil, "a second operation is refused")

        await observable.importOpml(from: url).value

        #expect(!observable.isBusy)
        #expect(!fake.isRunning)
        #expect(fake.importedXml == ["<opml></opml>"])
    }

    @Test
    func dismissingTheImportPanelEndsTheOperationWithNothingToShow() {
        let fake = FakeOpmlTransferring()
        let observable = OpmlTransferObservable(controller: fake)

        #expect(observable.beginImport())
        observable.reportImportCancelled()

        #expect(!observable.isBusy)
        #expect(!fake.isRunning)
        #expect(observable.result == nil)
    }

    @Test
    func anExportIsRecordedWhenTheSavePanelReportsBack() {
        let fake = FakeOpmlTransferring()
        let observable = OpmlTransferObservable(controller: fake)

        #expect(observable.prepareExport() != nil)
        observable.reportExportResult(.success(URL(fileURLWithPath: "/tmp/keryx.opml")))

        #expect(!observable.isBusy)
        #expect(!fake.isRunning)
        let result = try? #require(observable.result)
        #expect(result.map { !OpmlTransferObservable.statusText(for: $0).1 } == true)
    }

    @Test
    func anUnreadableOpenedFileFinishesAsImportFailed() async throws {
        let fake = FakeOpmlTransferring()
        let observable = OpmlTransferObservable(controller: fake)

        let task = try #require(observable.importDocument(xml: nil))
        await task.value

        let result = try #require(observable.result)
        #expect(observable.statusIsErrorFor(result))
        #expect(fake.importedXml == [nil])
    }

    @Test
    func importDocumentIsRefusedWhileAnotherOperationRuns() {
        let fake = FakeOpmlTransferring()
        let observable = OpmlTransferObservable(controller: fake)
        _ = observable.beginImport()

        #expect(observable.importDocument(xml: "<opml/>") == nil)
        #expect(fake.importedXml.isEmpty)
        #expect(observable.isBusy, "the running import's state is left as it was")
        #expect(fake.isRunning)
    }

    @Test
    func importDocumentShowsBusyRightAwayAndTheControllersResultAfterwards() async throws {
        let fake = FakeOpmlTransferring(importOutcome: OpmlResultImported(added: 2, failed: 0), importDelayNanoseconds: 50_000_000)
        let observable = OpmlTransferObservable(controller: fake)
        // A previous result, which starting a new import must clear at once.
        await observable.importDocument(xml: "<opml/>")?.value
        #expect(observable.result != nil)

        let task = try #require(observable.importDocument(xml: "<opml/>"))
        #expect(observable.isBusy, "busy before the controller's flow catches up")
        #expect(observable.result == nil)
        #expect(observable.importDocument(xml: "<opml/>") == nil, "no second start while running")
        await task.value

        #expect(!observable.isBusy)
        #expect(!fake.isRunning)
        #expect((observable.result as? OpmlResultImported)?.added == 2)
        #expect(fake.importedXml == ["<opml/>", "<opml/>"], "the run happened once")
        #expect(fake.finishedResults.count == 2, "finished by the controller once per run")
    }

    @Test
    func aCancelledImportDocumentEndsWithNoResult() async throws {
        let fake = FakeOpmlTransferring(importDelayNanoseconds: 5_000_000_000)
        let observable = OpmlTransferObservable(controller: fake)

        let task = try #require(observable.importDocument(xml: "<opml/>"))
        task.cancel()
        await task.value

        #expect(!observable.isBusy)
        #expect(!fake.isRunning)
        #expect(observable.result == nil, "a cancellation is not an ImportFailed")
        #expect(fake.finishedResults.count == 1)
        #expect(fake.finishedResults.first.map { $0 == nil } == true)
    }

    @Test
    func aCancelledPanelImportEndsWithNoResult() async throws {
        let fake = FakeOpmlTransferring(importDelayNanoseconds: 5_000_000_000)
        let observable = OpmlTransferObservable(controller: fake)
        let url = try writeTempOpmlFile()
        defer { try? FileManager.default.removeItem(at: url) }

        #expect(observable.beginImport())
        let task = observable.importOpml(from: url)
        task.cancel()
        await task.value

        #expect(!observable.isBusy)
        #expect(!fake.isRunning)
        #expect(observable.result == nil, "a cancellation is not an ImportFailed")
    }

    @Test
    func importDocumentRefusedByTheControllerTakesTheControllersState() async throws {
        let fake = FakeOpmlTransferring()
        let observable = OpmlTransferObservable(controller: fake)
        // Another operation holds the controller before the observable has seen it (its flow has
        // not caught up), so the observable's own guard lets the call through.
        #expect(fake.tryBegin(operation: .exporting))

        let task = try #require(observable.importDocument(xml: "<opml/>"))
        await task.value

        #expect(observable.isBusy, "the other operation is still running")
        #expect(observable.result == nil)
        #expect(fake.importedXml.isEmpty)
        #expect(fake.finishedResults.isEmpty, "the other operation is not finished by this call")
    }

    @Test
    func aRequestIsTakenOnlyWhileNothingRuns() {
        let fake = FakeOpmlTransferring()
        let observable = OpmlTransferObservable(controller: fake)
        _ = observable.beginImport()

        observable.request(OpmlRequestExportFile.shared)
        #expect(observable.pendingRequest != nil)
        #expect(observable.takeRequest() == nil, "waits for the running import")

        observable.reportImportCancelled()
        #expect(observable.takeRequest() != nil)
        #expect(observable.pendingRequest == nil)
        #expect(observable.takeRequest() == nil, "handed out once")
    }

    @Test
    func shouldPresentRequestFollowsTheWaitingRequestAndTheBusyState() {
        let fake = FakeOpmlTransferring()
        let observable = OpmlTransferObservable(controller: fake)
        #expect(!observable.shouldPresentRequest, "nothing is waiting")

        observable.request(OpmlRequestImportFile.shared)
        #expect(observable.shouldPresentRequest, "a request made while idle opens the Data tab")

        _ = observable.beginImport()
        #expect(!observable.shouldPresentRequest, "waits while an operation runs")

        observable.reportImportCancelled()
        #expect(observable.shouldPresentRequest, "opens once the run finishes")
    }

    @Test
    func aResultIsShownOnceAndThenCleared() async throws {
        let fake = FakeOpmlTransferring(importOutcome: OpmlResultImported(added: 3, failed: 0))
        let observable = OpmlTransferObservable(controller: fake)
        let task = try #require(observable.importDocument(xml: "<opml/>"))
        await task.value

        observable.showPendingResult()

        #expect(observable.statusMessage != nil)
        #expect(!observable.statusIsError)
        #expect(observable.result == nil)
        #expect(fake.clearCount == 1)

        observable.showPendingResult()
        #expect(fake.clearCount == 1, "nothing left to show on a later visit")
    }

    @Test
    func aPartialFailureShowsBothCountsAsAnError() {
        let (message, isError) = OpmlTransferObservable.statusText(for: OpmlResultImported(added: 2, failed: 1))
        #expect(isError)
        #expect(message.contains(" / "), "added and failed counts are both shown, like Compose's opmlImportedText")
    }

    @Test
    func aFullSuccessShowsOnlyTheAddedCount() {
        let (message, isError) = OpmlTransferObservable.statusText(for: OpmlResultImported(added: 2, failed: 0))
        #expect(!isError)
        #expect(!message.contains(" / "))
    }

    @Test
    func exportResultsMapToTheirStatus() {
        #expect(!OpmlTransferObservable.statusText(for: OpmlResultExported.shared).1)
        #expect(OpmlTransferObservable.statusText(for: OpmlResultExportFailed.shared).1)
        #expect(OpmlTransferObservable.statusText(for: OpmlResultImportFailed.shared).1)
    }
}

private extension OpmlTransferObservable {
    func statusIsErrorFor(_ result: OpmlResult) -> Bool {
        Self.statusText(for: result).1
    }
}
