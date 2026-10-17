import Testing

/// `RefreshStatus` decides what the iOS toolbar's status area says about a feed refresh.
@Suite
struct RefreshStatusTests {
    @Test
    func aRefreshOrACycleIsChecking() {
        #expect(RefreshStatus.isChecking(feedRefreshing: true, refreshCycleRunning: false))
        #expect(RefreshStatus.isChecking(feedRefreshing: false, refreshCycleRunning: true))
    }

    @Test
    func idleIsNotChecking() {
        #expect(!RefreshStatus.isChecking(feedRefreshing: false, refreshCycleRunning: false))
    }

    @Test
    func newArticlesAreCounted() {
        #expect(RefreshStatus.result(newArticleCount: 3, allFailed: false) == .newArticles(3))
    }

    @Test
    func nothingNewSaysSo() {
        #expect(RefreshStatus.result(newArticleCount: 0, allFailed: false) == .noNewArticles)
    }

    @Test
    func anAllFailedRefreshIsAFailure() {
        #expect(RefreshStatus.result(newArticleCount: 0, allFailed: true) == .refreshFailed)
    }
}
