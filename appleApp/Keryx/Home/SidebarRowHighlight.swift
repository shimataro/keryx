import SwiftUI

/// A sidebar row's highlight other than its own native list selection.
enum SidebarRowHighlight: Equatable {
    case none
    /// Every *other* rendered copy of the selected filter, e.g. a feed shown under both its folder
    /// group and an expanded tag — the role of the Compose app's `RowSelectionTone.SECONDARY`
    /// (`ListRowSelection.kt`). The Apple app shows it as accent-colored text with no fill: a faint fill of
    /// the selection color reads as a dull smudge on a light sidebar.
    case echo
}

#if os(macOS)
extension View {
    /// Shows `highlight` on a sidebar row: the title's color. The row a dragged item would be dropped
    /// onto is highlighted by the outline itself (`SidebarDropGuard`). (The iOS sidebar paints both in
    /// its cells' background configuration instead.)
    func sidebarRowHighlight(_ highlight: SidebarRowHighlight) -> some View {
        foregroundStyle(highlight.foreground)
            // Keeps the row's content spanning the row's width.
            .frame(maxWidth: .infinity, alignment: .leading)
    }
}

private extension SidebarRowHighlight {
    /// The title's color. The row's icon is already accent-tinted by the source list, so for the
    /// echo the title alone tells the copy apart.
    var foreground: AnyShapeStyle {
        switch self {
        case .none: AnyShapeStyle(.primary)
        case .echo: AnyShapeStyle(Color.accentColor)
        }
    }
}
#endif
