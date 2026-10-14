import SwiftUI
import WebKit

/// Wraps `WKWebView` for the article reader — see "Article Reader (native WebView)" in
/// `docs/app-architecture.md`. Loads pre-wrapped HTML (never raw article `content` — see
/// `ArticleWebViewHtml.kt`) and gates navigation: a click that would replace the top-level page is
/// opened in the external browser, while a top-level navigation nobody clicked is dropped. Only an SNS
/// embed's own navigation inside an iframe loads in place (see `ReaderNavigationGate`). A click a
/// script synthesizes is cancelled in the page (`syntheticClickGuard`).
struct ArticleWebView {
    let html: String

    @MainActor
    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    @MainActor
    private func configure(_ webView: WKWebView, coordinator: Coordinator) {
        webView.navigationDelegate = coordinator
        // An empty document is `ReaderDocument.empty`, held only until the first real one is built.
        guard !html.isEmpty else { return }
        // SwiftUI calls update on every re-evaluation; reloading an unchanged document would reset
        // the scroll position.
        guard html != coordinator.loadedHtml else { return }
        coordinator.reloadedHtml = nil
        coordinator.recovery.documentReplaced()
        coordinator.load(html, in: webView)
    }

    /// Cancels a link activation no person caused — a feed body's or an embed's script calling
    /// `a.click()` or dispatching a synthetic click — in every frame. WebKit reports such a click as
    /// `.linkActivated` just like a real one, so without this a script could open any page in the
    /// external browser (or another app) the moment an article is shown, or merely preloaded for a
    /// swipe. Any element carrying `href` or `xlink:href` counts as a link, so SVG and MathML anchors
    /// are covered along with HTML's `<a>` / `<area>`. It runs in its own content world, before any
    /// page script, so the page can neither remove the listener nor replace the `preventDefault` it
    /// calls. `preventDefault` is a no-op on a non-cancelable event, which this therefore cannot stop
    /// (see the same known-issues entry).
    @MainActor
    private static let syntheticClickGuard = WKUserScript(
        source: """
            (() => {
              const preventDefault = Event.prototype.preventDefault;
              window.addEventListener('click', (event) => {
                if (event.isTrusted) return;
                for (const node of event.composedPath()) {
                  if (node instanceof Element && (node.hasAttribute('href')
                      || node.hasAttributeNS('http://www.w3.org/1999/xlink', 'href'))) {
                    preventDefault.call(event);
                    return;
                  }
                }
              }, true);
            })();
            """,
        injectionTime: .atDocumentStart,
        forMainFrameOnly: false,
        in: .defaultClient
    )

    @MainActor
    private static func installSyntheticClickGuard(on webView: WKWebView) {
        webView.configuration.userContentController.addUserScript(syntheticClickGuard)
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        let gate = ReaderNavigationGate()
        let recovery = ReaderProcessRecovery()
        var loadedHtml: String?
        /// The document `load` last retried after a foreign commit replaced it, so a page that keeps
        /// replacing itself is retried once rather than forever.
        var reloadedHtml: String?
        /// The navigation `loadHTMLString` started for the current document, so only its commit or
        /// failure ends the gate's wait (another WebView load, or a superseded one, must not).
        var documentNavigation: WKNavigation?

        /// Loads `html` as the reader's document. The gate cannot tell a previous article's script
        /// navigating to `about:blank` from this load while it is in flight, so `didCommit` reloads
        /// the document if such a navigation replaced it.
        func load(_ html: String, in webView: WKWebView) {
            loadedHtml = html
            gate.beginDocumentLoad()
            documentNavigation = webView.loadHTMLString(html, baseURL: ReaderNavigationGate.documentBaseURL)
            // No navigation was started, so nothing will ever end the wait.
            if documentNavigation == nil { gate.endDocumentLoad() }
        }

        func webView(_ webView: WKWebView, didCommit navigation: WKNavigation!) {
            if navigation !== documentNavigation, let html = loadedHtml, reloadedHtml != html {
                // Something other than our document committed (a script's `about:blank` slipped
                // through while the load was in flight), so the article is gone: put it back.
                reloadedHtml = html
                load(html, in: webView)
                return
            }
            if navigation === documentNavigation { recovery.documentCommitted() }
            endDocumentLoad(of: navigation)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            endDocumentLoad(of: navigation)
        }

        private func endDocumentLoad(of navigation: WKNavigation?) {
            guard let navigation, navigation === documentNavigation else { return }
            gate.endDocumentLoad()
            documentNavigation = nil
        }

        /// iOS may kill WebKit's WebContent process while the app is in the background (the
        /// external browser a link opened is in front, say), leaving the view blank. `reload()` would
        /// not bring the document back (its URL is `about:blank`, and the gate cancels it anyway), so
        /// the held HTML is loaded again.
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            gate.endDocumentLoad()
            documentNavigation = nil
            reloadedHtml = nil
            guard let html = loadedHtml else { return }
            if recovery.shouldReloadAfterTermination() {
                load(html, in: webView)
            } else {
                // It went down again before even committing: stop here, and let the next update
                // (showing the article again) load it afresh rather than skip it as already loaded.
                loadedHtml = nil
            }
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            let kind = ReaderNavigationGate.Kind(
                targetIsMainFrame: navigationAction.targetFrame?.isMainFrame,
                isLinkActivated: navigationAction.navigationType == .linkActivated
            )
            switch gate.decide(url: navigationAction.request.url, kind: kind) {
            case let .openInBrowser(url):
                // The URL comes from the feed: only http(s) is ever handed to the OS.
                openInBrowserIfAllowed(url.absoluteString)
                decisionHandler(.cancel)
            case .allow:
                decisionHandler(.allow)
            case .cancel:
                decisionHandler(.cancel)
            }
        }
    }
}

#if os(macOS)
extension ArticleWebView: NSViewRepresentable {
    func makeNSView(context: Context) -> WKWebView {
        let webView = WKWebView()
        Self.installSyntheticClickGuard(on: webView)
        return webView
    }

    func updateNSView(_ webView: WKWebView, context: Context) {
        configure(webView, coordinator: context.coordinator)
    }
}
#else
extension ArticleWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let webView = ReaderWebViewWarmUp.takeWarmedWebView() ?? WKWebView()
        Self.installSyntheticClickGuard(on: webView)
        // Transparent until the first document paints, so a page whose body is still loading shows
        // the reader's own background (`ReaderPageView`) rather than WebKit's default white — the
        // document itself paints the same background once loaded.
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        configure(webView, coordinator: context.coordinator)
    }
}

/// Starts WebKit's out-of-process machinery (its WebContent/networking processes) once the app is
/// idle after launch, so the first article opened on iOS does not also pay for that cold start. The
/// warmed view is handed to the first reader page that asks for one rather than thrown away, so the
/// process it launched is the one that page actually uses.
@MainActor
enum ReaderWebViewWarmUp {
    /// Long enough after the reader first appears for launch-time work to have settled.
    private static let idleDelay: Duration = .seconds(1)

    private static var started = false
    /// Set once a reader page has created its WebView: WebKit is warm from then on.
    private static var webViewCreated = false
    private static var warmed: WKWebView?

    /// Warms up once per process, after `idleDelay`. A caller cancelled during the delay leaves the
    /// warm-up to the next one.
    static func scheduleOnce() async {
        guard !started, !webViewCreated else { return }
        started = true
        do {
            try await Task.sleep(for: idleDelay)
        } catch {
            started = false
            return
        }
        guard !webViewCreated else { return }
        let webView = WKWebView()
        webView.loadHTMLString("", baseURL: nil)
        warmed = webView
    }

    /// The warmed view, if one is waiting — at most once.
    static func takeWarmedWebView() -> WKWebView? {
        webViewCreated = true
        defer { warmed = nil }
        return warmed
    }
}
#endif
