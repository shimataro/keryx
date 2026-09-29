import SwiftUI

#if os(macOS)
import AppKit
#endif

/// A sidebar row's highlight other than its own native list selection: the faint echo of the
/// Compose app's `RowSelectionTone.SECONDARY` (`HomeCommon.kt`) — every *other* rendered copy of the
/// selected filter, e.g. a feed shown under both its folder group and an expanded tag — or the
/// accent fill a Finder/Notes sidebar item shows while a dragged item would be dropped onto it.
enum SidebarRowHighlight: Equatable {
    case none
    case echo
    case drop

    /// Mirrors Compose's `SECONDARY_SELECTION_ALPHA` (`HomeCommon.kt`): the same fraction of the
    /// selection color on every platform, independent of pane focus.
    static let echoAlpha = 0.15
}

#if os(macOS)
extension View {
    /// Paints `highlight` behind a sidebar row. It is drawn by the row's own `NSTableRowView` (see
    /// `SidebarRowHighlightAnchorView`), so it has exactly the native selection's shape — full row
    /// width including the unread badge, the system's insets and corner radius, and the row height
    /// the Sidebar icon size setting picks — which a background on the row's content could never
    /// match. (The iOS sidebar paints it in its cells' background configuration instead.)
    func sidebarRowHighlight(_ highlight: SidebarRowHighlight) -> some View {
        foregroundStyle(highlight == .drop ? AnyShapeStyle(Color.white) : AnyShapeStyle(.primary))
            // Keeps the row's content (and so its drop target) spanning the row's width.
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(SidebarRowHighlightAnchor(highlight: highlight))
    }
}

/// A zero-size view in the row's content whose only job is to find the enclosing `NSTableRowView`
/// and drive the highlight view inserted into it.
private struct SidebarRowHighlightAnchor: NSViewRepresentable {
    let highlight: SidebarRowHighlight

    func makeNSView(context: Context) -> SidebarRowHighlightAnchorView {
        SidebarRowHighlightAnchorView()
    }

    func updateNSView(_ view: SidebarRowHighlightAnchorView, context: Context) {
        view.highlight = highlight
    }

    static func dismantleNSView(_ view: SidebarRowHighlightAnchorView, coordinator: ()) {
        view.detach()
    }
}

/// Relies on SwiftUI's `.sidebar` `List` being hosted in an `NSOutlineView` whose rows are
/// `NSTableRowView`s — an implementation detail, not API. Should that ever change, no row view is
/// found and the row simply shows no highlight.
private final class SidebarRowHighlightAnchorView: NSView {
    var highlight: SidebarRowHighlight = .none {
        didSet { if highlight != oldValue { apply() } }
    }

    private weak var rowView: NSTableRowView?

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    override func viewDidMoveToWindow() {
        super.viewDidMoveToWindow()
        attach()
    }

    override func viewDidMoveToSuperview() {
        super.viewDidMoveToSuperview()
        attach()
    }

    /// Cells are reused across rows, so the enclosing row can change without this view being
    /// recreated; re-resolving on layout catches that.
    override func layout() {
        super.layout()
        attach()
    }

    func detach() {
        rowView.flatMap(SidebarRowHighlightView.existing(in:))?.release(from: self)
        rowView = nil
    }

    private func attach() {
        let found = window == nil ? nil : enclosingRowView()
        guard found !== rowView else { return }
        detach()
        rowView = found
        apply()
    }

    private func apply() {
        guard let rowView else { return }
        SidebarRowHighlightView.installed(in: rowView).show(highlight, for: self)
    }

    private func enclosingRowView() -> NSTableRowView? {
        var view = superview
        while let current = view {
            if let row = current as? NSTableRowView { return row }
            view = current.superview
        }
        return nil
    }
}

/// A second, always-selected `NSTableRowView` laid over the real row's full bounds, behind its
/// cells, so the system's own `drawSelection(in:)` paints the highlight in the native selection's
/// shape. Never hit-testable and hidden from accessibility, so it changes neither clicks nor what
/// VoiceOver reports as selected.
private final class SidebarRowHighlightView: NSTableRowView {
    /// The anchor that last set this row's highlight. A reused cell's new anchor can apply before
    /// the old one is dismantled, so only the current owner may clear it.
    private weak var owner: SidebarRowHighlightAnchorView?

    static func existing(in rowView: NSTableRowView) -> SidebarRowHighlightView? {
        rowView.subviews.lazy.compactMap { $0 as? SidebarRowHighlightView }.first
    }

    static func installed(in rowView: NSTableRowView) -> SidebarRowHighlightView {
        if let view = existing(in: rowView) { return view }
        let view = SidebarRowHighlightView(frame: rowView.bounds)
        view.autoresizingMask = [.width, .height]
        view.selectionHighlightStyle = .regular
        view.isSelected = true
        view.isEmphasized = true
        view.isHidden = true
        view.setAccessibilityElement(false)
        rowView.addSubview(view, positioned: .below, relativeTo: nil)
        return view
    }

    func show(_ highlight: SidebarRowHighlight, for anchor: SidebarRowHighlightAnchorView) {
        owner = anchor
        switch highlight {
        case .none:
            isHidden = true
        case .echo:
            alphaValue = SidebarRowHighlight.echoAlpha
            isHidden = false
        case .drop:
            alphaValue = 1
            isHidden = false
        }
    }

    func release(from anchor: SidebarRowHighlightAnchorView) {
        guard owner === anchor else { return }
        owner = nil
        isHidden = true
    }

    override func hitTest(_ point: NSPoint) -> NSView? { nil }

    /// Only the selection is wanted; the real row underneath already draws its own background.
    override func drawBackground(in dirtyRect: NSRect) {}
}
#endif
