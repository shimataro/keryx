#if os(iOS)
import SwiftUI

/// The toolbar status area's text while a refresh or sync runs, as Mail's "Checking for Mail…":
/// a small spinner and a one-line caption. VoiceOver reads it as a plain label and is not told when
/// it changes, so background work never interrupts what is being read.
struct RefreshStatusLabel: View {
    let status: RefreshStatus

    var body: some View {
        HStack(spacing: 6) {
            ProgressView().controlSize(.mini)
            Text(L(titleKey))
                .font(.footnote)
                .foregroundStyle(.secondary)
                .lineLimit(1)
        }
        // iOS 26 sizes a toolbar item to its glass capsule and truncates the caption to "…" unless
        // the label claims its own width.
        .fixedSize()
        .accessibilityElement(children: .combine)
    }

    private var titleKey: String {
        switch status {
        case .refreshing: return "home_refreshing"
        case .syncing: return "home_syncing"
        }
    }
}

/// The status-area item both iOS columns (sidebar and article list) add: present only while
/// something runs (held a moment longer by `HeldRefreshStatus`), so an idle toolbar leaves the
/// bottom bar to the content.
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
