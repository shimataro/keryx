/// Which scroll indicator the iOS reader's `WKWebView` draws, decided by the in-app theme alone.
///
/// Kept free of UIKit so it can be tested in the standalone test bundle; `ArticleWebView` maps it
/// to `UIScrollView.IndicatorStyle`. The reader's `WKWebView` is transparent (`isOpaque = false`),
/// so the indicator style is set explicitly rather than left to WebKit's own guess from the page
/// background, which leaves a dark indicator over the dark reader.
enum ReaderScrollIndicator: Equatable {
    /// Dark indicator, for a light reader background.
    case dark
    /// Light indicator, for a dark reader background.
    case light

    init(isDarkTheme: Bool) {
        self = isDarkTheme ? .light : .dark
    }
}
