import Foundation

/// Decides what the reader does with each of its `WKWebView` navigations — kept free of WebKit so the
/// standalone `KeryxTests` bundle can compile it directly.
///
/// The reader is not a browser: a navigation that would replace the whole page is never shown in it.
/// Only an embed's own navigation inside an iframe loads in place, so script-based SNS embeds keep
/// working; anything that would swap the top-level page opens externally (a click) or is dropped (no
/// click — even to one of the article's own links). Navigating freely inside the app would also make
/// it an "unrestricted web access" browser for App Store age-rating purposes. A "click" a script
/// synthesizes is cancelled in the page by `ArticleWebView`, since WebKit reports it as
/// `.linkActivated` exactly like a person's (see "A feed body's own JavaScript runs inside the
/// article reader" in `docs/known-issues.md` for what that does not catch).
@MainActor
final class ReaderNavigationGate {
    enum Decision: Equatable {
        /// A click that would replace the top-level page: open `url` in the external browser, and
        /// cancel the navigation.
        case openInBrowser(URL)
        /// The document's own load, or an embed's navigation inside an iframe: let it load in the reader.
        case allow
        /// A navigation nobody asked for.
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

    /// Whether the reader's own `loadHTMLString` navigation is in flight. Only then is a top-level
    /// `about:blank` (see `ArticleNavigationPolicy.documentBaseURL`) the document itself; at any other
    /// time it is a script replacing the page.
    private var awaitingDocumentLoad = false

    /// Marks the start of the reader's own document load; call right before `loadHTMLString`.
    func beginDocumentLoad() {
        awaitingDocumentLoad = true
    }

    /// Marks the document load as committed or failed, so a later `about:blank` is no longer taken
    /// for it.
    func endDocumentLoad() {
        awaitingDocumentLoad = false
    }

    /// Decides a navigation to `url` (of `kind`).
    func decide(url: URL?, kind: Kind) -> Decision {
        if isDocumentLoad(url, kind: kind) { return .allow }
        switch kind {
        case .subframe: return .allow
        case .mainFrameClick: return url.map(Decision.openInBrowser) ?? .cancel
        // Nobody clicked: never open anything, even a URL that is one of the article's own links.
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
