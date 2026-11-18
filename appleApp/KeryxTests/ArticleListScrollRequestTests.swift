import Testing

/// `ArticleListScrollRequest.listShowsRows` decides when a scroll asked of the iOS article list can
/// reach it.
@Suite
struct ArticleListScrollRequestTests {
    @Test
    func rowsBehindAPendingBodyAreNotShown() {
        #expect(!ArticleListScrollRequest.listShowsRows(bodyIsContent: false, rowCount: 3, searchActive: false, searchShowsList: false))
    }

    @Test
    func rowsUnderAContentBodyAreShown() {
        #expect(ArticleListScrollRequest.listShowsRows(bodyIsContent: true, rowCount: 3, searchActive: false, searchShowsList: false))
    }

    @Test
    func noRowsAreNotShown() {
        #expect(!ArticleListScrollRequest.listShowsRows(bodyIsContent: true, rowCount: 0, searchActive: false, searchShowsList: false))
    }

    @Test
    func searchRowsAreNotShownUntilTheSearchShowsTheList() {
        #expect(!ArticleListScrollRequest.listShowsRows(bodyIsContent: true, rowCount: 3, searchActive: true, searchShowsList: false))
        #expect(ArticleListScrollRequest.listShowsRows(bodyIsContent: true, rowCount: 3, searchActive: true, searchShowsList: true))
    }
}
