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
    let preferences: PreferencesObservable
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    @Environment(\.colorScheme) private var colorScheme
    @State private var copyConfirmed = false

    var body: some View {
        VStack(spacing: 0) {
            ArticleWebView(html: documentHtml, outboundLinks: outboundLinks)
        }
        .focused(focusedPane, equals: .reader)
        .toolbar { toolbarContent }
        .onChange(of: home.copyPulse) { _, _ in
            copyConfirmed = true
            Task {
                try? await Task.sleep(for: .seconds(1.5))
                copyConfirmed = false
            }
        }
    }

    /// Rendered straight from the already-loaded `selectedArticle`, matching Compose's own
    /// 3-pane (non-pager) reader (`ArticleDetailPane.kt`'s `singleReaderDocument`) — there is no
    /// separate content cache or loading state to keep in sync here, since `Articles` (the
    /// `StateFlow` this comes from) already carries the full body.
    private var readerRow: ArticleReaderRow? { home.selectedArticle?.toReaderRow() }

    /// Rebuilt whenever the in-app theme or font-size setting changes, following the app's own
    /// light/dark setting rather than the OS's raw appearance — see `PreferencesObservable`.
    /// `colorScheme` already reflects `KeryxApp`'s own `.preferredColorScheme` override, so this
    /// needs no separate read of `localSettings.themeMode`.
    private var theme: ArticleHtmlTheme {
        themeFor(colorScheme: colorScheme, fontScale: Float(preferences.localSettings.fontSizeScale))
    }

    private var documentHtml: String {
        guard let article = home.selectedArticle else {
            return ArticleWebViewHtmlKt.articlePlaceholderHtml(theme: theme, message: L("home_no_article_selected"))
        }
        guard let row = readerRow else {
            return ArticleWebViewHtmlKt.articlePlaceholderHtml(theme: theme, message: L("home_no_article_selected"))
        }
        let title = row.title.isEmpty ? L("article_no_title") : row.title
        let meta = FormattingKt.articleMetaText(author: row.author, publishedAt: row.published_at)
        let openInBrowserTooltip = L("article_open_in_browser")
        if let body = row.readerBody(), !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return ArticleWebViewHtmlKt.wrapArticleHtml(
                theme: theme,
                title: title,
                meta: meta,
                body: body,
                baseUrl: article.url,
                titleUrl: article.url,
                titleTooltip: openInBrowserTooltip
            )
        }
        return ArticleWebViewHtmlKt.articleNoContentHtml(
            theme: theme,
            title: title,
            meta: meta,
            message: L("article_no_content"),
            titleUrl: article.url,
            titleTooltip: openInBrowserTooltip
        )
    }

    /// Only an http(s) article URL counts as an outbound link — matches Compose's own
    /// `hasUsableUrl`-gated set (`ArticleDetailPane.kt:754-757`); a `keryx://`-scheme or empty URL
    /// must not be added, since WebKit would then treat a click that happens to normalize to the
    /// same string as "open externally" instead of loading it in the reader.
    private var outboundLinks: Set<String> {
        guard let article = home.selectedArticle, let body = readerRow?.readerBody() else { return [] }
        var links = ArticleWebViewHtmlKt.extractLinks(html: body, baseUri: article.url)
        if ArticleListModelKt.isHttpOrHttpsUrl(url: article.url) {
            links.insert(article.url)
        }
        return links
    }

    private var feedName: String? { home.selectedFeedName }
    private var feedFaviconUrl: String? { home.selectedFeedFaviconUrl }

    /// Favicon + feed name as plain text, matching Compose's reader top bar
    /// (`ArticleDetailPane.kt`'s `titleContent`): a letter avatar when there is no favicon URL.
    @ViewBuilder
    private var feedHeader: some View {
        if let feedName {
            HStack(spacing: 6) {
                FaviconView(url: feedFaviconUrl, letter: feedName.first)
                    .frame(width: 16, height: 16)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                Text(feedName)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            }
            .accessibilityElement(children: .combine)
        }
    }

    // MARK: - Toolbar

    // In the window toolbar (the strip above this column) so each pane gets its own toolbar section.
    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        let article = home.selectedArticle
        let hasUsableUrl = article.map { ArticleListModelKt.hasUsableUrl(url: $0.url) } ?? false

        // Default placement: `.navigation` items of this column land at the end of the *previous*
        // column's toolbar section instead of at the start of this one.
        // Plain label, not a control: hide the shared glass capsule macOS 26 groups items into.
        if #available(macOS 26, iOS 26, *) {
            ToolbarItem { feedHeader }
                .sharedBackgroundVisibility(.hidden)
        } else {
            ToolbarItem { feedHeader }
        }

        if #available(macOS 26, *) {
            ToolbarSpacer(.flexible)
        }

        ToolbarItemGroup(placement: .primaryAction) {
            Button {
                home.viewModel.toggleStarSelected()
            } label: {
                Image(systemName: article?.is_starred == 1 ? "star.fill" : "star")
            }
            .disabled(article == nil)
            .help(L(article?.is_starred == 1 ? "article_unstar" : "article_star"))

            Button {
                home.viewModel.markSelectedUnread()
            } label: {
                Image(systemName: "envelope.badge")
            }
            .disabled(article == nil)
            .help(L("article_mark_as_unread"))

            Button {
                guard let article else { return }
                copyToPasteboard(article.url)
                home.pulseCopy()
            } label: {
                Image(systemName: copyConfirmed ? "checkmark" : "link")
            }
            .disabled(!hasUsableUrl)
            .help(L("article_copy_url"))

            Button {
                if let article { openInBrowser(article.url) }
            } label: {
                Image(systemName: "safari")
            }
            .disabled(!hasUsableUrl)
            .help(L("article_open_in_browser"))
        }
    }

    /// Resolves the reader's colors under `colorScheme` explicitly — via
    /// `NSAppearance.performAsCurrentDrawingAppearance` on macOS, and a `UITraitCollection` on iOS —
    /// rather than reading whatever the OS's own current appearance happens to be, so the reader
    /// follows `colorScheme` (which already reflects the in-app theme override) even for a frame
    /// where the system appearance has not caught up yet.
    #if os(macOS)
    private func themeFor(colorScheme: ColorScheme, fontScale: Float) -> ArticleHtmlTheme {
        let appearance = NSAppearance(named: colorScheme == .dark ? .darkAqua : .aqua) ?? NSAppearance.currentDrawing()
        var theme: ArticleHtmlTheme?
        appearance.performAsCurrentDrawingAppearance {
            theme = ArticleHtmlTheme(
                surface: argb(.windowBackgroundColor),
                onSurface: argb(.labelColor),
                linkColor: argb(.linkColor),
                mutedColor: argb(.secondaryLabelColor),
                fontScale: fontScale
            )
        }
        return theme ?? ArticleHtmlTheme(surface: 0, onSurface: 0, linkColor: 0, mutedColor: 0, fontScale: fontScale)
    }

    private func argb(_ color: NSColor) -> Int32 {
        guard let rgb = color.usingColorSpace(.deviceRGB) else { return 0 }
        let r = Int32(rgb.redComponent * 255)
        let g = Int32(rgb.greenComponent * 255)
        let b = Int32(rgb.blueComponent * 255)
        return (Int32(0xFF) << 24) | (r << 16) | (g << 8) | b
    }
    #else
    private func themeFor(colorScheme: ColorScheme, fontScale: Float) -> ArticleHtmlTheme {
        let trait = UITraitCollection(userInterfaceStyle: colorScheme == .dark ? .dark : .light)
        func resolved(_ color: UIColor) -> UIColor { color.resolvedColor(with: trait) }
        return ArticleHtmlTheme(
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
