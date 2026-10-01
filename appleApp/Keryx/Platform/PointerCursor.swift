import SwiftUI

#if os(macOS)
import AppKit

extension View {
    /// Shows the pointing-hand cursor while hovered. SwiftUI's `Link` leaves the arrow in place on
    /// macOS, whereas hyperlinks conventionally show a pointing hand there. Uses `NSCursor`
    /// rather than `pointerStyle(.link)`, which needs macOS 15 (the deployment target is 14).
    func linkPointer() -> some View {
        modifier(LinkPointerModifier())
    }
}

private struct LinkPointerModifier: ViewModifier {
    /// Whether this view currently has a cursor pushed, so push/pop stay balanced even if the view
    /// disappears (e.g. its window closes) while hovered.
    @State private var pushed = false

    func body(content: Content) -> some View {
        content
            .onHover { hovering in
                if hovering {
                    push()
                } else {
                    pop()
                }
            }
            .onDisappear(perform: pop)
    }

    private func push() {
        guard !pushed else { return }
        NSCursor.pointingHand.push()
        pushed = true
    }

    private func pop() {
        guard pushed else { return }
        NSCursor.pop()
        pushed = false
    }
}
#else
extension View {
    /// No-op on iOS: there is no hover cursor.
    func linkPointer() -> some View {
        self
    }
}
#endif
