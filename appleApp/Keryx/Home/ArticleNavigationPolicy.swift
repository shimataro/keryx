import Foundation

/// The reader's navigation constants — kept free of WebKit so the standalone `KeryxTests` bundle can
/// compile it directly.
enum ArticleNavigationPolicy {
    /// The base URL the reader passes to `loadHTMLString`. Deliberately `nil` (the document is
    /// `about:blank`), so that WebKit's report of the programmatic load itself is recognizable as such
    /// (`ReaderNavigationGate`) rather than looking like a navigation to the article's own URL.
    /// Relative URLs still resolve through the `<base href>` `wrapArticleHtml` emits, matching the
    /// Compose desktop reader.
    static let documentBaseURL: URL? = nil
}
