import Foundation
import KeryxShared
import Testing

/// Fakes `OpmlTransferring` — `KeryxTests` is standalone/non-hosted, so this substitutes for a
/// running `KeryxSdk`'s real `OpmlTransferController`, with its rules in miniature: one operation at a
/// time, a request handed out only while nothing runs, and `importBegun` always finishing. Kept
/// deliberately minimal: the real controller's ordering and cancellation guarantees are pinned by
/// `OpmlTransferControllerTest` (and, against the real SDK graph, `KeryxSdkTest`) in `:shared`, which
/// is the authority — these tests only cover what `OpmlTransferObservable` does with its answers.
/// `@unchecked Sendable` (not an `actor`/`@MainActor` class): the Kotlin result types aren't
/// themselves `Sendable`, which an actor's isolated methods would be barred from handing across the
/// isolation boundary — a restriction the real controller (a plain, non-isolated conformance) never
/// hits. The mutable state is protected by a lock instead.
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
    var currentPendingRequest: OpmlRequest? { lock.withLock { pending } }

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

    /// The real `importBegun`'s shape: imports, then always finishes — with no result when cancelled
    /// (during the delay), rethrowing the cancellation.
    func importBegun(xml: String?) async throws {
        var outcome: OpmlResult?
        defer { finish(result: outcome) }
        if importDelayNanoseconds > 0 {
            try await Task.sleep(nanoseconds: importDelayNanoseconds)
        }
        lock.withLock { _importedXml.append(xml) }
        outcome = xml == nil ? OpmlResultImportFailed.shared : importOutcome
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
        lock.withLock {
            _clearCount += 1
            lastResult = nil
        }
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

    private func document(_ xml: String?) -> OpmlRequestImportDocument {
        OpmlRequestImportDocument(xml: xml)
    }

    @Test
    func anUnreadableOpenedFileFinishesAsImportFailed() async throws {
        let fake = FakeOpmlTransferring()
        let observable = OpmlTransferObservable(controller: fake)

        let task = try #require(observable.importDocument(document(nil)))
        await task.value

        let result = try #require(observable.result)
        #expect(observable.statusIsErrorFor(result))
        #expect(fake.importedXml == [nil])
    }

    @Test
    func importDocumentRefusedByTheControllerPutsTheRequestBack() {
        let fake = FakeOpmlTransferring()
        let observable = OpmlTransferObservable(controller: fake)
        // Another operation holds the controller — whatever the observable's mirrored state says.
        #expect(fake.tryBegin(operation: .exporting))
        let request = document("<opml/>")

        #expect(observable.importDocument(request) == nil)

        #expect(fake.importedXml.isEmpty)
        #expect(fake.finishedResults.isEmpty, "the other operation is not finished by this call")
        #expect(observable.isBusy, "the running operation's state is taken from the controller")
        #expect(fake.currentPendingRequest === request, "put back, to run once the other operation finishes")
        #expect(observable.pendingRequest === request)
    }

    @Test
    func aRefusedRequestDoesNotReplaceANewerOne() {
        let fake = FakeOpmlTransferring()
        let observable = OpmlTransferObservable(controller: fake)
        #expect(fake.tryBegin(operation: .exporting))
        let newer = OpmlRequestExportFile.shared
        observable.request(newer)

        #expect(observable.importDocument(document("<opml/>")) == nil)

        #expect(fake.currentPendingRequest === newer, "the last request still wins")
    }

    @Test
    func importDocumentIsNotRefusedByAStaleMirroredBusyState() async throws {
        let fake = FakeOpmlTransferring(importOutcome: OpmlResultImported(added: 1, failed: 0))
        let observable = OpmlTransferObservable(controller: fake)
        // The mirrored state says busy (an operation that has since finished on the controller, before
        // the flow observer caught up); only the controller's own answer decides.
        _ = observable.beginImport()
        fake.finish(result: nil)
        #expect(observable.isBusy)

        let task = try #require(observable.importDocument(document("<opml/>")))
        await task.value

        #expect(fake.importedXml == ["<opml/>"])
        #expect((observable.result as? OpmlResultImported)?.added == 1)
        #expect(!observable.isBusy)
    }

    @Test
    func aSecondRequestArrivingBeforeTheFirstRunStartsIsKeptAndRunNext() async throws {
        let fake = FakeOpmlTransferring(importDelayNanoseconds: 20_000_000)
        let observable = OpmlTransferObservable(controller: fake)

        observable.request(document("<first/>"))
        let first = try #require(observable.takeRequest() as? OpmlRequestImportDocument)
        let firstRun = try #require(observable.importDocument(first))
        // Before the first run's Task has had a chance to start, a second .opml file is opened and
        // the Data tab tries to carry it out.
        observable.request(document("<second/>"))
        #expect(observable.takeRequest() == nil, "not handed out while the first holds the controller")
        #expect(observable.pendingRequest != nil, "the second request is still waiting")

        await firstRun.value
        let second = try #require(observable.takeRequest() as? OpmlRequestImportDocument)
        await observable.importDocument(second)?.value

        #expect(fake.importedXml == ["<first/>", "<second/>"], "both documents were imported, in order")
        #expect(observable.pendingRequest == nil)
        #expect(!observable.isBusy)
    }

    @Test
    func importDocumentShowsBusyRightAwayAndTheControllersResultAfterwards() async throws {
        let fake = FakeOpmlTransferring(importOutcome: OpmlResultImported(added: 2, failed: 0), importDelayNanoseconds: 50_000_000)
        let observable = OpmlTransferObservable(controller: fake)
        // A previous result, which starting a new import must clear at once.
        await observable.importDocument(document("<opml/>"))?.value
        #expect(observable.result != nil)

        let task = try #require(observable.importDocument(document("<opml/>")))
        #expect(observable.isBusy, "busy before the controller's flow catches up")
        #expect(observable.result == nil)
        await task.value

        #expect(!observable.isBusy)
        #expect(!fake.isRunning)
        #expect((observable.result as? OpmlResultImported)?.added == 2)
        #expect(fake.importedXml == ["<opml/>", "<opml/>"], "each run happened once")
        #expect(fake.finishedResults.count == 2, "finished by the controller once per run")
    }

    @Test
    func aCancelledImportDocumentEndsWithNoResult() async throws {
        let fake = FakeOpmlTransferring(importDelayNanoseconds: 5_000_000_000)
        let observable = OpmlTransferObservable(controller: fake)

        let task = try #require(observable.importDocument(document("<opml/>")))
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
        let task = try #require(observable.importDocument(document("<opml/>")))
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
