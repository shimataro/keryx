import CoreGraphics
import Testing

/// Covers `ArticleTableLayout`: the fixed-row-height article lists (macOS's table, iOS's collection
/// view) keep their top visible row in place by id across a row change, and work out every other
/// scroll position, without measuring any row.
@Suite
struct ArticleTableLayoutTests {
    private let rowHeight: CGFloat = 70
    private let viewportHeight: CGFloat = 700

    private func ids(_ range: Range<Int>) -> [String] { range.map { "a\($0)" } }

    private func index(_ ids: [String]) -> [String: Int] {
        Dictionary(uniqueKeysWithValues: ids.enumerated().map { ($1, $0) })
    }

    private func offset(
        from old: [String],
        to new: [String],
        anchor: ScrollAnchor?,
        resetToTop: Bool = false
    ) -> CGFloat {
        ArticleTableLayout.scrollOffset(
            oldIds: old,
            newIndexById: index(new),
            newCount: new.count,
            anchor: anchor,
            rowHeight: rowHeight,
            viewportHeight: viewportHeight,
            resetToTop: resetToTop
        )
    }

    @Test
    func keepsTheAnchorRowWhenRowsAreInsertedAboveIt() {
        // Unread only turned off, oldest first: the few unread rows sit at the end of the full list.
        let all = ids(0..<11_585)
        let unread = Array(all.suffix(18))
        let anchor = ScrollAnchor(id: unread[0], offset: 12)
        #expect(offset(from: unread, to: all, anchor: anchor) == CGFloat(11_585 - 18) * rowHeight + 12)
    }

    @Test
    func keepsTheAnchorRowNearTheTopOfTheFullList() {
        // Newest first: the unread rows are among the newest, so only a few rows land above them.
        let all = ids(0..<11_585)
        let unread = Array(all[20..<38])
        let anchor = ScrollAnchor(id: unread[0], offset: 0)
        #expect(offset(from: unread, to: all, anchor: anchor) == 20 * rowHeight)
    }

    @Test
    func aRemovedAnchorFallsBackToTheNextSurvivingRow() {
        let old = ids(0..<100)
        let new = old.filter { $0 != "a40" && $0 != "a41" }
        let anchor = ScrollAnchor(id: "a40", offset: 30)
        // a42 survives and now sits at index 40; the offset into the removed row is dropped.
        #expect(offset(from: old, to: new, anchor: anchor) == 40 * rowHeight)
    }

    @Test
    func aRemovedAnchorFallsBackToThePreviousRowWhenNothingAfterItSurvives() {
        let old = ids(0..<100)
        let new = Array(old.prefix(50))
        let anchor = ScrollAnchor(id: "a60", offset: 0)
        // a49 is the nearest survivor, but the list now ends there, so the offset is clamped.
        #expect(offset(from: old, to: new, anchor: anchor) == 50 * rowHeight - viewportHeight)
    }

    @Test
    func goesToTheTopWhenNoOldRowSurvives() {
        let anchor = ScrollAnchor(id: "a5", offset: 10)
        #expect(offset(from: ids(0..<100), to: ids(100..<200), anchor: anchor) == 0)
    }

    @Test
    func resetGoesToTheTop() {
        let rows = ids(0..<100)
        let anchor = ScrollAnchor(id: "a50", offset: 10)
        #expect(offset(from: rows, to: rows, anchor: anchor, resetToTop: true) == 0)
    }

    @Test
    func noAnchorGoesToTheTop() {
        #expect(offset(from: [], to: ids(0..<100), anchor: nil) == 0)
    }

    @Test
    func clampsToTheEndOfTheList() {
        let rows = ids(0..<100)
        let anchor = ScrollAnchor(id: "a99", offset: 0)
        #expect(offset(from: rows, to: rows, anchor: anchor) == 100 * rowHeight - viewportHeight)
    }

    @Test
    func aListShorterThanTheViewportStaysAtTheTop() {
        let rows = ids(0..<5)
        let anchor = ScrollAnchor(id: "a3", offset: 0)
        #expect(offset(from: rows, to: rows, anchor: anchor) == 0)
    }

    @Test
    func anEmptyListStaysAtTheTop() {
        let anchor = ScrollAnchor(id: "a3", offset: 0)
        #expect(offset(from: ids(0..<10), to: [], anchor: anchor) == 0)
    }

    // MARK: - Anchor

    @Test
    func theAnchorIsTheTopVisibleRowAndTheOffsetPastItsEdge() {
        let rows = ids(0..<100)
        let anchor = ArticleTableLayout.anchor(count: rows.count, id: { rows[$0] }, visibleTop: 3 * rowHeight + 12, rowHeight: rowHeight)
        #expect(anchor == ScrollAnchor(id: "a3", offset: 12))
    }

    @Test
    func aListPulledPastItsStartAnchorsOnTheFirstRow() {
        let rows = ids(0..<100)
        let anchor = ArticleTableLayout.anchor(count: rows.count, id: { rows[$0] }, visibleTop: -60, rowHeight: rowHeight)
        #expect(anchor == ScrollAnchor(id: "a0", offset: 0))
    }

    @Test
    func aListScrolledPastItsEndAnchorsOnTheLastRow() {
        let rows = ids(0..<10)
        let anchor = ArticleTableLayout.anchor(count: rows.count, id: { rows[$0] }, visibleTop: 50 * rowHeight, rowHeight: rowHeight)
        #expect(anchor?.id == "a9")
    }

    @Test
    func anEmptyListHasNoAnchor() {
        #expect(ArticleTableLayout.anchor(count: 0, id: { _ in "" }, visibleTop: 0, rowHeight: rowHeight) == nil)
        #expect(ArticleTableLayout.anchor(count: 5, id: { _ in "a" }, visibleTop: 0, rowHeight: 0) == nil)
    }

    // MARK: - Visible rows

    @Test
    func visibleRowsAreThoseWithAnyPartInTheViewport() {
        // 700 / 70 = 10 rows exactly from a row boundary; 30 points in, an eleventh is touched.
        #expect(ArticleTableLayout.visibleRows(count: 100, rowHeight: rowHeight, visibleTop: 0, viewportHeight: viewportHeight) == 0..<10)
        #expect(ArticleTableLayout.visibleRows(count: 100, rowHeight: rowHeight, visibleTop: 30, viewportHeight: viewportHeight) == 0..<11)
        #expect(ArticleTableLayout.visibleRows(count: 100, rowHeight: rowHeight, visibleTop: 70, viewportHeight: viewportHeight) == 1..<11)
    }

    @Test
    func visibleRowsAreClippedToTheList() {
        #expect(ArticleTableLayout.visibleRows(count: 4, rowHeight: rowHeight, visibleTop: 0, viewportHeight: viewportHeight) == 0..<4)
        #expect(ArticleTableLayout.visibleRows(count: 100, rowHeight: rowHeight, visibleTop: -50, viewportHeight: viewportHeight) == 0..<10)
        #expect(ArticleTableLayout.visibleRows(count: 100, rowHeight: rowHeight, visibleTop: 100 * rowHeight, viewportHeight: viewportHeight).isEmpty)
    }

    @Test
    func noVisibleRowsForAnEmptyListOrViewport() {
        #expect(ArticleTableLayout.visibleRows(count: 0, rowHeight: rowHeight, visibleTop: 0, viewportHeight: viewportHeight).isEmpty)
        #expect(ArticleTableLayout.visibleRows(count: 10, rowHeight: rowHeight, visibleTop: 0, viewportHeight: 0).isEmpty)
        #expect(ArticleTableLayout.visibleRows(count: 10, rowHeight: 0, visibleTop: 0, viewportHeight: viewportHeight).isEmpty)
    }

    // MARK: - Fresh end

    @Test
    func theFreshEndIsTheTopWhenNewestFirstAndTheBottomOtherwise() {
        let end = 100 * rowHeight - viewportHeight
        #expect(ArticleTableLayout.freshEndOffset(newestFirst: true, count: 100, rowHeight: rowHeight, viewportHeight: viewportHeight) == 0)
        #expect(ArticleTableLayout.freshEndOffset(newestFirst: false, count: 100, rowHeight: rowHeight, viewportHeight: viewportHeight) == end)
        #expect(ArticleTableLayout.freshEndOffset(newestFirst: false, count: 3, rowHeight: rowHeight, viewportHeight: viewportHeight) == 0)
    }

    // MARK: - Reveal

    private func reveal(index: Int, top: CGFloat, anyPartCounts: Bool, count: Int = 100) -> CGFloat? {
        ArticleTableLayout.revealOffset(
            index: index, count: count, rowHeight: rowHeight, visibleTop: top, viewportHeight: viewportHeight,
            anyPartCounts: anyPartCounts
        )
    }

    @Test
    func aRowFullyInViewNeverMoves() {
        #expect(reveal(index: 12, top: 10 * rowHeight, anyPartCounts: false) == nil)
        #expect(reveal(index: 12, top: 10 * rowHeight, anyPartCounts: true) == nil)
    }

    @Test
    func aPartlyVisibleRowMovesOnlyWhenItMustBeWhole() {
        // Row 10 starts at 700; the viewport at 630 covers 630..<1330, so row 10 is whole. At 100 it is
        // 100..<800: row 10 (700..<770) is whole too, row 11 (770..<840) is cut at the bottom.
        #expect(reveal(index: 11, top: 100, anyPartCounts: true) == nil)
        #expect(reveal(index: 11, top: 100, anyPartCounts: false) == 840 - viewportHeight)
    }

    @Test
    func aRowAboveTheViewportEndsAtItsTopEdge() {
        #expect(reveal(index: 2, top: 20 * rowHeight, anyPartCounts: true) == 2 * rowHeight)
        #expect(reveal(index: 2, top: 20 * rowHeight, anyPartCounts: false) == 2 * rowHeight)
    }

    @Test
    func aRowBelowTheViewportEndsAtItsBottomEdge() {
        #expect(reveal(index: 40, top: 0, anyPartCounts: true) == 41 * rowHeight - viewportHeight)
    }

    @Test
    func aRevealIsClampedToTheScrollableRange() {
        // The last row of a list that barely overflows: its bottom edge is the end of the list.
        #expect(reveal(index: 14, top: 0, anyPartCounts: true, count: 15) == 15 * rowHeight - viewportHeight)
        // A short list never scrolls: a row out of view goes to the top, not its bottom edge (280).
        #expect(reveal(index: 4, top: 400, anyPartCounts: false, count: 5) == 0)
    }

    @Test
    func aListShorterThanTheViewportHasNoScrollableRange() {
        #expect(ArticleTableLayout.maxOffset(count: 5, rowHeight: rowHeight, viewportHeight: viewportHeight) == 0)
        #expect(ArticleTableLayout.clamped(280, count: 5, rowHeight: rowHeight, viewportHeight: viewportHeight) == 0)
        #expect(ArticleTableLayout.clamped(-30, count: 5, rowHeight: rowHeight, viewportHeight: viewportHeight) == 0)
    }

    @Test
    func anOffsetPastEitherEndIsClampedToIt() {
        let end = ArticleTableLayout.maxOffset(count: 15, rowHeight: rowHeight, viewportHeight: viewportHeight)
        #expect(end == 15 * rowHeight - viewportHeight)
        #expect(ArticleTableLayout.clamped(end + 100, count: 15, rowHeight: rowHeight, viewportHeight: viewportHeight) == end)
        #expect(ArticleTableLayout.clamped(-1, count: 15, rowHeight: rowHeight, viewportHeight: viewportHeight) == 0)
        #expect(ArticleTableLayout.clamped(140, count: 15, rowHeight: rowHeight, viewportHeight: viewportHeight) == 140)
    }

    @Test
    func aRowOutsideTheListNeverMoves() {
        #expect(reveal(index: -1, top: 0, anyPartCounts: false) == nil)
        #expect(reveal(index: 100, top: 0, anyPartCounts: false) == nil)
    }
}
