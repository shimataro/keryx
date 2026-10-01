import Foundation
import KeryxShared
import Testing

/// Fakes `OpmlTransferring` — `KeryxTests` is standalone/non-hosted, so this substitutes for a
/// running `KeryxSdk`'s real `OpmlTransfer`. `@unchecked Sendable` (not an `actor`/`@MainActor`
/// class): `OpmlImportOutcome` isn't itself `Sendable`, and an `actor`'s own isolated
/// implementation of `importOpml(xml:)` would then be barred from returning it across the
/// isolation boundary — a restriction the real `OpmlTransfer` (a plain, non-isolated conformance)
/// never hits. This class's own tiny bit of mutable state (`importCallCount`) is protected by a
/// lock instead, so it's genuinely safe to mark `Sendable` despite the compiler not verifying it.
final class FakeOpmlTransferring: OpmlTransferring, @unchecked Sendable {
    private let exportOpmlResult: String
    private let importResult: Swift.Result<OpmlImportOutcome, Error>
    private let importDelayNanoseconds: UInt64
    private let lock = NSLock()
    private var _importCallCount = 0
    var importCallCount: Int {
        lock.withLock { _importCallCount }
    }

    init(
        exportOpmlResult: String = "",
        importResult: Swift.Result<OpmlImportOutcome, Error> = .success(OpmlImportOutcome(added: 0, failed: 0)),
        importDelayNanoseconds: UInt64 = 0
    ) {
        self.exportOpmlResult = exportOpmlResult
        self.importResult = importResult
        self.importDelayNanoseconds = importDelayNanoseconds
    }

    func exportOpml() -> String { exportOpmlResult }

    func importOpml(xml: String) async throws -> OpmlImportOutcome {
        if importDelayNanoseconds > 0 {
            try? await Task.sleep(nanoseconds: importDelayNanoseconds)
        }
        lock.withLock { _importCallCount += 1 }
        switch importResult {
        case .success(let outcome): return outcome
        case .failure(let error): throw error
        }
    }
}

private struct FakeImportError: Error {}

@MainActor
@Suite
struct OpmlTransferObservableTests {
    private func writeTempOpmlFile() throws -> URL {
        let url = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString + ".opml")
        try "<opml></opml>".write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    @Test
    func exportDocumentIsNilWhileBusy() async throws {
        let fake = FakeOpmlTransferring(importDelayNanoseconds: 50_000_000)
        let observable = OpmlTransferObservable(opml: fake)
        let url = try writeTempOpmlFile()
        defer { try? FileManager.default.removeItem(at: url) }

        let task = try #require(observable.importOpml(from: url))
        #expect(observable.isBusy)
        #expect(observable.exportDocument() == nil)

        await task.value
        #expect(!observable.isBusy)
    }

    @Test
    func secondImportWhileBusyIsIgnored() async throws {
        let fake = FakeOpmlTransferring(importDelayNanoseconds: 50_000_000)
        let observable = OpmlTransferObservable(opml: fake)
        let url = try writeTempOpmlFile()
        defer { try? FileManager.default.removeItem(at: url) }

        let task = try #require(observable.importOpml(from: url))
        #expect(observable.importOpml(from: url) == nil)
        await task.value

        #expect(fake.importCallCount == 1)
    }

    @Test
    func successfulImportWithNoFailuresReportsSuccess() async throws {
        let fake = FakeOpmlTransferring(importResult: .success(OpmlImportOutcome(added: 3, failed: 0)))
        let observable = OpmlTransferObservable(opml: fake)
        let url = try writeTempOpmlFile()
        defer { try? FileManager.default.removeItem(at: url) }

        let task = try #require(observable.importOpml(from: url))
        await task.value

        #expect(!observable.statusIsError)
        #expect(observable.statusMessage != nil)
    }

    @Test
    func partialFailureReportsFailure() async throws {
        let fake = FakeOpmlTransferring(importResult: .success(OpmlImportOutcome(added: 2, failed: 1)))
        let observable = OpmlTransferObservable(opml: fake)
        let url = try writeTempOpmlFile()
        defer { try? FileManager.default.removeItem(at: url) }

        let task = try #require(observable.importOpml(from: url))
        await task.value

        #expect(observable.statusIsError)
    }

    @Test
    func thrownErrorReportsFailure() async throws {
        let fake = FakeOpmlTransferring(importResult: .failure(FakeImportError()))
        let observable = OpmlTransferObservable(opml: fake)
        let url = try writeTempOpmlFile()
        defer { try? FileManager.default.removeItem(at: url) }

        let task = try #require(observable.importOpml(from: url))
        await task.value

        #expect(observable.statusIsError)
    }

    @Test
    func panelFailureReportsFailure() {
        let observable = OpmlTransferObservable(opml: FakeOpmlTransferring())
        observable.reportImportPanelFailure()
        #expect(observable.statusIsError)
    }
}
