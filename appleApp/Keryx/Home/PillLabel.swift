import SwiftUI

/// The accent-filled capsule the article list's new-articles pill and the iOS copy toast
/// (`TransientToast`) are both drawn as — one style, so the two cannot drift apart.
struct PillLabel: View {
    /// How far the new-articles pill sits from the edge of the list it is pinned to.
    static let edgeInset: CGFloat = 8

    let title: String
    let systemImage: String

    var body: some View {
        Label(title, systemImage: systemImage)
            .font(.callout)
            .padding(.horizontal, 12)
            .padding(.vertical, 6)
            .background(Capsule().fill(Color.accentColor))
            .foregroundStyle(.white)
    }
}
