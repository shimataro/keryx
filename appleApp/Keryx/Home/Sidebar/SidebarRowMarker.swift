#if os(macOS)
import AppKit
import SwiftUI

/// Marks a macOS sidebar row with the item it shows, so `SidebarDropGuard` can tell what a row of
/// the `List`'s outline is from the row itself rather than from where it sits. Put behind a row's
/// content with `.background(SidebarRowMarker(item:))`: it is zero-size, draws nothing and is hidden
/// from accessibility, so the row looks and reads exactly as without it.
///
/// An accessibility identifier would be the obvious carrier, but SwiftUI exposes it neither on the
/// outline's row views nor on its accessibility rows; a view of our own in the row's hierarchy is
/// found on both visible and off-screen rows (`SidebarDropOutline.marker(of:)`).
struct SidebarRowMarker: NSViewRepresentable {
    let item: SidebarItemID

    func makeNSView(context: Context) -> SidebarRowMarkerView {
        SidebarRowMarkerView(item: item)
    }

    func updateNSView(_ view: SidebarRowMarkerView, context: Context) {
        view.item = item
    }

    func sizeThatFits(_ proposal: ProposedViewSize, nsView: SidebarRowMarkerView, context: Context) -> CGSize? {
        .zero
    }
}

/// The view `SidebarRowMarker` puts in a row: it only carries the row's item.
final class SidebarRowMarkerView: NSView {
    var item: SidebarItemID

    init(item: SidebarItemID) {
        self.item = item
        super.init(frame: .zero)
        setAccessibilityElement(false)
        setAccessibilityHidden(true)
    }

    @available(*, unavailable)
    required init?(coder: NSCoder) { fatalError("init(coder:) is not supported") }

    override var intrinsicContentSize: NSSize { .zero }
    override func hitTest(_ point: NSPoint) -> NSView? { nil }
}
#endif
