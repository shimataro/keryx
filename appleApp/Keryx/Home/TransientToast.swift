import SwiftUI

/// The bottom-aligned capsule that shows a `TransientToastState`'s message — the iOS URL-copy
/// confirmation (`HomeObservable.copyToast`). Drawn as the same `PillLabel` as the article list's
/// new-articles pill, but purely informational: it never takes a touch.
///
/// It is an accessibility element (identifier `copy-toast`) so VoiceOver users can find it and UI
/// tests can wait for it. Showing it does not move the VoiceOver focus, so the message is spoken once,
/// by the announcement `HomeObservable.copyArticleUrl` posts alongside it.
struct TransientToast: View {
    let state: TransientToastState
    /// Whether the new-articles pill is on screen at the bottom of the list
    /// (`HomeObservable.newArticlesPillAtBottom`): the toast then sits above it instead of covering it.
    let liftedAbovePill: Bool
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    /// The pill's height (one callout line plus its vertical padding), scaled with Dynamic Type.
    @ScaledMetric(relativeTo: .callout) private var pillHeight: CGFloat = 33

    /// The toast's own distance from the bottom edge when nothing is under it.
    private static let bottomInset: CGFloat = 16
    /// The gap kept between the toast and a pill under it.
    private static let pillGap: CGFloat = 8

    var body: some View {
        ZStack {
            if let message = state.message {
                PillLabel(title: message, systemImage: "checkmark")
                    .accessibilityElement(children: .ignore)
                    .accessibilityLabel(message)
                    .accessibilityIdentifier("copy-toast")
                    .padding(.bottom, bottomPadding)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: state.message)
        .animation(.easeInOut(duration: 0.2), value: liftedAbovePill)
        .allowsHitTesting(false)
    }

    private var bottomPadding: CGFloat {
        liftedAbovePill
            ? max(Self.bottomInset, PillLabel.edgeInset + pillHeight + Self.pillGap)
            : Self.bottomInset
    }
}
