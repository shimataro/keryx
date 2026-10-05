import SwiftUI

/// A flexible space that pushes the toolbar items after it to the trailing edge of their column.
/// `ToolbarSpacer` exists only from macOS 26 / iOS 26; before that, macOS turns a `Spacer` in its own
/// item into the toolbar's flexible space, while iOS keeps its earlier layout (no spacer at all).
struct FlexibleToolbarSpacer: ToolbarContent {
    /// Only fill in where `ToolbarSpacer` is missing (macOS before 26): for a column whose items
    /// macOS 26 already right-aligns by itself, such as the sidebar.
    var legacyOnly = false

    var body: some ToolbarContent {
        #if os(macOS)
        if #available(macOS 26, *) {
            if !legacyOnly {
                ToolbarSpacer(.flexible)
            }
        } else {
            ToolbarItem { Spacer() }
        }
        #else
        if #available(iOS 26, *) {
            if !legacyOnly {
                ToolbarSpacer(.flexible)
            }
        }
        #endif
    }
}
