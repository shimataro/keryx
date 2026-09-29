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
///
/// Unlike Compose — whose heavyweight `SwingPanel` WebView repaints the whole window when added or
/// removed, so it renders even "no article selected" as HTML inside the WebView — the empty state
/// here is a native `ContentUnavailableView` laid over the WebView. The WebView itself stays
/// mounted underneath, so selecting an article never recreates it (and never flashes its default
/// white background before the first paint).
struct ArticleDetailView: View {
    let home: HomeObservable
    let preferences: PreferencesObservable
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    @Environment(\.colorScheme) private var colorScheme
    @State private var copyConfirmed = false
    /// The reader's current document and its outbound-link set, built by `rebuildDocument()` off the
    /// main thread. Held here rather than recomputed in `body`: building it means wrapping the whole
    /// article body and parsing it with ksoup, far too heavy to repeat on every body evaluation — and
    /// a single selection re-evaluates `body` several times (`selectedFeedName`/`selectedFeedFaviconUrl`
    /// arrive as flows of their own). Mirrors Compose's own `remember`/`LaunchedEffect` memoization
    /// (`ArticleDetailPane.kt`).
    @State private var document = ReaderDocument.empty

    var body: some View {
        let hasArticle = home.selectedArticle != nil
        ZStack {
            ArticleWebView(html: document.html, outboundLinks: document.outboundLinks)
                .allowsHitTesting(hasArticle)
                .accessibilityHidden(!hasArticle)
            if !hasArticle {
                ContentUnavailableView(L("home_no_article_selected"), systemImage: "doc.richtext")
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .background(.background)
            }
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
        .task(id: documentKey) {
            await rebuildDocument()
        }
    }

    /// What the document depends on. The article is keyed by instance identity rather than by
    /// content: a new `Articles` instance only arrives when `HomeViewModel` actually publishes one,
    /// and an unchanged rebuilt document never reloads the WebView (see `ArticleWebView.configure`).
    private var documentKey: ReaderDocumentKey {
        ReaderDocumentKey(
            article: home.selectedArticle.map(ObjectIdentifier.init),
            colorScheme: colorScheme,
            fontSizeScale: preferences.fontSizeScale
        )
    }

    /// Rendered straight from the already-loaded `selectedArticle`, matching Compose's own
    /// 3-pane (non-pager) reader (`ArticleDetailPane.kt`'s `singleReaderDocument`) — there is no
    /// separate content cache or loading state to keep in sync here, since `Articles` (the
    /// `StateFlow` this comes from) already carries the full body.
    ///
    /// The inputs are flattened to plain values here, on the main actor, and the document itself is
    /// built by `ReaderDocument.build` off it. Until that finishes the previous document stays on
    /// screen; a rebuild superseded by a newer key is cancelled by `.task(id:)` and discarded.
    private func rebuildDocument() async {
        let inputs = readerDocumentInputs()
        let built = await ReaderDocument.build(inputs)
        guard !Task.isCancelled else { return }
        document = built
    }

    private func readerDocumentInputs() -> ReaderDocumentInputs {
        let theme = themeColors(colorScheme: colorScheme, fontScale: Float(preferences.fontSizeScale))
        // Hidden under the native empty state, but still swapped for a blank themed document so the
        // previous article's embedded media stops playing.
        guard let article = home.selectedArticle else {
            return ReaderDocumentInputs(theme: theme, content: .placeholder)
        }
        let row = article.toReaderRow()
        let title = row.title.isEmpty ? L("article_no_title") : row.title
        let meta = FormattingKt.articleMetaText(author: row.author, publishedAt: row.published_at)
        let header = ReaderDocumentInputs.Header(
            title: title,
            meta: meta,
            url: article.url,
            // Only an http(s) article URL counts as an outbound link — matches Compose's own
            // `hasUsableUrl`-gated set (`ArticleDetailPane.kt`); a `keryx://`-scheme or empty URL
            // must not be added, since WebKit would then treat a click that happens to normalize
            // to the same string as "open externally" instead of loading it in the reader.
            urlIsOutbound: ArticleListModelKt.isHttpOrHttpsUrl(url: article.url),
            openInBrowserTooltip: L("article_open_in_browser")
        )
        let body = row.readerBody()
        if let body, !body.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            return ReaderDocumentInputs(theme: theme, content: .article(header, body: body))
        }
        return ReaderDocumentInputs(theme: theme, content: .noContent(header, message: L("article_no_content"), blankBody: body))
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
                    .frame(width: 18, height: 18)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                Text(feedName)
                    .font(.title3)
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

        if #available(macOS 26, iOS 26, *) {
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
