#if os(iOS)
import SwiftUI

/// The toolbar status area's text about a feed refresh, as Mail's "Checking for Mail…" and "Updated
/// Just Now": a small spinner and "Checking for new articles…" while it runs, then its result with no
/// spinner — or, when no feed could be reached, a warning symbol, so a failure reads as one at a glance
/// without relying on its color alone. VoiceOver reads it as a plain label — for a failure, the longer
/// sentence that also gives the next step (`spokenText`) — and is not told when it changes, so
/// background work never interrupts what is being read.
struct RefreshStatusLabel: View {
    let status: RefreshStatus

    var body: some View {
        HStack(spacing: 6) {
            switch status {
            case .checking:
                ProgressView().controlSize(.mini)
            case .refreshFailed:
                Image(systemName: "exclamationmark.triangle.fill")
                    .font(.footnote)
                    .foregroundStyle(.orange)
                    .accessibilityHidden(true)
            case .newArticles, .noNewArticles:
                EmptyView()
            }
            Text(text)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        // Claiming its own width has a cost: the label can no longer shrink to the bar, so its width is
        // the text's width. The widest status ("Checking for new articles…" in en, "新着記事が N 件あります"
        // in ja) spans about 250pt at xxxLarge including the spinner, inside the narrowest place the
        // label appears (an iPad mini's 279pt article-list column). The accessibility sizes do not fit
        // it: en alone passes 285pt at the first of them. Capping the label's own scaling at the
        // largest standard size keeps it inside the bar; the notification center and VoiceOver carry
        // the status for anyone who needs it larger.
        .dynamicTypeSize(...DynamicTypeSize.xxxLarge)
        // iOS 26 sizes a toolbar item to its glass capsule and truncates the caption to "…" unless
        // the label claims its own width.
        .fixedSize()
        .accessibilityElement(children: .combine)
        .accessibilityLabel(spokenText)
    }

    /// What VoiceOver reads. The failure's visible text is as short as the bar's width forces and gives
    /// no next step, and the warning symbol is hidden from VoiceOver, so it hears the notification
    /// center's sentence instead — which says the check failed and what to do. VoiceOver has no width
    /// to fit; every other status reads as it shows.
    private var spokenText: String {
        switch status {
        case .refreshFailed: return L("notification_refresh_failed")
        case .checking, .newArticles, .noNewArticles: return text
        }
    }

    private var text: String {
        switch status {
        case .checking: return L("home_refreshing")
        case .newArticles(let count): return LF("feed_new_articles", Int64(count))
        case .noNewArticles: return L("home_no_new_articles")
        case .refreshFailed: return L("home_refresh_failed")
        }
    }
}

/// The status-area item both iOS columns (sidebar and article list) add: present only while a refresh
/// the user started runs and for a moment after (`HeldRefreshStatus`), so an idle toolbar leaves the
/// bottom bar to the content. Before iOS 26 nothing else sits in that bar, so it appears and goes with
/// the item — acceptable as the response to the user's own action, which is why automatic refreshes
/// never show here (`external-spec.md` §9).
struct RefreshStatusToolbarItem: ToolbarContent {
    let home: HomeObservable

    var body: some ToolbarContent {
        if let status = home.refreshStatus.status {
            if #available(iOS 26, *) {
                // Plain status text as Mail shows it, not a glass button that looks tappable.
                ToolbarItem(placement: .status) { RefreshStatusLabel(status: status) }
                    .sharedBackgroundVisibility(.hidden)
            } else {
                ToolbarItem(placement: .status) { RefreshStatusLabel(status: status) }
            }
        }
    }
}
#endif
