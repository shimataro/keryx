import Testing

/// `RefreshStatus` decides what the iOS toolbar's status area says about a feed refresh.
@Suite
struct RefreshStatusTests {
    @Test
    func newArticlesAreCounted() {
        #expect(RefreshStatus.result(newArticleCount: 3, unreachable: false) == .newArticles(3))
    }

    @Test
    func nothingNewSaysSo() {
        #expect(RefreshStatus.result(newArticleCount: 0, unreachable: false) == .noNewArticles)
    }

    @Test
    func anUnreachableRefreshIsAFailure() {
        #expect(RefreshStatus.result(newArticleCount: 0, unreachable: true) == .refreshFailed)
    }
}
