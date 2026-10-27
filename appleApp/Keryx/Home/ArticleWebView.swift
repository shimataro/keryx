import SwiftUI
import WebKit

/// Wraps `WKWebView` for the article reader — see "Article Reader (native WebView)" in
/// `docs/app-architecture.md`. Loads pre-wrapped HTML (never raw article `content` — see
/// `ArticleWebViewHtml.kt`) and gates navigation: a click that would replace the top-level page is
/// opened in the external browser, while a top-level navigation nobody clicked is dropped. Only an SNS
/// embed's own navigation inside an iframe loads in place (see `ReaderNavigationGate`). A click a
/// script synthesizes is cancelled in the page (`syntheticClickGuard`). The page reports its scroll
/// position (`scrollReporter`), so a document loaded again after WebContent died returns to where the
/// user was (`ReaderScrollRestoration`).
struct ArticleWebView {
    let html: String
    /// The in-app theme, which picks the iOS scroll indicator (see `ReaderScrollIndicator`).
    let isDarkTheme: Bool
    /// Where to scroll the first document this view loads once it has finished loading (a position
    /// restored from before the app was terminated — `ReaderScrollPosition`), or nil for the top.
    var initialScrollFraction: Double?
    /// Told each position the user scrolls the document to, as a fraction of its scrollable height.
    var onScroll: ((Double) -> Void)?

    @MainActor
    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    @MainActor
    private func configure(_ webView: WKWebView, coordinator: Coordinator) {
        webView.navigationDelegate = coordinator
        coordinator.onScroll = onScroll
        // An empty document is `ReaderDocument.empty`, held only until the first real one is built.
        guard !html.isEmpty else { return }
        // SwiftUI calls update on every re-evaluation; reloading an unchanged document would reset
        // the scroll position.
        guard html != coordinator.loadedHtml else { return }
        coordinator.reloadedHtml = nil
        coordinator.recovery.documentReplaced()
        coordinator.scrollRestoration.documentReplaced(
            restoring: coordinator.loadedHtml == nil ? initialScrollFraction : nil
        )
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

    /// The name the page posts scroll messages under (`scrollReporter`).
    private static let scrollMessageName = "keryxReaderScroll"

    /// Reports the main frame's vertical scroll position to `Coordinator` as a fraction of its
    /// scrollable height (throttled), the user's own scroll/tap/key input, and the content's height
    /// changing, and defines the function the restore scrolls with. Its handler is registered in the
    /// same content world, so the page's own scripts can neither post to it nor call that function;
    /// only trusted (person-caused) input counts as the user's.
    @MainActor
    private static let scrollReporter = WKUserScript(
        source: """
            (() => {
              const post = (message) => window.webkit.messageHandlers.\(scrollMessageName).postMessage(message);
              const root = () => document.scrollingElement || document.documentElement;
              const range = () => root().scrollHeight - window.innerHeight;
              let timer = null;
              window.addEventListener('scroll', () => {
                if (timer !== null) return;
                timer = setTimeout(() => {
                  timer = null;
                  const scrollable = range();
                  post({ kind: 'scroll', fraction: scrollable > 0 ? window.scrollY / scrollable : 0 });
                }, 200);
              }, { passive: true });
              for (const type of ['touchstart', 'wheel', 'keydown', 'mousedown']) {
                window.addEventListener(type, (event) => {
                  if (event.isTrusted) post({ kind: 'user' });
                }, { capture: true, passive: true });
              }
              let height = root().scrollHeight;
              new ResizeObserver(() => {
                const current = root().scrollHeight;
                if (current === height) return;
                height = current;
                post({ kind: 'resize' });
              }).observe(document.body || document.documentElement);
              window.keryxScrollToFraction = (fraction) => {
                const scrollable = range();
                if (scrollable > 0) window.scrollTo(0, fraction * scrollable);
              };
            })();
            """,
        injectionTime: .atDocumentEnd,
        forMainFrameOnly: true,
        in: .defaultClient
    )

    @MainActor
    private static func installUserScripts(on webView: WKWebView, coordinator: Coordinator) {
        let controller = webView.configuration.userContentController
        controller.addUserScript(syntheticClickGuard)
        controller.addUserScript(scrollReporter)
        // The controller retains its handlers for as long as the view lives; the weak proxy keeps it
        // from also keeping the coordinator alive.
        controller.add(ScrollMessageProxy(coordinator), contentWorld: .defaultClient, name: scrollMessageName)
    }

    /// Forwards the page's scroll messages to the coordinator without retaining it.
    @MainActor
    private final class ScrollMessageProxy: NSObject, WKScriptMessageHandler {
        private weak var coordinator: Coordinator?

        init(_ coordinator: Coordinator) {
            self.coordinator = coordinator
        }

        func userContentController(_ userContentController: WKUserContentController, didReceive message: WKScriptMessage) {
            guard message.frameInfo.isMainFrame else { return }
            coordinator?.didReceiveScrollMessage(message.body)
        }
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        let gate = ReaderNavigationGate()
        let recovery = ReaderProcessRecovery()
        let scrollRestoration = ReaderScrollRestoration()
        var loadedHtml: String?
        var onScroll: ((Double) -> Void)?
        /// The document `load` last retried after a foreign commit replaced it, so a page that keeps
        /// replacing itself is retried once rather than forever.
        var reloadedHtml: String?
        /// The navigation `loadHTMLString` started for the current document, so only its commit or
        /// failure ends the gate's wait (another WebView load, or a superseded one, must not).
        var documentNavigation: WKNavigation?
        /// The view `load` last loaded into, so a document given up on can be loaded again when the
        /// app becomes active.
        private weak var webView: WKWebView?

        override init() {
            super.init()
            #if os(macOS)
            let didBecomeActive = NSApplication.didBecomeActiveNotification
            #else
            let didBecomeActive = UIApplication.didBecomeActiveNotification
            #endif
            NotificationCenter.default.addObserver(
                self, selector: #selector(appDidBecomeActive), name: didBecomeActive, object: nil
            )
        }

        /// Loads `html` as the reader's document. The gate cannot tell a previous article's script
        /// navigating to `about:blank` from this load while it is in flight, so `didCommit` reloads
        /// the document if such a navigation replaced it.
        func load(_ html: String, in webView: WKWebView) {
            loadedHtml = html
            self.webView = webView
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
            endDocumentLoad(of: navigation)
        }

        func webView(_ webView: WKWebView, didFailProvisionalNavigation navigation: WKNavigation!, withError error: Error) {
            endDocumentLoad(of: navigation)
        }

        private func endDocumentLoad(of navigation: WKNavigation?) {
            guard let navigation, navigation === documentNavigation else { return }
            abandonDocumentLoad()
        }

        /// Ends the gate's wait for the document load in flight, if any.
        private func abandonDocumentLoad() {
            gate.endDocumentLoad()
            documentNavigation = nil
        }

        /// iOS may kill WebKit's WebContent process while the app is in the background (the
        /// external browser a link opened is in front, say), leaving the view blank. `reload()` would
        /// not bring the document back (its URL is `about:blank`, and the gate cancels it anyway), so
        /// the held HTML is loaded again.
        func webViewWebContentProcessDidTerminate(_ webView: WKWebView) {
            abandonDocumentLoad()
            reloadedHtml = nil
            guard let html = loadedHtml else { return }
            switch recovery.processTerminated() {
            case .reload:
                scrollRestoration.reloadStarted()
                load(html, in: webView)
            case .giveUp:
                // It went down again after its one reload: stop here. `loadedHtml` is kept, so an
                // update with the same document still skips it; `appDidBecomeActive` tries once more.
                break
            }
        }

        /// The usual termination happens while the app is in the background, and the one reload may
        /// have died with it: coming back loads a document given up on once more.
        @objc private func appDidBecomeActive() {
            guard recovery.appBecameActive(), let html = loadedHtml, let webView else { return }
            scrollRestoration.reloadStarted()
            load(html, in: webView)
        }

        /// A document loaded to restore a position (`ReaderScrollRestoration`) is scrolled there once
        /// it has finished loading.
        func webView(_ webView: WKWebView, didFinish navigation: WKNavigation!) {
            guard let fraction = scrollRestoration.documentFinished(now: .now) else { return }
            scroll(webView, to: fraction)
        }

        /// Handles a message from `scrollReporter`.
        fileprivate func didReceiveScrollMessage(_ body: Any) {
            guard let message = body as? [String: Any], let kind = message["kind"] as? String else { return }
            switch kind {
            case "scroll":
                guard let fraction = (message["fraction"] as? NSNumber)?.doubleValue else { return }
                guard let recorded = scrollRestoration.scrolled(to: fraction, now: .now) else { return }
                onScroll?(recorded)
            case "user":
                scrollRestoration.userInteracted()
            case "resize":
                guard let fraction = scrollRestoration.contentResized(now: .now), let webView else { return }
                scroll(webView, to: fraction)
            default:
                break
            }
        }

        private func scroll(_ webView: WKWebView, to fraction: Double) {
            webView.evaluateJavaScript(
                "window.keryxScrollToFraction?.(\(fraction))", in: nil, in: .defaultClient, completionHandler: nil
            )
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
        Self.installUserScripts(on: webView, coordinator: context.coordinator)
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
        Self.installUserScripts(on: webView, coordinator: context.coordinator)
        // Transparent until the first document paints, so a page whose body is still loading shows
        // the reader's own background (`ReaderPageView`) rather than WebKit's default white — the
        // document itself paints the same background once loaded.
        webView.isOpaque = false
        webView.backgroundColor = .clear
        webView.scrollView.backgroundColor = .clear
        return webView
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        webView.scrollView.indicatorStyle = switch ReaderScrollIndicator(isDarkTheme: isDarkTheme) {
        case .dark: .black
        case .light: .white
        }
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
