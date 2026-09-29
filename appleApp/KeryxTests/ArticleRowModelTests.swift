import KeryxShared
import SwiftUI
import Testing

/// Covers `ArticleRowModel`, the article list's per-emission display rows: the fields it resolves
/// from the Kotlin row, and the search-highlight splitting moved there from `ArticleListView`.
@Suite
struct ArticleRowModelTests {
    private let utc = Kotlinx_datetimeTimeZone.Companion.shared.of(zoneId: "UTC")

    private func row(title: String = "Title", publishedAt: Int64? = 0, isRead: Int64 = 0, isStarred: Int64 = 0) -> ArticleListRow {
        ArticleListRow(
            id: "a1",
            feed_id: "f1",
            title: title,
            url: "https://example.com/a1",
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
        zoneId: String = "UTC"
    ) -> ArticleRowList {
        ArticleRowList.build(
            rows.map { (row: $0, markedTitle: nil) },
            feedInfo: ["f1": FeedRowInfo(title: feedTitle, faviconUrl: nil)],
            reusing: previous,
            zoneId: zoneId,
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
            [(row: row(), markedTitle: nil)],
            feedInfo: ["f1": FeedRowInfo(title: "Feed", faviconUrl: nil)],
            reusing: first,
            zoneId: "UTC",
            makeZone: { resolved += 1; return utc }
        )
        #expect(resolved == 0)
    }
}
