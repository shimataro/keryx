import Testing

@Suite
struct CompactColumnRestorationTests {
    @Test(arguments: [CompactSearchNavigation.Column.sidebar, .content, .detail])
    func everyColumnRoundTripsThroughStorage(column: CompactSearchNavigation.Column) {
        let stored = CompactColumnRestoration.storageValue(column)
        #expect(CompactColumnRestoration.column(storageValue: stored) == column)
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
