import Testing

@Suite
struct DockBadgeTests {
    @Test
    func noUnreadClearsBadge() {
        #expect(dockBadgeLabel(unreadCount: 0) == nil)
        #expect(dockBadgeLabel(unreadCount: -1) == nil)
    }

    @Test
    func countIsShownExactly() {
        #expect(dockBadgeLabel(unreadCount: 1) == "1")
        #expect(dockBadgeLabel(unreadCount: 99) == "99")
        #expect(dockBadgeLabel(unreadCount: 100) == "100")
        #expect(dockBadgeLabel(unreadCount: 1234) == "1234")
    }
}
