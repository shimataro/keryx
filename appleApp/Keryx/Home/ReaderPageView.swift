import KeryxShared
import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// One reader page: an `ArticleWebView` showing `row`, or — while `row` is `nil` — a blank themed
/// document on macOS (nothing selected) and just the reader's background on iOS (a pager page whose
/// body is still loading). Used once by the macOS reader and once per page by the iOS swipe pager
/// (`ArticlePagerView`).
struct ReaderPageView: View {
    /// The article to render, or `nil` for the blank state.
    let row: ArticleReaderRow?
    /// Identifies the instance `row` came from, so the document is rebuilt only when a new one is
    /// actually published — see `rebuildDocument()`.
    let revision: ObjectIdentifier?
    let preferences: PreferencesObservable

    @Environment(\.colorScheme) private var colorScheme
    /// The page's current document and its outbound-link set, built by `rebuildDocument()` off the
    /// main thread. Held here rather than recomputed in `body`: building it means wrapping the whole
    /// article body and parsing it with ksoup, far too heavy to repeat on every body evaluation — and
    /// a single selection re-evaluates `body` several times (`selectedFeedName`/`selectedFeedFaviconUrl`
    /// arrive as flows of their own). Mirrors Compose's own `remember`/`LaunchedEffect` memoization
    /// (`ArticleDetailPane.kt`).
    @State private var document = ReaderDocument.empty

    /// How long a rebuild waits for its key to settle before starting, so stepping quickly through
    /// articles (J/K held down, rapid clicks) builds only the article it stops on.
    private static let rebuildDebounce: Duration = .milliseconds(75)

    var body: some View {
        ArticleWebView(html: document.html, outboundLinks: document.outboundLinks)
            #if os(iOS)
            // Shows through the WebView until its first document paints (see `ArticleWebView`).
            .background(.background)
            #endif
            .task(id: documentKey) {
                await rebuildDocument()
            }
    }

    /// What the document depends on. The article is keyed by instance identity rather than by
    /// content: a new instance only arrives when `HomeViewModel` actually publishes one, and an
    /// unchanged rebuilt document never reloads the WebView (see `ArticleWebView.configure`).
    private var documentKey: ReaderDocumentKey {
        ReaderDocumentKey(
            article: revision,
            colorScheme: colorScheme,
            fontSizeScale: preferences.fontSizeScale
        )
    }

    /// The inputs are flattened to plain values here, on the main actor, and the document itself is
    /// built by `ReaderDocument.build` off it. Until that finishes the previous document stays on
    /// screen; a rebuild superseded by a newer key is cancelled by `.task(id:)` and discarded.
    ///
    /// The document is shown as soon as its HTML is ready; its outbound links are extracted
    /// afterwards (unless the previous document already had the same body's), with `ArticleWebView`
    /// holding any click made in between.
    private func rebuildDocument() async {
        #if os(iOS)
        // A pager page whose body is still loading shows only the reader's background rather than
        // loading a blank document first: the WebView then loads once, when the body arrives. (Each
        // iOS page is one article, so there is never a previous article's media to stop.)
        if row == nil { return }
        #endif
        // The first document a page shows is built at once; only replacing one waits to settle.
        if !document.html.isEmpty {
            do { try await Task.sleep(for: Self.rebuildDebounce) } catch { return }
        }
        let inputs = readerDocumentInputs()
        let built = await ReaderDocument.build(inputs, reusingLinksOf: document)
        guard !Task.isCancelled else { return }
        document = built
        guard built.outboundLinks == nil, let source = built.linkSource else { return }
        let links = await ReaderDocument.extractOutboundLinks(source)
        guard !Task.isCancelled else { return }
        document = built.withOutboundLinks(links)
    }

    private func readerDocumentInputs() -> ReaderDocumentInputs {
        let theme = themeColors(colorScheme: colorScheme, fontScale: Float(preferences.fontSizeScale))
        // A blank themed document rather than none, so a previous article's embedded media stops
        // playing.
        guard let row else {
            return ReaderDocumentInputs(theme: theme, content: .placeholder)
        }
        let title = row.title.isEmpty ? L("article_no_title") : row.title
        let meta = FormattingKt.articleMetaText(author: row.author, publishedAt: row.published_at)
        let header = ReaderDocumentInputs.Header(
            title: title,
            meta: meta,
            url: row.url,
            // Only an http(s) article URL counts as an outbound link — the same rule as Open in
            // Browser (`canOpenInBrowser`); a `keryx://`-scheme or empty URL
            // must not be added, since WebKit would then treat a click that happens to normalize
            // to the same string as "open externally" instead of loading it in the reader.
            urlIsOutbound: ArticleListModelKt.isHttpOrHttpsUrl(url: row.url),
            openInBrowserTooltip: L("article_open_in_browser")
        )
        return ReaderDocumentInputs(
            theme: theme,
            content: .article(header, row: row, noContentMessage: L("article_no_content"))
        )
    }

    /// Resolves the reader's colors under `colorScheme` explicitly — via
    /// `NSAppearance.performAsCurrentDrawingAppearance` on macOS, and a `UITraitCollection` on iOS —
    /// rather than reading whatever the OS's own current appearance happens to be, so the reader
    /// follows `colorScheme` (which already reflects the in-app theme override) even for a frame
    /// where the system appearance has not caught up yet.
    #if os(macOS)
    private func themeColors(colorScheme: ColorScheme, fontScale: Float) -> ReaderThemeColors {
        let appearance = NSAppearance(named: colorScheme == .dark ? .darkAqua : .aqua) ?? NSAppearance.currentDrawing()
        var theme: ReaderThemeColors?
        appearance.performAsCurrentDrawingAppearance {
            theme = ReaderThemeColors(
                surface: argb(.windowBackgroundColor),
                onSurface: argb(.labelColor),
                linkColor: argb(.linkColor),
                mutedColor: argb(.secondaryLabelColor),
                fontScale: fontScale
            )
        }
        return theme ?? ReaderThemeColors(surface: 0, onSurface: 0, linkColor: 0, mutedColor: 0, fontScale: fontScale)
    }

    private func argb(_ color: NSColor) -> Int32 {
        guard let rgb = color.usingColorSpace(.deviceRGB) else { return 0 }
        let r = Int32(rgb.redComponent * 255)
        let g = Int32(rgb.greenComponent * 255)
        let b = Int32(rgb.blueComponent * 255)
        return (Int32(0xFF) << 24) | (r << 16) | (g << 8) | b
    }
    #else
    private func themeColors(colorScheme: ColorScheme, fontScale: Float) -> ReaderThemeColors {
        let trait = UITraitCollection(userInterfaceStyle: colorScheme == .dark ? .dark : .light)
        func resolved(_ color: UIColor) -> UIColor { color.resolvedColor(with: trait) }
        return ReaderThemeColors(
            surface: argb(resolved(.systemBackground)),
            onSurface: argb(resolved(.label)),
            linkColor: argb(resolved(.link)),
            mutedColor: argb(resolved(.secondaryLabel)),
            fontScale: fontScale
        )
    }

    private func argb(_ color: UIColor) -> Int32 {
        var red: CGFloat = 0, green: CGFloat = 0, blue: CGFloat = 0, alpha: CGFloat = 0
        color.getRed(&red, green: &green, blue: &blue, alpha: &alpha)
        let r = Int32(red * 255)
        let g = Int32(green * 255)
        let b = Int32(blue * 255)
        return (Int32(0xFF) << 24) | (r << 16) | (g << 8) | b
    }
    #endif
}
