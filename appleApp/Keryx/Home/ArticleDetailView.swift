import KeryxShared
import SwiftUI
#if os(macOS)
import AppKit
#else
import UIKit
#endif

/// The reader pane. On macOS — always the permanent, keyboard-driven 3-pane layout (no swipe pager;
/// that is a touch-only affordance, see `external-spec.md` §9) — a single `ReaderPageView` that
/// stays mounted across selections, swapping only the WebView's document. On iOS, a touch device at
/// every width, the swipe pager `ArticlePagerView` instead.
///
/// Unlike Compose — whose heavyweight `SwingPanel` WebView repaints the whole window when added or
/// removed, so it renders even "no article selected" as HTML inside the WebView — the empty state
/// here is a native `ContentUnavailableView` laid over the WebView. On macOS the WebView itself
/// stays mounted underneath, so selecting an article never recreates it (and never flashes its
/// default white background before the first paint).
struct ArticleDetailView: View {
    let home: HomeObservable
    let preferences: PreferencesObservable
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    @State private var copyConfirmed = false

    var body: some View {
        let hasArticle = home.selectedArticle != nil
        ZStack {
            #if os(iOS)
            // A swipe pager only while there is an article to page from — a pager with nothing
            // selected would still show its first page (see `ArticlePagerView`).
            if hasArticle {
                ArticlePagerView(home: home, preferences: preferences)
            }
            #else
            ReaderPageView(
                row: home.selectedArticle?.toReaderRow(),
                revision: home.selectedArticle.map(ObjectIdentifier.init),
                preferences: preferences
            )
            .allowsHitTesting(hasArticle)
            .accessibilityHidden(!hasArticle)
            #endif
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
            // The checkmark is the only other confirmation, and VoiceOver does not see it change.
            AccessibilityNotification.Announcement(L("article_url_copied")).post()
            Task {
                try? await Task.sleep(for: .seconds(1.5))
                copyConfirmed = false
            }
        }
    }

    private var feedName: String? { home.selectedFeedName }
    private var feedFaviconUrl: String? { home.selectedFeedFaviconUrl }

    /// Favicon + feed name as plain text, matching Compose's reader top bar
    /// (`ArticleDetailPane.kt`'s `titleContent`): a letter avatar when there is no favicon URL.
    /// A `Label` so the toolbar's overflow menu (at a narrow width) shows the feed name, not just the
    /// favicon; `.titleAndIcon` keeps both visible in the toolbar itself.
    @ViewBuilder
    private var feedHeader: some View {
        if let feedName {
            Label {
                Text(feedName)
                    .font(.title3)
                    .foregroundStyle(.secondary)
                    .lineLimit(1)
            } icon: {
                FaviconView(url: feedFaviconUrl, letter: feedName.first)
                    .frame(width: 18, height: 18)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
            }
            .labelStyle(.titleAndIcon)
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

        // `Label`s rather than bare icons so the toolbar's overflow menu (at a narrow width) gets titles;
        // the toolbar itself still renders them icon-only.
        ToolbarItemGroup(placement: .primaryAction) {
            Button {
                home.viewModel.toggleStarSelected()
            } label: {
                Label(
                    L(article?.is_starred == 1 ? "article_unstar" : "article_star"),
                    systemImage: article?.is_starred == 1 ? "star.fill" : "star"
                )
            }
            .disabled(article == nil)
            .help(L(article?.is_starred == 1 ? "article_unstar" : "article_star"))

            Button {
                home.viewModel.markSelectedUnread()
            } label: {
                Label(L("article_mark_as_unread"), systemImage: "envelope.badge")
            }
            .disabled(article == nil)
            .help(L("article_mark_as_unread"))

            Button {
                guard let article else { return }
                copyToPasteboard(article.url)
                home.pulseCopy()
            } label: {
                Label(L("article_copy_url"), systemImage: copyConfirmed ? "checkmark" : "link")
            }
            .disabled(!hasUsableUrl)
            .help(L("article_copy_url"))

            Button {
                if let article { openInBrowser(article.url) }
            } label: {
                Label(L("article_open_in_browser"), systemImage: "safari")
            }
            .disabled(!hasUsableUrl)
            .help(L("article_open_in_browser"))
        }
    }
}
