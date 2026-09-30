import SwiftUI
import WebKit

/// Wraps `WKWebView` for the article reader — see "Article Reader (native WebView)" in
/// `docs/app-architecture.md`. Loads pre-wrapped HTML (never raw article `content` — see
/// `ArticleWebViewHtml.kt`) and gates outbound navigation: only a request whose target URL is
/// either the article's own URL or one of the links `extractLinks` found inside the body is
/// treated as a genuine click-through and opened in the external browser; everything else (an SNS
/// embed's own internal navigation) is left to load inside the WebView untouched.
///
/// `outboundLinks` is `nil` while the document's links are still being extracted (the document is
/// shown before that finishes — see `ReaderPageView.rebuildDocument`); `ReaderNavigationGate` holds
/// any navigation arriving in that window rather than letting it load in the reader.
struct ArticleWebView {
    let html: String
    let outboundLinks: Set<String>?

    @MainActor
    func makeCoordinator() -> Coordinator {
        Coordinator()
    }

    @MainActor
    private func configure(_ webView: WKWebView, coordinator: Coordinator) {
        webView.navigationDelegate = coordinator
        // An empty document is `ReaderDocument.empty`, held only until the first real one is built.
        guard !html.isEmpty else { return }
        // Before the load below, so a click the previous document left held is cancelled rather
        // than answered with this document's links.
        coordinator.gate.update(documentID: html, outboundLinks: outboundLinks)
        // SwiftUI calls update on every re-evaluation; reloading an unchanged document would reset
        // the scroll position.
        guard html != coordinator.loadedHtml else { return }
        coordinator.loadedHtml = html
        webView.loadHTMLString(html, baseURL: ArticleNavigationPolicy.documentBaseURL)
    }

    @MainActor
    private static func dismantle(coordinator: Coordinator) {
        // WebKit requires every decision handler to be called exactly once.
        coordinator.gate.discardPending()
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        let gate = ReaderNavigationGate()
        var loadedHtml: String?

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            gate.decide(url: navigationAction.request.url) { decision in
                switch decision {
                case let .openInBrowser(url):
                    openInBrowser(url.absoluteString)
                    decisionHandler(.cancel)
                case .allow:
                    decisionHandler(.allow)
                case .cancel:
                    decisionHandler(.cancel)
                }
            }
        }
    }
}

#if os(macOS)
extension ArticleWebView: NSViewRepresentable {
    func makeNSView(context: Context) -> WKWebView {
        WKWebView()
    }

    func updateNSView(_ webView: WKWebView, context: Context) {
        configure(webView, coordinator: context.coordinator)
    }

    static func dismantleNSView(_ webView: WKWebView, coordinator: Coordinator) {
        dismantle(coordinator: coordinator)
    }
}
#else
extension ArticleWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        let webView = ReaderWebViewWarmUp.takeWarmedWebView() ?? WKWebView()
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

    static func dismantleUIView(_ webView: WKWebView, coordinator: Coordinator) {
        dismantle(coordinator: coordinator)
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
