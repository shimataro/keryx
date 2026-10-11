import Foundation
import Testing

@MainActor
@Suite
struct ReaderNavigationGateTests {
    private let articleUrl = URL(string: "https://example.com/posts/1")!
    private let embedUrl = URL(string: "https://platform.twitter.com/embed/Tweet.html")!
    private let otherSiteUrl = URL(string: "https://other.example.org/landing")!
    private let documentUrl = URL(string: "about:blank")!

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

    @Test
    func aClickThatWouldReplaceThePageOpensExternally() {
        let gate = ReaderNavigationGate()

        // One of the article's own links, or a script-built link / an iframe's `target="_top"` link.
        #expect(gate.decide(url: articleUrl, kind: .mainFrameClick) == .openInBrowser(articleUrl))
        #expect(gate.decide(url: otherSiteUrl, kind: .mainFrameClick) == .openInBrowser(otherSiteUrl))
    }

    @Test
    func aClickWithNoUrlIsDropped() {
        #expect(ReaderNavigationGate().decide(url: nil, kind: .mainFrameClick) == .cancel)
    }

    @Test
    func aTopLevelNavigationNobodyClickedIsDroppedEvenToAnArticleLink() {
        let gate = ReaderNavigationGate()

        // `location = …` or a meta refresh: nobody asked for the browser, and the article stays.
        #expect(gate.decide(url: otherSiteUrl, kind: .mainFrameOther) == .cancel)
        #expect(gate.decide(url: articleUrl, kind: .mainFrameOther) == .cancel)
    }

    // MARK: - Iframes

    @Test
    func anEmbedsOwnNavigationInsideAnIframeStillLoads() {
        #expect(ReaderNavigationGate().decide(url: embedUrl, kind: .subframe) == .allow)
    }

    @Test
    func anIframeNavigatingToTheArticlesUrlLoadsInPlaceInsteadOfOpeningTheBrowser() {
        // An iframe pointed at an article link used to be mistaken for a click on it.
        #expect(ReaderNavigationGate().decide(url: articleUrl, kind: .subframe) == .allow)
    }

    // MARK: - The document's own load

    @Test
    func theDocumentsOwnLoadIsAllowedWhileItIsInFlight() {
        let gate = ReaderNavigationGate()
        gate.beginDocumentLoad()

        #expect(gate.decide(url: documentUrl, kind: .mainFrameOther) == .allow)
        #expect(gate.decide(url: nil, kind: .mainFrameOther) == .allow)
    }

    @Test
    func aLaterScriptNavigationToBlankIsDropped() {
        let gate = ReaderNavigationGate()
        gate.beginDocumentLoad()
        gate.endDocumentLoad()

        // `location = "about:blank"` after the document committed would blank the article.
        #expect(gate.decide(url: documentUrl, kind: .mainFrameOther) == .cancel)
    }

    @Test
    func blankIsDroppedWhenNoDocumentLoadWasAnnounced() {
        #expect(ReaderNavigationGate().decide(url: documentUrl, kind: .mainFrameOther) == .cancel)
    }

    @Test
    func aClickIsNeverTakenForTheDocumentLoadEvenWhileOneIsInFlight() {
        let gate = ReaderNavigationGate()
        gate.beginDocumentLoad()

        // Not allowed in place; the click is handed to the external-browser path, which refuses a
        // non-http(s) URL.
        #expect(gate.decide(url: documentUrl, kind: .mainFrameClick) == .openInBrowser(documentUrl))
    }
}
