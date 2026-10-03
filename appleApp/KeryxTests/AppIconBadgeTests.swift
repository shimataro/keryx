import Testing

@Suite
struct AppIconBadgeTests {
    @Test
    func negativeCountIsZero() {
        #expect(appIconBadgeCount(unreadCount: -5) == 0)
    }

    @Test
    func countIsKeptExactly() {
        #expect(appIconBadgeCount(unreadCount: 0) == 0)
        #expect(appIconBadgeCount(unreadCount: 1234) == 1234)
    }

    @Test
    func hugeCountIsClamped() {
        #expect(appIconBadgeCount(unreadCount: Int64.max) == Int.max)
    }
}
