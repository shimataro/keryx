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
@MainActor
final class ReaderNavigationGate {
    enum Decision: Equatable {
        /// A genuine outbound click: open `url` in the external browser, and cancel the navigation.
        case openInBrowser(URL)
        /// Anything else (an SNS embed's own navigation): let it load inside the reader.
        case allow
        /// The document the navigation belonged to is gone.
        case cancel
    }

    typealias DecisionHandler = @MainActor (Decision) -> Void

    private var documentID: String?
    /// `nil` while the current document's links are still being extracted.
    private(set) var outboundLinks: Set<String>? = []
    private var pending: [(url: URL?, handler: DecisionHandler)] = []

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
            decision.handler(Self.decision(url: decision.url, outboundLinks: links))
        }
    }

    /// Decides a navigation to `url` now if the link set is known, or holds it until it is.
    ///
    /// The document's own `loadHTMLString` navigation (`about:blank`, see
    /// `ArticleNavigationPolicy.documentBaseURL`) is never held — holding it would keep the document
    /// from loading until its links were extracted, the very wait this gate exists to remove.
    func decide(url: URL?, _ handler: @escaping DecisionHandler) {
        if let links = outboundLinks {
            handler(Self.decision(url: url, outboundLinks: links))
        } else if Self.isDocumentLoad(url) {
            handler(Self.decision(url: url, outboundLinks: []))
        } else {
            pending.append((url, handler))
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

    private static func decision(url: URL?, outboundLinks: Set<String>) -> Decision {
        if let url, ArticleNavigationPolicy.isOutboundClick(url: url, outboundLinks: outboundLinks) {
            return .openInBrowser(url)
        }
        return .allow
    }

    private static func isDocumentLoad(_ url: URL?) -> Bool {
        guard let url else { return true }
        return url == ArticleNavigationPolicy.documentBaseURL || url.absoluteString == "about:blank"
    }
}
