#if os(iOS)
import SwiftUI

/// The toolbar status area's text about a feed refresh, as Mail's "Checking for Mail…" and "Updated
/// Just Now": a small spinner and "Checking for new articles…" while it runs, then its result with no
/// spinner — or, when every feed failed, a warning symbol, so a failure reads as one at a glance
/// without relying on its color alone. VoiceOver reads it as a plain label and is not told when it
/// changes, so background work never interrupts what is being read.
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
        // iOS 26 sizes a toolbar item to its glass capsule and truncates the caption to "…" unless
        // the label claims its own width.
        .fixedSize()
        .accessibilityElement(children: .combine)
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

/// The status-area item both iOS columns (sidebar and article list) add: present only while a check
/// runs and for a moment after (`HeldRefreshStatus`), so an idle toolbar leaves the bottom bar to the
/// content.
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
