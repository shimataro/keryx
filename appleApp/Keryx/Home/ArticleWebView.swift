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
    let outboundLinks: Set<String>

    @MainActor
    func makeCoordinator() -> Coordinator {
        Coordinator(outboundLinks: outboundLinks)
    }

    @MainActor
    private func configure(_ webView: WKWebView, coordinator: Coordinator) {
        webView.navigationDelegate = coordinator
        coordinator.outboundLinks = outboundLinks
        // SwiftUI calls update on every re-evaluation; reloading an unchanged document would reset
        // the scroll position.
        guard html != coordinator.loadedHtml else { return }
        coordinator.loadedHtml = html
        webView.loadHTMLString(html, baseURL: ArticleNavigationPolicy.documentBaseURL)
    }

    @MainActor
    final class Coordinator: NSObject, WKNavigationDelegate {
        var outboundLinks: Set<String>
        var loadedHtml: String?

        init(outboundLinks: Set<String>) {
            self.outboundLinks = outboundLinks
        }

        func webView(
            _ webView: WKWebView,
            decidePolicyFor navigationAction: WKNavigationAction,
            decisionHandler: @escaping @MainActor @Sendable (WKNavigationActionPolicy) -> Void
        ) {
            if let url = navigationAction.request.url,
               ArticleNavigationPolicy.isOutboundClick(url: url, outboundLinks: outboundLinks) {
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
