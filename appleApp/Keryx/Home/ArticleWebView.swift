import SwiftUI
import WebKit

/// Wraps `WKWebView` for the article reader — see "Article Reader (native WebView)" in
/// `docs/app-architecture.md`. Loads pre-wrapped HTML (never raw article `content` — see
/// `ArticleWebViewHtml.kt`) and gates outbound navigation: only a request whose target URL is
/// either the article's own URL or one of the links `extractLinks` found inside the body is
/// treated as a genuine click-through and opened in the external browser; everything else (an SNS
/// embed's own internal navigation) is left to load inside the WebView untouched.
struct ArticleWebView {
    let html: String
    let baseUrl: URL?
    let outboundLinks: Set<String>

    @MainActor
    func makeCoordinator() -> Coordinator {
        Coordinator(outboundLinks: outboundLinks)
    }

    @MainActor
    private func configure(_ webView: WKWebView, coordinator: Coordinator) {
        webView.navigationDelegate = coordinator
        coordinator.outboundLinks = outboundLinks
        webView.loadHTMLString(html, baseURL: baseUrl)
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        var outboundLinks: Set<String>

        init(outboundLinks: Set<String>) {
            self.outboundLinks = outboundLinks
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            guard let url = navigationAction.request.url else {
                decisionHandler(.allow)
                return
            }
            if outboundLinks.contains(url.absoluteString) {
                openInBrowser(url.absoluteString)
                decisionHandler(.cancel)
            } else {
                decisionHandler(.allow)
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
}
#else
extension ArticleWebView: UIViewRepresentable {
    func makeUIView(context: Context) -> WKWebView {
        WKWebView()
    }

    func updateUIView(_ webView: WKWebView, context: Context) {
        configure(webView, coordinator: context.coordinator)
    }
}
#endif
