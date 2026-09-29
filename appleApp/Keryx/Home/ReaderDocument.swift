import KeryxShared
import SwiftUI

/// The reader theme's colors (opaque `0xAARRGGBB`) and font scale, resolved on the main actor and
/// carried as plain values so the document can be built off it — `ArticleHtmlTheme` itself is a
/// Kotlin class and cannot cross actors.
struct ReaderThemeColors: Sendable {
    let surface: Int32
    let onSurface: Int32
    let linkColor: Int32
    let mutedColor: Int32
    let fontScale: Float
}

/// Everything `ReaderDocument.build` needs, already localized and flattened to Swift values.
struct ReaderDocumentInputs: Sendable {
    struct Header: Sendable {
        let title: String
        let meta: String
        let url: String
        /// Whether `url` itself counts as an outbound link (only an http(s) URL does).
        let urlIsOutbound: Bool
        let openInBrowserTooltip: String
    }

    enum Content: Sendable {
        /// No article selected.
        case placeholder
        case article(Header, body: String)
        /// The feed supplied neither `content` nor `summary` (or only whitespace — then carried as
        /// `blankBody`, whose links still count, as they always have).
        case noContent(Header, message: String, blankBody: String?)
    }

    let theme: ReaderThemeColors
    let content: Content
}

/// What `ArticleDetailView.rebuildDocument` keys its rebuild on.
struct ReaderDocumentKey: Hashable {
    let article: ObjectIdentifier?
    let colorScheme: ColorScheme
    let fontSizeScale: Double
}

/// A reader document ready to load, plus the links in it that count as genuine outbound clicks.
struct ReaderDocument: Sendable {
    let html: String
    let outboundLinks: Set<String>

    /// Held only until the first build lands (a few milliseconds after the reader appears);
    /// `ArticleWebView` loads nothing for it.
    static let empty = ReaderDocument(html: "", outboundLinks: [])

    /// Builds the document off the main actor: wrapping a whole article body and parsing it with
    /// ksoup for its links is the expensive part of switching articles.
    @concurrent
    static func build(_ inputs: ReaderDocumentInputs) async -> ReaderDocument {
        let colors = inputs.theme
        let theme = ArticleHtmlTheme(
            surface: colors.surface,
            onSurface: colors.onSurface,
            linkColor: colors.linkColor,
            mutedColor: colors.mutedColor,
            fontScale: colors.fontScale
        )
        switch inputs.content {
        case .placeholder:
            return ReaderDocument(
                html: ArticleWebViewHtmlKt.articlePlaceholderHtml(theme: theme, message: ""),
                outboundLinks: []
            )
        case let .article(header, body):
            let html = ArticleWebViewHtmlKt.wrapArticleHtml(
                theme: theme,
                title: header.title,
                meta: header.meta,
                body: body,
                baseUrl: header.url,
                titleUrl: header.url,
                titleTooltip: header.openInBrowserTooltip
            )
            return ReaderDocument(html: html, outboundLinks: outboundLinks(body: body, header: header))
        case let .noContent(header, message, blankBody):
            let html = ArticleWebViewHtmlKt.articleNoContentHtml(
                theme: theme,
                title: header.title,
                meta: header.meta,
                message: message,
                titleUrl: header.url,
                titleTooltip: header.openInBrowserTooltip
            )
            return ReaderDocument(html: html, outboundLinks: blankBody.map { outboundLinks(body: $0, header: header) } ?? [])
        }
    }

    private static func outboundLinks(body: String, header: ReaderDocumentInputs.Header) -> Set<String> {
        var links = ArticleWebViewHtmlKt.extractLinks(html: body, baseUri: header.url)
        if header.urlIsOutbound { links.insert(header.url) }
        return links
    }
}
