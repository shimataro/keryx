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

#if os(iOS)
/// A fixed gap that splits the toolbar items on either side of it into separate glass groups, for
/// neighbors that belong together but behave differently. Nothing before iOS 26, which draws no
/// groups to split. iOS only: macOS keeps such items in one group.
struct FixedToolbarSpacer: ToolbarContent {
    /// The placement of the items it sits between; a spacer left at `.automatic` lands elsewhere.
    var placement: ToolbarItemPlacement = .automatic

    var body: some ToolbarContent {
        if #available(iOS 26, *) {
            ToolbarSpacer(.fixed, placement: placement)
        }
    }
}
#endif
