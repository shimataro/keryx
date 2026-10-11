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

        gate.decide(url: articleUrl, kind: .mainFrameClick, outbound.handler())
        gate.decide(url: embedUrl, kind: .subframe, embed.handler())

        #expect(outbound.decisions == [.openInBrowser(articleUrl)])
        #expect(embed.decisions == [.allow])
        #expect(gate.pendingCount == 0)
    }

    @Test
    func holdsAClickUntilTheLinksResolveThenOpensItExternally() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: nil)
        let recorder = Recorder()

        gate.decide(url: articleUrl, kind: .mainFrameClick, recorder.handler())
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

        gate.decide(url: embedUrl, kind: .subframe, recorder.handler())
        gate.update(documentID: "doc", outboundLinks: links)

        #expect(recorder.decisions == [.allow])
    }

    @Test
    func theDocumentsOwnLoadIsNeverHeld() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: nil)
        let own = Recorder(), missing = Recorder()

        gate.decide(url: documentUrl, kind: .mainFrameOther, own.handler())
        gate.decide(url: nil, kind: .mainFrameOther, missing.handler())

        #expect(own.decisions == [.allow])
        #expect(missing.decisions == [.allow])
        #expect(gate.pendingCount == 0)
    }

    @Test
    func switchingDocumentCancelsWhatThePreviousOneLeftHeld() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "old", outboundLinks: nil)
        let recorder = Recorder()
        gate.decide(url: articleUrl, kind: .mainFrameClick, recorder.handler())

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
        gate.decide(url: articleUrl, kind: .mainFrameClick, recorder.handler())

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

        gate.decide(url: articleUrl, kind: .mainFrameClick, recorder.handler())

        #expect(recorder.decisions == [.openInBrowser(articleUrl)])
    }

    @Test
    func discardingCancelsEveryHeldDecisionOnce() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: nil)
        let first = Recorder(), second = Recorder()
        gate.decide(url: articleUrl, kind: .mainFrameClick, first.handler())
        gate.decide(url: embedUrl, kind: .subframe, second.handler())

        gate.discardPending()
        gate.update(documentID: "doc", outboundLinks: links)

        #expect(first.decisions == [.cancel])
        #expect(second.decisions == [.cancel])
    }

    // MARK: - Kind

    @Test
    func aNavigationInsideAnIframeIsASubframeWhateverStartedIt() {
        #expect(ReaderNavigationGate.Kind(targetIsMainFrame: false, isLinkActivated: true) == .subframe)
        #expect(ReaderNavigationGate.Kind(targetIsMainFrame: false, isLinkActivated: false) == .subframe)
    }

    @Test
    func aLinkClickTargetingTheMainFrameOrANewWindowIsAMainFrameClick() {
        #expect(ReaderNavigationGate.Kind(targetIsMainFrame: true, isLinkActivated: true) == .mainFrameClick)
        // `target="_blank"`: no target frame.
        #expect(ReaderNavigationGate.Kind(targetIsMainFrame: nil, isLinkActivated: true) == .mainFrameClick)
    }

    @Test
    func anyOtherMainFrameOrNewWindowNavigationIsMainFrameOther() {
        #expect(ReaderNavigationGate.Kind(targetIsMainFrame: true, isLinkActivated: false) == .mainFrameOther)
        #expect(ReaderNavigationGate.Kind(targetIsMainFrame: nil, isLinkActivated: false) == .mainFrameOther)
    }

    // MARK: - Top-level navigations

    private let otherSiteUrl = URL(string: "https://other.example.org/landing")!

    @Test
    func aClickThatWouldReplaceThePageOpensExternally() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: links)
        let recorder = Recorder()

        // A script-built link, or an iframe's `target="_top"` link: not among the extracted links.
        gate.decide(url: otherSiteUrl, kind: .mainFrameClick, recorder.handler())

        #expect(recorder.decisions == [.openInBrowser(otherSiteUrl)])
    }

    @Test
    func aTopLevelNavigationNobodyClickedIsDropped() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: links)
        let recorder = Recorder()

        // `location = …` or a meta refresh: the article stays on screen.
        gate.decide(url: otherSiteUrl, kind: .mainFrameOther, recorder.handler())

        #expect(recorder.decisions == [.cancel])
    }

    @Test
    func anEmbedsOwnNavigationInsideAnIframeStillLoads() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: links)
        let recorder = Recorder()

        gate.decide(url: otherSiteUrl, kind: .subframe, recorder.handler())

        #expect(recorder.decisions == [.allow])
    }

    @Test
    func aHeldTopLevelNavigationGetsItsVerdictOnceTheLinksResolve() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: nil)
        let clicked = Recorder(), scripted = Recorder(), embed = Recorder()

        gate.decide(url: otherSiteUrl, kind: .mainFrameClick, clicked.handler())
        gate.decide(url: otherSiteUrl, kind: .mainFrameOther, scripted.handler())
        gate.decide(url: otherSiteUrl, kind: .subframe, embed.handler())
        #expect(gate.pendingCount == 3)

        gate.update(documentID: "doc", outboundLinks: links)

        #expect(clicked.decisions == [.openInBrowser(otherSiteUrl)])
        #expect(scripted.decisions == [.cancel])
        #expect(embed.decisions == [.allow])
    }

    @Test
    func theDocumentsOwnLoadIsAllowedWhateverItsKind() {
        let gate = ReaderNavigationGate()
        gate.update(documentID: "doc", outboundLinks: links)
        let recorder = Recorder()

        gate.decide(url: documentUrl, kind: .mainFrameOther, recorder.handler())

        #expect(recorder.decisions == [.allow])
    }
}
