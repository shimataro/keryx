import Testing

@MainActor
@Suite
struct ReaderProcessRecoveryTests {
    @Test
    func theFirstTerminationReloadsTheDocument() {
        let recovery = ReaderProcessRecovery()
        #expect(recovery.shouldReloadAfterTermination())
    }

    @Test
    func aTerminationBeforeTheReloadCommitsDoesNotReloadAgain() {
        let recovery = ReaderProcessRecovery()
        _ = recovery.shouldReloadAfterTermination()
        #expect(!recovery.shouldReloadAfterTermination())
    }

    @Test
    func aTerminationAfterTheReloadCommittedReloadsAgain() {
        let recovery = ReaderProcessRecovery()
        _ = recovery.shouldReloadAfterTermination()
        recovery.documentCommitted()
        #expect(recovery.shouldReloadAfterTermination())
    }

    @Test
    func loadingAnotherDocumentReloadsOnTheNextTermination() {
        let recovery = ReaderProcessRecovery()
        _ = recovery.shouldReloadAfterTermination()
        recovery.documentReplaced()
        #expect(recovery.shouldReloadAfterTermination())
    }
}
