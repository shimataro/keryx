import KeryxShared
import Testing

/// Covers the pure rules behind the reader's pages: which pages the iOS pager shows and hydrates
/// (`ReaderPagerPages`), what each page shows (`ReaderPageSource`), and what the single macOS reader
/// shows (`ReaderPageInputs`). Platform-independent, so the macOS test run covers them all.
@Suite
struct ReaderPageSourceTests {
    private func listRow(_ id: String) -> ArticleListRow {
        ArticleListRow(
            id: id, feed_id: "f1", title: "Title \(id)", url: "https://example.com/\(id)",
            published_at: nil, created_at: 0, is_read: 0, is_starred: 0
        )
    }

    private func article(_ id: String, feedId: String = "f1") -> Articles {
        Articles(
            id: id, feed_id: feedId, guid: id, url: "https://example.com/\(id)", title: "Title \(id)",
            summary: nil, content: "<p>\(id)</p>", author: nil, published_at: nil, thumbnail_url: nil,
            is_read: 0, read_at: nil, is_starred: 0, starred_at: nil, cached_at: 0, search_text: "",
            updated_at: 0, created_at: 0, deleted_at: nil, deleted_updated_at: nil
        )
    }

    // MARK: - ReaderPagerPages.resolve

    @Test
    func aSelectionInTheListLeavesThePagesAlone() {
        let rows = [listRow("a"), listRow("b"), listRow("c")]
        let index = ReaderPagerIndex(rows: rows)

        let pages = ReaderPagerPages.resolve(pagerRows: rows, index: index, selected: rows[1])

        #expect(pages.ids == ["a", "b", "c"])
        #expect(pages.rows.count == 3)
    }

    @Test
    func noSelectionLeavesThePagesAlone() {
        let rows = [listRow("a"), listRow("b")]

        let pages = ReaderPagerPages.resolve(pagerRows: rows, index: ReaderPagerIndex(rows: rows), selected: nil)

        #expect(pages.ids == ["a", "b"])
    }

    /// The reader stays on one page for the selection while it is not in the list (before the first
    /// emission, or after a tombstone), rather than clamping onto — and selecting — another article.
    @Test
    func aSelectionMissingFromTheListIsThePagerOnlyPage() {
        let rows = [listRow("a"), listRow("b")]

        let pages = ReaderPagerPages.resolve(pagerRows: rows, index: ReaderPagerIndex(rows: rows), selected: listRow("z"))
        #expect(pages.ids == ["z"])
        #expect(pages.indexById == ["z": 0])

        let beforeTheFirstEmission = ReaderPagerPages.resolve(pagerRows: [], index: .empty, selected: listRow("z"))
        #expect(beforeTheFirstEmission.ids == ["z"])
    }

    // MARK: - ReaderPagerPages.neighbourIds

    @Test
    func neighboursAreThePagesEitherSide() {
        let rows = [listRow("a"), listRow("b"), listRow("c"), listRow("d")]
        let pages = ReaderPagerPages(rows: rows, index: ReaderPagerIndex(rows: rows))

        #expect(pages.neighbourIds(of: "b") == ["a", "c"])
        #expect(pages.neighbourIds(of: "a") == ["b"])
        #expect(pages.neighbourIds(of: "d") == ["c"])
    }

    @Test
    func noNeighboursForNoSelectionOrAnUnknownOne() {
        let rows = [listRow("a"), listRow("b")]
        let pages = ReaderPagerPages(rows: rows, index: ReaderPagerIndex(rows: rows))

        #expect(pages.neighbourIds(of: nil).isEmpty)
        #expect(pages.neighbourIds(of: "zzz").isEmpty)
        #expect(ReaderPagerPages(rows: [listRow("a")], index: ReaderPagerIndex(ids: ["a"])).neighbourIds(of: "a").isEmpty)
    }

    // MARK: - ReaderPageSource.resolve

    @Test
    func theSelectedPageShowsItsLoadedArticleOverTheHeldCopy() {
        let loaded = article("a")
        let held = article("a").toReaderRow()

        let source = ReaderPageSource.resolve(isSelected: true, loaded: loaded, cached: held)

        #expect(source.row?.id == "a")
        #expect(source.revision == ObjectIdentifier(loaded))
        #expect(source.feedId == "f1")
    }

    @Test
    func anotherPageShowsItsHeldCopyEvenWhenAnArticleIsLoaded() {
        let loadedForTheSelection = article("a")
        let held = article("b", feedId: "f2").toReaderRow()

        let source = ReaderPageSource.resolve(isSelected: false, loaded: loadedForTheSelection, cached: held)

        #expect(source.row === held)
        #expect(source.revision == ObjectIdentifier(held))
        #expect(source.feedId == "f2")
    }

    @Test
    func theSelectedPageFallsBackToItsHeldCopyUntilItsBodyLoads() {
        let held = article("a").toReaderRow()

        let source = ReaderPageSource.resolve(isSelected: true, loaded: nil, cached: held)

        #expect(source.row === held)
        #expect(source.revision == ObjectIdentifier(held))
    }

    @Test
    func aPageWithNothingYetHasNoContent() {
        let source = ReaderPageSource.resolve(isSelected: false, loaded: nil, cached: nil)

        #expect(source.row == nil)
        #expect(source.revision == nil)
        #expect(source.feedId == nil)
    }

    // MARK: - ReaderPageInputs.resolve

    @Test
    func noContentIsTheBlankInputs() {
        let inputs = ReaderPageInputs.resolve(ReaderContentNone.shared)

        #expect(inputs.row == nil)
        #expect(inputs.header == nil)
        #expect(inputs.revision == nil)
    }

    @Test
    func aHeaderIsShownAloneKeyedByItsRow() {
        let row = listRow("a")

        let inputs = ReaderPageInputs.resolve(ReaderContentHeader(row: row))

        #expect(inputs.row == nil)
        #expect(inputs.header === row)
        #expect(inputs.revision == ObjectIdentifier(row))
    }

    @Test
    func aLoadedArticleIsKeyedByItsInstanceSoAToggleRebuildsTheDocument() {
        let first = article("a")
        let toggled = article("a")

        let a = ReaderPageInputs.resolve(ReaderContentFull(article: first))
        let b = ReaderPageInputs.resolve(ReaderContentFull(article: toggled))

        #expect(a.row?.id == "a")
        #expect(a.header == nil)
        #expect(a.revision == ObjectIdentifier(first))
        #expect(a.revision != b.revision)
    }
}
