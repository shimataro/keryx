import Foundation

/// Applies `ArticleNavigationPolicy.isOutboundClick` to the reader's navigations while the current
/// document's outbound-link set may still be being extracted — kept free of WebKit so the standalone
/// `KeryxTests` bundle can compile it directly.
///
/// The reader loads a document before `ReaderDocument.extractOutboundLinks` has parsed its body, so
/// a navigation can arrive while the set is still unknown. Answering it with "allow" would open a
/// link click inside the reader instead of the external browser, so such a decision is held and
/// answered only once the set lands (`update`), with exactly the verdict it would have got had the
/// set been known all along. A held decision the document never resolves — it was replaced, or the
/// reader went away — is answered `.cancel` (`update` with a new document, `discardPending`).
///
/// The reader is not a browser: a navigation that would replace the whole page is never shown in it.
/// Only an embed's own navigation inside an iframe loads in place, so script-based SNS embeds keep
/// working; anything that would swap the top-level page opens externally (a click) or is dropped (no
/// click — even to one of the article's own links). Navigating freely inside the app would also make
/// it an "unrestricted web access" browser for App Store age-rating purposes. A "click" a script
/// synthesizes never reaches this gate: `ArticleWebView` cancels it in the page, since WebKit reports
/// it as `.linkActivated` exactly like a person's.
@MainActor
final class ReaderNavigationGate {
    enum Decision: Equatable {
        /// A genuine outbound click: open `url` in the external browser, and cancel the navigation.
        case openInBrowser(URL)
        /// The document's own load, or an embed's navigation inside an iframe: let it load in the reader.
        case allow
        /// The document the navigation belonged to is gone.
        case cancel
    }

    /// What kind of navigation WebKit reported, reduced to what the policy needs (and free of WebKit
    /// so the test bundle can build one).
    enum Kind: Equatable {
        /// Inside an iframe — an embed doing its own thing.
        case subframe
        /// A user click that would replace the top-level page. Includes a navigation with no target
        /// frame (`target="_blank"`, `window.open`), which would otherwise open a new page.
        case mainFrameClick
        /// A top-level navigation nobody clicked: a script setting `location`, a meta refresh, a
        /// form submission, a reload.
        case mainFrameOther

        /// Reduces a `WKNavigationAction`: `targetIsMainFrame` is its `targetFrame?.isMainFrame`
        /// (`nil` for no target frame — `target="_blank"`, `window.open` — which counts as the main
        /// frame, since it would open a new top-level page), `isLinkActivated` whether its
        /// `navigationType` is `.linkActivated`.
        init(targetIsMainFrame: Bool?, isLinkActivated: Bool) {
            if targetIsMainFrame == false {
                self = .subframe
            } else {
                self = isLinkActivated ? .mainFrameClick : .mainFrameOther
            }
        }
    }

    typealias DecisionHandler = @MainActor (Decision) -> Void

    private var documentID: String?
    /// `nil` while the current document's links are still being extracted.
    private(set) var outboundLinks: Set<String>? = []
    private var pending: [(url: URL?, kind: Kind, handler: DecisionHandler)] = []
    /// Whether the reader's own `loadHTMLString` navigation is in flight. Only then is a top-level
    /// `about:blank` the document itself; at any other time it is a script replacing the page.
    private var awaitingDocumentLoad = false

    /// How many decisions are currently held (for tests).
    var pendingCount: Int { pending.count }

    /// Reports the document currently shown (`documentID`, which must change whenever its links
    /// could) and its link set — `nil` while still unresolved.
    ///
    /// A different document cancels whatever the previous one left held. For the same document, a
    /// resolved set answers every held decision; an unresolved report after a resolved one keeps the
    /// resolved set, since an identical document has identical links.
    func update(documentID: String, outboundLinks: Set<String>?) {
        if documentID != self.documentID {
            self.documentID = documentID
            self.outboundLinks = outboundLinks
            flush(with: .cancel)
        } else if self.outboundLinks == nil {
            self.outboundLinks = outboundLinks
        }
        guard let links = self.outboundLinks else { return }
        let held = pending
        pending = []
        for decision in held {
            decision.handler(self.decision(url: decision.url, kind: decision.kind, outboundLinks: links))
        }
    }

    /// Marks the start of the reader's own document load; call right before `loadHTMLString`.
    func beginDocumentLoad() {
        awaitingDocumentLoad = true
    }

    /// Marks the document load as committed or failed, so a later `about:blank` is no longer taken
    /// for it.
    func endDocumentLoad() {
        awaitingDocumentLoad = false
    }

    /// Decides a navigation to `url` (of `kind`) now if the link set is known, or holds it until it is.
    ///
    /// The document's own `loadHTMLString` navigation (`about:blank`, see
    /// `ArticleNavigationPolicy.documentBaseURL`, announced by `beginDocumentLoad`) is never held — holding it would keep the document
    /// from loading until its links were extracted, the very wait this gate exists to remove.
    func decide(url: URL?, kind: Kind, _ handler: @escaping DecisionHandler) {
        if let links = outboundLinks {
            handler(decision(url: url, kind: kind, outboundLinks: links))
        } else if isDocumentLoad(url, kind: kind) {
            handler(.allow)
        } else {
            pending.append((url, kind, handler))
        }
    }

    /// Cancels every held decision — the reader is going away.
    func discardPending() {
        flush(with: .cancel)
    }

    private func flush(with decision: Decision) {
        let held = pending
        pending = []
        for entry in held { entry.handler(decision) }
    }

    private func decision(url: URL?, kind: Kind, outboundLinks: Set<String>) -> Decision {
        if isDocumentLoad(url, kind: kind) { return .allow }
        // Nobody clicked: never open anything, even a URL that is one of the article's own links.
        if kind == .mainFrameOther { return .cancel }
        if let url, ArticleNavigationPolicy.isOutboundClick(url: url, outboundLinks: outboundLinks) {
            return .openInBrowser(url)
        }
        switch kind {
        case .subframe: return .allow
        case .mainFrameClick: return url.map(Decision.openInBrowser) ?? .cancel
        case .mainFrameOther: return .cancel
        }
    }

    /// The reader's own load: a nobody-clicked top-level navigation to the blank base URL while
    /// `loadHTMLString` is in flight. A script setting `location = "about:blank"` later, or a click on
    /// such a link, is not it.
    private func isDocumentLoad(_ url: URL?, kind: Kind) -> Bool {
        guard awaitingDocumentLoad, kind == .mainFrameOther else { return false }
        guard let url else { return true }
        return url == ArticleNavigationPolicy.documentBaseURL || url.absoluteString == "about:blank"
    }
}
