import Testing

@Suite
struct CompactColumnRestorationTests {
    @Test(arguments: [CompactSearchNavigation.Column.sidebar, .content, .detail])
    func everyColumnRoundTripsThroughStorage(column: CompactSearchNavigation.Column) {
        #expect(CompactColumnRestoration.column(storageValue: column.rawValue) == column)
    }

    /// Scene storage holds these values from earlier launches: renaming one would lose the column.
    @Test
    func theStoredValuesStayAsTheyWere() {
        #expect(CompactSearchNavigation.Column.sidebar.rawValue == "sidebar")
        #expect(CompactSearchNavigation.Column.content.rawValue == "content")
        #expect(CompactSearchNavigation.Column.detail.rawValue == "detail")
    }

    @Test
    func anUnrecognisedValueStartsAtTheSidebar() {
        #expect(CompactColumnRestoration.column(storageValue: "") == .sidebar)
        #expect(CompactColumnRestoration.column(storageValue: "reader") == .sidebar)
    }

    @Test
    func theReaderIsRestoredWithItsArticle() {
        #expect(CompactColumnRestoration.restored(.detail, hasSelectedArticle: true) == .detail)
    }

    @Test
    func theReaderWithoutAnArticleFallsBackToTheArticleList() {
        #expect(CompactColumnRestoration.restored(.detail, hasSelectedArticle: false) == .content)
    }

    @Test
    func theSidebarAndTheArticleListAreRestoredAsTheyWere() {
        #expect(CompactColumnRestoration.restored(.sidebar, hasSelectedArticle: false) == .sidebar)
        #expect(CompactColumnRestoration.restored(.content, hasSelectedArticle: false) == .content)
        #expect(CompactColumnRestoration.restored(.content, hasSelectedArticle: true) == .content)
    }
}
