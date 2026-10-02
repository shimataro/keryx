#if os(macOS)
import Testing

/// Covers `ContextMenuSelectionTracker`'s hover bookkeeping: a right-click selects the row that is
/// hovered now — including after a cell is reused for another row under a stationary pointer.
@MainActor
@Suite
struct ContextMenuSelectionTrackerTests {
    /// Records which row's select closure a simulated right-click runs.
    private final class SelectionLog {
        var selected: [String] = []
        func select(_ id: String) -> () -> Void { { self.selected.append(id) } }
    }

    @Test
    func aRightClickSelectsTheHoveredRow() {
        let tracker = ContextMenuSelectionTracker()
        let log = SelectionLog()
        tracker.hoverEntered("A", select: log.select("A"))
        tracker.contextClicked()
        #expect(log.selected == ["A"])
    }

    @Test
    func aRightClickAfterTheHoverEndedSelectsNothing() {
        let tracker = ContextMenuSelectionTracker()
        let log = SelectionLog()
        tracker.hoverEntered("A", select: log.select("A"))
        tracker.hoverExited("A")
        tracker.contextClicked()
        #expect(log.selected.isEmpty)
    }

    /// A cell reused for row B under a stationary pointer: the right-click selects B, the row drawn
    /// under the pointer, not A, the row the cell showed when the pointer entered it.
    @Test
    func aReusedCellSelectsTheRowItNowDraws() {
        let tracker = ContextMenuSelectionTracker()
        let log = SelectionLog()
        tracker.hoverEntered("A", select: log.select("A"))
        tracker.hoveredRowChanged(from: "A", to: "B", select: log.select("B"))
        tracker.contextClicked()
        #expect(log.selected == ["B"])
    }

    /// A stale exit for a row the pointer already moved on from does not clear the current hover.
    @Test
    func aLateExitOfAnotherRowKeepsTheCurrentHover() {
        let tracker = ContextMenuSelectionTracker()
        let log = SelectionLog()
        tracker.hoverEntered("B", select: log.select("B"))
        tracker.hoverExited("A")
        tracker.contextClicked()
        #expect(log.selected == ["B"])
    }
}
#endif
