import Foundation

/// Decides which reader navigations are genuine outbound link clicks — kept free of WebKit so the
/// standalone `KeryxTests` bundle can compile it directly.
enum ArticleNavigationPolicy {
    /// The base URL the reader passes to `loadHTMLString`. Deliberately `nil` (the document is
    /// `about:blank`): WebKit reports the programmatic load itself as a navigation to its base
    /// URL, so passing the article's own URL — which `outboundLinks` always contains, since the
    /// rendered title links to it — made the reader cancel its own document and open the browser
    /// instead. Relative URLs still resolve through the `<base href>` `wrapArticleHtml` emits,
    /// matching the Compose desktop reader.
    static let documentBaseURL: URL? = nil

    /// Whether a navigation to `url` is a click on one of the article's own links, to be opened
    /// in the external browser rather than inside the reader.
    static func isOutboundClick(url: URL?, outboundLinks: Set<String>) -> Bool {
        guard let url else { return false }
        return outboundLinks.contains(url.absoluteString)
    }
}
