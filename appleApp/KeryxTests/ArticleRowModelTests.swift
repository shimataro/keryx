import KeryxShared
import SwiftUI
import Testing

/// Covers `ArticleRowModel`, the article list's per-emission display rows: the fields it resolves
/// from the Kotlin row, and the search-highlight splitting moved there from `ArticleListView`.
@Suite
struct ArticleRowModelTests {
    private let utc = Kotlinx_datetimeTimeZone.Companion.shared.of(zoneId: "UTC")

    private func row(id: String = "a1", title: String = "Title", publishedAt: Int64? = 0, isRead: Int64 = 0, isStarred: Int64 = 0) -> ArticleListRow {
        ArticleListRow(
            id: id,
            feed_id: "f1",
            title: title,
            url: "https://example.com/\(id)",
            published_at: publishedAt.map { KotlinLong(value: $0) },
            created_at: 0,
            is_read: isRead,
            is_starred: isStarred
        )
    }

    private func model(_ row: ArticleListRow, marked: String? = nil) -> ArticleRowModel {
        ArticleRowModel(row: row, markedTitle: marked, feedTitle: "Feed", faviconUrl: nil, zone: utc)
    }

    @Test
    func resolvesRowFields() {
        let m = model(row(publishedAt: 90_061_000, isRead: 1, isStarred: 1))
        #expect(m.id == "a1")
        #expect(m.title == "Title")
        #expect(m.feedTitle == "Feed")
        #expect(m.timestamp == "1970-01-02 01:01")
        #expect(m.isRead)
        #expect(m.isStarred)
        #expect(m.url == "https://example.com/a1")
        #expect(m.highlightedTitle == nil)
        #expect(m.hasUsableUrl)
        #expect(m.canOpenInBrowser)
    }

    @Test
    func blankUrlIsNotUsable() {
        let blank = ArticleListRow(
            id: "a1", feed_id: "f1", title: "Title", url: "", published_at: nil, created_at: 0, is_read: 0, is_starred: 0
        )
        #expect(!model(blank).hasUsableUrl)
        #expect(!model(blank).canOpenInBrowser)
    }

    /// Open in Browser's http(s) rule is stricter than Copy URL's non-blank one: a `file:` or
    /// relative link can be copied but is never opened.
    @Test(arguments: ["file:///etc/passwd", "javascript:alert(1)", "/relative/path"])
    func nonHttpUrlIsCopyableButNotOpenable(url: String) {
        let row = ArticleListRow(
            id: "a1", feed_id: "f1", title: "Title", url: url, published_at: nil, created_at: 0, is_read: 0, is_starred: 0
        )
        #expect(model(row).hasUsableUrl)
        #expect(!model(row).canOpenInBrowser)
    }

    @Test
    func missingPublishDateFormatsAsEmpty() {
        #expect(model(row(publishedAt: nil)).timestamp == "")
    }

    @Test
    func blankTitleIsNil() {
        #expect(model(row(title: "")).title == nil)
    }

    @Test
    func blankMarkedTitleFallsBackToPlainTitle() {
        #expect(model(row(), marked: "").highlightedTitle == nil)
    }

    @Test
    func markedTitleHighlightsOnlyTheMatchedRuns() throws {
        let highlighted = try #require(model(row(), marked: "ab\u{2}cd\u{3}ef").highlightedTitle)
        #expect(String(highlighted.characters) == "abcdef")
        let runs = highlighted.runs.map { (String(highlighted[$0.range].characters), $0.backgroundColor != nil) }
        #expect(runs.map(\.0) == ["ab", "cd", "ef"])
        #expect(runs.map(\.1) == [false, true, false])
    }

    @Test
    func unterminatedMarkerHighlightsToTheEnd() throws {
        let highlighted = try #require(ArticleRowModel.highlighted("a\u{2}bc") as AttributedString?)
        let runs = highlighted.runs.map { (String(highlighted[$0.range].characters), $0.backgroundColor != nil) }
        #expect(runs.map(\.0) == ["a", "bc"])
        #expect(runs.map(\.1) == [false, true])
    }

    @Test
    func equalityComparesDisplayedValuesNotRowIdentity() {
        #expect(model(row()) == model(row()))
        #expect(model(row(isRead: 0)) != model(row(isRead: 1)))
    }

    // MARK: - ArticleRowList.build

    private func build(
        _ rows: [ArticleListRow],
        feedTitle: String = "Feed",
        reusing previous: ArticleRowList = .empty,
        zoneId: String = "UTC",
        generation: Int = 1
    ) -> ArticleRowList {
        ArticleRowList.build(
            rows,
            feedInfo: ["f1": FeedRowInfo(title: feedTitle, faviconUrl: nil)],
            reusing: previous,
            zoneId: zoneId,
            generation: generation,
            makeZone: { utc }
        )
    }

    @Test
    func unchangedRowIsReused() {
        let first = build([row()])
        let second = build([row()], reusing: first)
        // An equal but distinct Kotlin row: reuse keeps the previous model, and with it its row.
        #expect(second.rows[0].row === first.rows[0].row)
        #expect(second.indexById == ["a1": 0])
    }

    @Test
    func fullyReusedListKeepsThePreviousGeneration() {
        let first = build([row(id: "a1"), row(id: "a2")], generation: 1)
        let second = build([row(id: "a1"), row(id: "a2")], reusing: first, generation: 2)
        #expect(second.generation == 1)
        #expect(second.indexById == first.indexById)
    }

    @Test
    func reorderedRowsTakeANewGeneration() {
        let first = build([row(id: "a1"), row(id: "a2")], generation: 1)
        let second = build([row(id: "a2"), row(id: "a1")], reusing: first, generation: 2)
        #expect(second.generation == 2)
        #expect(second.rows.map(\.id) == ["a2", "a1"])
        #expect(second.indexById == ["a2": 0, "a1": 1])
        // Moved, not rebuilt.
        #expect(second.rows[0].row === first.rows[1].row)
    }

    @Test
    func removedRowTakesANewGeneration() {
        let first = build([row(id: "a1"), row(id: "a2")], generation: 1)
        let second = build([row(id: "a1")], reusing: first, generation: 2)
        #expect(second.generation == 2)
        #expect(second.indexById == ["a1": 0])
    }

    @Test
    func changedRowTakesANewGeneration() {
        let first = build([row(isRead: 0)], generation: 1)
        #expect(build([row(isRead: 1)], reusing: first, generation: 2).generation == 2)
    }

    @Test
    func searchResultsCarryTheirMarkedTitle() throws {
        let result = ArticleSearchResult(article: row(), titleMarked: "\u{2}Ti\u{3}tle")
        let list = ArticleRowList.build(
            [result],
            feedInfo: [:],
            reusing: .empty,
            zoneId: "UTC",
            generation: 1,
            makeZone: { utc }
        )
        #expect(list.rows[0].markedTitle == "\u{2}Ti\u{3}tle")
        #expect(try #require(list.rows[0].highlightedTitle).characters.count == 5)
    }

    @Test
    func backgroundBuildMatchesTheSynchronousOne() async {
        let first = build([row(id: "a1")], generation: 1)
        let second = await ArticleRowList.buildInBackground(
            [row(id: "a1")],
            feedInfo: ["f1": FeedRowInfo(title: "Feed", faviconUrl: nil)],
            reusing: first,
            zoneId: "UTC",
            generation: 2
        )
        #expect(second.generation == 1)
    }

    @Test
    func changedRowIsRebuilt() {
        let first = build([row(isRead: 0)])
        let fresh = row(isRead: 1)
        let second = build([fresh], reusing: first)
        #expect(second.rows[0].row === fresh)
        #expect(second.rows[0].isRead)
    }

    @Test
    func changedFeedTitleRebuildsTheRow() {
        let first = build([row()], feedTitle: "Old")
        let fresh = row()
        let second = build([fresh], feedTitle: "New", reusing: first)
        #expect(second.rows[0].row === fresh)
        #expect(second.rows[0].feedTitle == "New")
    }

    @Test
    func zoneChangeRebuildsEveryRow() {
        let first = build([row()], zoneId: "UTC")
        let fresh = row()
        let second = build([fresh], reusing: first, zoneId: "Asia/Tokyo")
        #expect(second.rows[0].row === fresh)
    }

    @Test
    func zoneIsResolvedOnlyWhenARowIsBuilt() {
        let first = build([row()])
        var resolved = 0
        _ = ArticleRowList.build(
            [row()],
            feedInfo: ["f1": FeedRowInfo(title: "Feed", faviconUrl: nil)],
            reusing: first,
            zoneId: "UTC",
            generation: 2,
            makeZone: { resolved += 1; return utc }
        )
        #expect(resolved == 0)
    }

    // MARK: - ArticleRowMenuState

    /// A macOS right-click on an unselected row selects it — marking it read — before the menu
    /// shows, so the menu must offer "Mark as unread".
    @Test
    func unreadUnselectedRowIsReadOnceAMacContextClickOpensItsMenu() {
        #expect(ArticleRowMenuState.readAfterContextMenuOpen(isRead: false, isSelected: false, selectsOnContextClick: true))
    }

    /// An already-selected row is not selected again, so an article the user marked unread stays so.
    @Test
    func unreadSelectedRowStaysUnread() {
        #expect(!ArticleRowMenuState.readAfterContextMenuOpen(isRead: false, isSelected: true, selectsOnContextClick: true))
    }

    @Test(arguments: [(false, true), (false, false), (true, true), (true, false)])
    func readRowStaysRead(isSelected: Bool, selectsOnContextClick: Bool) {
        #expect(ArticleRowMenuState.readAfterContextMenuOpen(isRead: true, isSelected: isSelected, selectsOnContextClick: selectsOnContextClick))
    }

    /// An iOS long-press selects nothing, so the menu reflects the row's current state.
    @Test(arguments: [false, true])
    func longPressThatDoesNotSelectNeverFlipsTheReadState(isSelected: Bool) {
        #expect(!ArticleRowMenuState.readAfterContextMenuOpen(isRead: false, isSelected: isSelected, selectsOnContextClick: false))
    }

    @Test
    func contextClickSelectsOnlyOnMacOS() {
        #if os(macOS)
        #expect(ArticleRowMenuState.selectsOnContextClick)
        #else
        #expect(!ArticleRowMenuState.selectsOnContextClick)
        #endif
    }
}
