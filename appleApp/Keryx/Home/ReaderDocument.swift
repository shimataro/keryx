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

/// Everything `ReaderDocument.build` needs, already localized and flattened to Swift values — except
/// the article row itself, whose body is picked and bridged to Swift inside `build`, off the main
/// actor (a whole article body is the one large string here).
struct ReaderDocumentInputs: Sendable {
    struct Header: Sendable {
        let title: String
        let meta: String
        /// The feed's favicon URL, shown before `meta` (nil where the toolbar names the feed).
        let metaIconUrl: String?
        let url: String
        /// Whether `url` itself counts as an outbound link (only an http(s) URL does).
        let urlIsOutbound: Bool
        let openInBrowserTooltip: String
    }

    enum Content: Sendable {
        /// No article selected.
        case placeholder
        /// An article; `build` renders `noContentMessage` in place of the body when the feed
        /// supplied neither `content` nor `summary` (or only whitespace — whose links still count,
        /// as they always have).
        case article(Header, row: ArticleReaderRow, noContentMessage: String)
    }

    let theme: ReaderThemeColors
    let content: Content
}

/// `ArticleReaderRow` is an immutable Kotlin data class (every property a `val` of an immutable
/// type), so handing one to `ReaderDocument.build` off the main actor cannot race.
extension ArticleReaderRow: @retroactive @unchecked Sendable {}

/// What `ArticleDetailView.rebuildDocument` keys its rebuild on.
struct ReaderDocumentKey: Hashable {
    let article: ObjectIdentifier?
    let feedName: String?
    let feedFaviconUrl: String?
    let colorScheme: ColorScheme
    let fontSizeScale: Double
}

/// What a document's outbound-link set is extracted from: the article body, resolved against the
/// article's own URL, plus that URL itself when it counts as an outbound link.
struct ReaderLinkSource: Sendable, Equatable {
    let body: String
    let baseUri: String
    let includesBaseUri: Bool
}

/// A reader document ready to load, plus the links in it that count as genuine outbound clicks.
///
/// Built in two steps so the document reaches the WebView without waiting for its body to be parsed
/// for links: `build` renders the HTML (leaving `outboundLinks` `nil` when a body still needs
/// parsing), and `extractOutboundLinks` supplies the set afterwards. `ArticleWebView` holds any
/// navigation made in between — see `ReaderNavigationGate`.
struct ReaderDocument: Sendable {
    let html: String
    /// `nil` until `extractOutboundLinks` has run over `linkSource`.
    let outboundLinks: Set<String>?
    /// What `outboundLinks` is extracted from; `nil` for a document with no body to parse.
    let linkSource: ReaderLinkSource?

    /// Held only until the first build lands (a few milliseconds after the reader appears);
    /// `ArticleWebView` loads nothing for it.
    static let empty = ReaderDocument(html: "", outboundLinks: [], linkSource: nil)

    /// This document with its link set filled in.
    func withOutboundLinks(_ links: Set<String>) -> ReaderDocument {
        ReaderDocument(html: html, outboundLinks: links, linkSource: linkSource)
    }

    /// Renders the document off the main actor — wrapping a whole article body is expensive — but
    /// leaves the body's links unparsed unless `previous` already parsed the very same body: a star
    /// or read toggle publishes a new instance of the same article, which must not re-run ksoup.
    @concurrent
    static func build(_ inputs: ReaderDocumentInputs, reusingLinksOf previous: ReaderDocument) async -> ReaderDocument {
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
                outboundLinks: [],
                linkSource: nil
            )
        case let .article(header, row, noContentMessage):
            let body = row.readerBody()
            if let body, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                let html = ArticleWebViewHtmlKt.wrapArticleHtml(
                    theme: theme,
                    title: header.title,
                    meta: header.meta,
                    body: body,
                    baseUrl: header.url,
                    titleUrl: header.url,
                    titleTooltip: header.openInBrowserTooltip,
                    metaIconUrl: header.metaIconUrl
                )
                let source = ReaderLinkSource(body: body, baseUri: header.url, includesBaseUri: header.urlIsOutbound)
                let reused = previous.linkSource == source ? previous.outboundLinks : nil
                return ReaderDocument(html: html, outboundLinks: reused, linkSource: source)
            }
            let html = ArticleWebViewHtmlKt.articleNoContentHtml(
                theme: theme,
                title: header.title,
                meta: header.meta,
                message: noContentMessage,
                titleUrl: header.url,
                titleTooltip: header.openInBrowserTooltip,
                metaIconUrl: header.metaIconUrl
            )
            // A blank body has nothing worth deferring: parsing whitespace is instant.
            let links = body.map {
                outboundLinks(ReaderLinkSource(body: $0, baseUri: header.url, includesBaseUri: header.urlIsOutbound))
            } ?? []
            return ReaderDocument(html: html, outboundLinks: links, linkSource: nil)
        }
    }

    /// Parses `source`'s body with ksoup for its links, off the main actor.
    @concurrent
    static func extractOutboundLinks(_ source: ReaderLinkSource) async -> Set<String> {
        outboundLinks(source)
    }

    private static func outboundLinks(_ source: ReaderLinkSource) -> Set<String> {
        var links = ArticleWebViewHtmlKt.extractLinks(html: source.body, baseUri: source.baseUri)
        if source.includesBaseUri { links.insert(source.baseUri) }
        return links
    }
}
