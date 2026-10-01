import Foundation
import Testing

@MainActor
@Suite
struct ReaderNavigationGateTests {
    private let articleUrl = URL(string: "https://example.com/posts/1")!
    private let embedUrl = URL(string: "https://platform.twitter.com/embed/Tweet.html")!
    private let documentUrl = ArticleNavigationPolicy.documentBaseURL ?? URL(string: "about:blank")!
    private var links: Set<String> { [articleUrl.absoluteString] }

    /// Records every decision a handler is given, so a test can check both the verdict and that
    /// each handler was answered exactly once.
    private final class Recorder {
        var decisions: [ReaderNavigationGate.Decision] = []
        @MainActor
        func handler() -> ReaderNavigationGate.DecisionHandler { { self.decisions.append($0) } }
    }

    @Test
    func decidesAtOnceWhenLinksAreKnown() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: links)
        let outbound = Recorder(), embed = Recorder()

        gate.decide(url: articleUrl, outbound.handler())
        gate.decide(url: embedUrl, embed.handler())

        #expect(outbound.decisions == [.openInBrowser(articleUrl)])
        #expect(embed.decisions == [.allow])
        #expect(gate.pendingCount == 0)
    }

    @Test
    func holdsAClickUntilTheLinksResolveThenOpensItExternally() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: nil)
        let recorder = Recorder()

        gate.decide(url: articleUrl, recorder.handler())
        // Never allowed in the meantime: that would open the article's link inside the reader.
        #expect(recorder.decisions.isEmpty)
        #expect(gate.pendingCount == 1)

        gate.update(documentID: "doc", outboundLinks: links)
        #expect(recorder.decisions == [.openInBrowser(articleUrl)])
        #expect(gate.pendingCount == 0)
    }

    @Test
    func aHeldEmbedNavigationIsAllowedOnceTheLinksResolve() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: nil)
        let recorder = Recorder()

        gate.decide(url: embedUrl, recorder.handler())
        gate.update(documentID: "doc", outboundLinks: links)

        #expect(recorder.decisions == [.allow])
    }

    @Test
    func theDocumentsOwnLoadIsNeverHeld() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: nil)
        let own = Recorder(), missing = Recorder()

        gate.decide(url: documentUrl, own.handler())
        gate.decide(url: nil, missing.handler())

        #expect(own.decisions == [.allow])
        #expect(missing.decisions == [.allow])
        #expect(gate.pendingCount == 0)
    }

    @Test
    func switchingDocumentCancelsWhatThePreviousOneLeftHeld() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "old", outboundLinks: nil)
        let recorder = Recorder()
        gate.decide(url: articleUrl, recorder.handler())

        // Even though the new document's links would count it as outbound.
        gate.update(documentID: "new", outboundLinks: links)

        #expect(recorder.decisions == [.cancel])
        #expect(gate.pendingCount == 0)
    }

    @Test
    func switchingToAnUnresolvedDocumentStillCancels() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "old", outboundLinks: nil)
        let recorder = Recorder()
        gate.decide(url: articleUrl, recorder.handler())

        gate.update(documentID: "new", outboundLinks: nil)

        #expect(recorder.decisions == [.cancel])
        #expect(gate.outboundLinks == nil)
    }

    @Test
    func anUnresolvedReportForTheSameDocumentKeepsItsResolvedLinks() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: links)
        gate.update(documentID: "doc", outboundLinks: nil)
        let recorder = Recorder()

        gate.decide(url: articleUrl, recorder.handler())

        #expect(recorder.decisions == [.openInBrowser(articleUrl)])
    }

    @Test
    func discardingCancelsEveryHeldDecisionOnce() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: nil)
        let first = Recorder(), second = Recorder()
        gate.decide(url: articleUrl, first.handler())
        gate.decide(url: embedUrl, second.handler())

        gate.discardPending()
        gate.update(documentID: "doc", outboundLinks: links)

        #expect(first.decisions == [.cancel])
        #expect(second.decisions == [.cancel])
    }
}
