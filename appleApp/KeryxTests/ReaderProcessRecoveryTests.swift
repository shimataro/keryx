import Testing

@MainActor
@Suite
struct ReaderProcessRecoveryTests {
    @Test
    func theFirstTerminationReloadsTheDocument() {
        let recovery = ReaderProcessRecovery()
        #expect(recovery.processTerminated() == .reload)
    }

    @Test
    func aSecondTerminationGivesUpEvenAfterTheReloadCommitted() {
        let recovery = ReaderProcessRecovery()
        _ = recovery.processTerminated()
        #expect(recovery.processTerminated() == .giveUp)
        #expect(recovery.processTerminated() == .giveUp)
    }

    @Test
    func loadingAnotherDocumentReloadsOnTheNextTermination() {
        let recovery = ReaderProcessRecovery()
        _ = recovery.processTerminated()
        recovery.documentReplaced()
        #expect(recovery.processTerminated() == .reload)
    }

    @Test
    func loadingAnotherDocumentAfterGivingUpReloadsOnTheNextTermination() {
        let recovery = ReaderProcessRecovery()
        _ = recovery.processTerminated()
        _ = recovery.processTerminated()
        recovery.documentReplaced()
        #expect(recovery.processTerminated() == .reload)
        #expect(recovery.processTerminated() == .giveUp)
    }
}
