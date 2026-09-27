import KeryxShared
import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// The reader pane — desktop is always the permanent, keyboard-driven 3-pane layout (no swipe
/// pager; that is a narrow/touch-only affordance, see `external-spec.md` §9), so this view has no
/// back control and stays mounted across selections, swapping only the WebView's document.
struct ArticleDetailView: View {
    let home: HomeObservable
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    @State private var copyConfirmed = false

    var body: some View {
        VStack(spacing: 0) {
            toolbar
            Divider()
            ArticleWebView(html: documentHtml, baseUrl: baseUrl, outboundLinks: outboundLinks)
        }
        .focused(focusedPane, equals: .reader)
        .onChange(of: selectedArticleId, initial: true) { _, id in
            guard let id else { return }
            home.viewModel.requestArticleContent(id: id)
        }
    }

    private var selectedArticleId: String? { home.selectedArticle?.id }

    private var readerRow: ArticleReaderRow? {
        selectedArticleId.flatMap { home.articleContents[$0] }
    }

    private var theme: ArticleHtmlTheme {
        #if os(macOS)
        ArticleHtmlTheme(
            surface: argb(.windowBackgroundColor),
            onSurface: argb(.labelColor),
            linkColor: argb(.linkColor),
            mutedColor: argb(.secondaryLabelColor),
            fontScale: 1.0
        )
        #else
        ArticleHtmlTheme(
            surface: argb(.systemBackground),
            onSurface: argb(.label),
            linkColor: argb(.link),
            mutedColor: argb(.secondaryLabel),
            fontScale: 1.0
        )
        #endif
    }

    private var documentHtml: String {
        guard let article = home.selectedArticle else {
            return ArticleWebViewHtmlKt.articlePlaceholderHtml(theme: theme, message: L("home_no_article_selected"))
        }
        guard let row = readerRow else {
            // Still loading — requestArticleContent was just dispatched in onChange above.
            return ArticleWebViewHtmlKt.articlePlaceholderHtml(theme: theme, message: L("apple_loading"))
        }
        let meta = [feedName, formattedDate(row.published_at)].compactMap { $0 }.joined(separator: " · ")
        if let body = row.readerBody(), !body.isEmpty {
            return ArticleWebViewHtmlKt.wrapArticleHtml(
                theme: theme,
                title: row.title,
                meta: meta,
                body: body,
                baseUrl: article.url,
                titleUrl: article.url,
                titleTooltip: article.url
            )
        }
        return ArticleWebViewHtmlKt.articleNoContentHtml(
            theme: theme,
            title: row.title,
            meta: meta,
            message: L("article_no_content"),
            titleUrl: article.url,
            titleTooltip: article.url
        )
    }

    private var baseUrl: URL? {
        guard let article = home.selectedArticle else { return nil }
        return URL(string: article.url)
    }

    private var outboundLinks: Set<String> {
        guard let article = home.selectedArticle, let body = readerRow?.readerBody() else { return [] }
        return ArticleWebViewHtmlKt.extractLinks(html: body, baseUri: article.url).union([article.url])
    }

    private var feedName: String? { home.selectedFeedName }

    private func formattedDate(_ epochMillis: KotlinLong?) -> String? {
        guard let epochMillis else { return nil }
        let date = Date(timeIntervalSince1970: Double(epochMillis.int64Value) / 1000)
        return date.formatted(date: .abbreviated, time: .shortened)
    }

    // MARK: - Toolbar

    @ViewBuilder
    private var toolbar: some View {
        HStack(spacing: 12) {
            if let article = home.selectedArticle {
                Button {
                    home.viewModel.toggleStarSelected()
                } label: {
                    Image(systemName: article.is_starred == 1 ? "star.fill" : "star")
                }
                .help(L(article.is_starred == 1 ? "article_unstar" : "article_star"))

                Button {
                    home.viewModel.markSelectedUnread()
                } label: {
                    Image(systemName: "envelope.badge")
                }
                .help(L("article_mark_as_unread"))

                if ArticleListModelKt.hasUsableUrl(url: article.url) {
                    Button {
                        copyToPasteboard(article.url)
                        copyConfirmed = true
                        Task {
                            try? await Task.sleep(for: .seconds(1.5))
                            copyConfirmed = false
                        }
                    } label: {
                        Image(systemName: copyConfirmed ? "checkmark" : "link")
                    }
                    .help(L("article_copy_url"))

                    Button {
                        openInBrowser(article.url)
                    } label: {
                        Image(systemName: "safari")
                    }
                    .help(L("article_open_in_browser"))
                }
            }
            Spacer()
        }
        .buttonStyle(.borderless)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
    }

    #if os(macOS)
    private func argb(_ color: NSColor) -> Int32 {
        guard let rgb = color.usingColorSpace(.deviceRGB) else { return 0 }
        let r = Int32(rgb.redComponent * 255)
        let g = Int32(rgb.greenComponent * 255)
        let b = Int32(rgb.blueComponent * 255)
        return (Int32(0xFF) << 24) | (r << 16) | (g << 8) | b
    }
    #else
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
