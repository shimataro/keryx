import CoreGraphics
import Testing

/// Covers `ArticleTableLayout.scrollOffset`: the macOS article list keeps its top visible row in
/// place by id across a row change, without measuring any row.
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
}
