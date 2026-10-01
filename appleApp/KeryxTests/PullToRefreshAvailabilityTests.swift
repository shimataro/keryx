import Testing

/// `PullToRefreshAvailability` mirrors Compose's own `pullRefreshAvailable` (`HomeCommon.kt`).
@Suite
struct PullToRefreshAvailabilityTests {
    @Test
    func availableOverAPlainListWithFeeds() {
        #expect(PullToRefreshAvailability.isAvailable(searchActive: false, hasFeeds: true))
    }

    @Test
    func unavailableOverSearchResults() {
        #expect(!PullToRefreshAvailability.isAvailable(searchActive: true, hasFeeds: true))
    }

    @Test
    func unavailableWithNoFeeds() {
        #expect(!PullToRefreshAvailability.isAvailable(searchActive: false, hasFeeds: false))
    }
}
