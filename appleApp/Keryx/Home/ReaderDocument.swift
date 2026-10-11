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

/// A reader document ready to load.
struct ReaderDocument: Sendable {
    let html: String

    /// Held only until the first build lands (a few milliseconds after the reader appears);
    /// `ArticleWebView` loads nothing for it.
    static let empty = ReaderDocument(html: "")

    /// Renders the document off the main actor — wrapping a whole article body is expensive.
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
            return ReaderDocument(html: ArticleWebViewHtmlKt.articlePlaceholderHtml(theme: theme, message: ""))
        case let .article(header, row, noContentMessage):
            let body = row.readerBody()
            if let body, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
                return ReaderDocument(html: ArticleWebViewHtmlKt.wrapArticleHtml(
                    theme: theme,
                    title: header.title,
                    meta: header.meta,
                    body: body,
                    baseUrl: header.url,
                    titleUrl: header.url,
                    titleTooltip: header.openInBrowserTooltip,
                    metaIconUrl: header.metaIconUrl
                ))
            }
            return ReaderDocument(html: ArticleWebViewHtmlKt.articleNoContentHtml(
                theme: theme,
                title: header.title,
                meta: header.meta,
                message: noContentMessage,
                titleUrl: header.url,
                titleTooltip: header.openInBrowserTooltip,
                metaIconUrl: header.metaIconUrl
            ))
        }
    }
}
