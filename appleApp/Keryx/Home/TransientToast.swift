import SwiftUI

/// The bottom-aligned capsule that shows a `TransientToastState`'s message — the iOS URL-copy
/// confirmation (`HomeObservable.copyToast`). Drawn like the article list's new-articles pill, but
/// purely informational: it never takes a touch, and VoiceOver hears the message as an announcement
/// instead (`HomeObservable.copyArticleUrl`), so the capsule itself is hidden from it.
struct TransientToast: View {
    let state: TransientToastState
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        ZStack {
            if let message = state.message {
                Label(message, systemImage: "checkmark")
                    .font(.callout)
                    .padding(.horizontal, 12)
                    .padding(.vertical, 6)
                    .background(Capsule().fill(Color.accentColor))
                    .foregroundStyle(.white)
                    .padding(.bottom, 16)
                    .transition(reduceMotion ? .opacity : .move(edge: .bottom).combined(with: .opacity))
            }
        }
        .animation(.easeInOut(duration: 0.2), value: state.message)
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
