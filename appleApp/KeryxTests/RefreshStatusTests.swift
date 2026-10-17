import Testing

/// `RefreshStatus.from` picks what the iOS toolbar's status area shows.
@Suite
struct RefreshStatusTests {
    @Test
    func nothingWhileIdle() {
        #expect(RefreshStatus.from(refreshIndicatorShown: false, syncing: false) == nil)
    }

    @Test
    func refreshingWhileRefreshing() {
        #expect(RefreshStatus.from(refreshIndicatorShown: true, syncing: false) == .refreshing)
    }

    @Test
    func syncingWhileSyncing() {
        #expect(RefreshStatus.from(refreshIndicatorShown: false, syncing: true) == .syncing)
    }

    @Test
    func refreshWinsWhenBothHold() {
        #expect(RefreshStatus.from(refreshIndicatorShown: true, syncing: true) == .refreshing)
    }
}
