import Testing

@Suite
struct HomeRestorationTests {
    private let stored = ReaderScrollPosition(articleId: "a", fraction: 0.5)

    private func readerScroll(_ restoration: HomeRestoration) -> ReaderScrollPosition? {
        restoration.readerScroll(articleId: stored.articleId, fraction: stored.fraction)
    }

    @Test
    func theRestoredSelectionArrivingKeepsTheReaderPosition() {
        var restoration = HomeRestoration()
        restoration.selectionChanged(from: nil, to: "a")
        #expect(readerScroll(restoration) == stored)
    }

    @Test
    func theReaderIsRestoredWithItsSelectedArticle() {
        var restoration = HomeRestoration()
        let moved = restoration.appeared(storedColumn: .detail, hasSelectedArticle: true)
        #expect(moved == nil)
        #expect(readerScroll(restoration) == stored)
    }

    @Test
    func theReaderWithoutAnArticleMovesToTheArticleListOnce() {
        var restoration = HomeRestoration()
        let first = restoration.appeared(storedColumn: .detail, hasSelectedArticle: false)
        let second = restoration.appeared(storedColumn: .detail, hasSelectedArticle: false)
        #expect(first == .content)
        #expect(second == nil)
    }

    @Test
    func theRestoredColumnMoveIsNotTheUsersNavigation() {
        var restoration = HomeRestoration()
        _ = restoration.appeared(storedColumn: .detail, hasSelectedArticle: false)
        // Not the user's: no article-list rebuild, no return flash, and the position stays offered.
        let restoredMove = restoration.columnChanged()
        #expect(!restoredMove)
        #expect(readerScroll(restoration) == stored)
        // The next move is the user's again.
        let usersMove = restoration.columnChanged()
        #expect(usersMove)
    }

    @Test
    func theUsersNavigationWithdrawsTheReaderPosition() {
        var restoration = HomeRestoration()
        _ = restoration.appeared(storedColumn: .detail, hasSelectedArticle: true)
        let usersMove = restoration.columnChanged()
        #expect(usersMove)
        #expect(readerScroll(restoration) == nil)
    }

    @Test
    func theUserSelectingAnotherArticleWithdrawsTheReaderPosition() {
        var restoration = HomeRestoration()
        restoration.selectionChanged(from: "a", to: "b")
        #expect(readerScroll(restoration) == nil)
    }

    @Test
    func clearingTheSelectionWithdrawsTheReaderPosition() {
        var restoration = HomeRestoration()
        restoration.selectionChanged(from: "a", to: nil)
        #expect(readerScroll(restoration) == nil)
    }

    @Test
    func emptyStorageOffersNoPosition() {
        let restoration = HomeRestoration()
        #expect(restoration.readerScroll(articleId: "", fraction: 0.5) == nil)
    }

    /// Scene storage from an earlier launch is read back by these keys: renaming one would lose it.
    @Test
    func theSceneStorageKeysStayAsTheyWere() {
        #expect(HomeRestoration.compactColumnKey == "home.compactColumn")
        #expect(HomeRestoration.readerScrollArticleIdKey == "reader.scrollArticleId")
        #expect(HomeRestoration.readerScrollFractionKey == "reader.scrollFraction")
    }
}
