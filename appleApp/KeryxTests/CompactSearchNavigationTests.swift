import Testing

/// `CompactSearchNavigation` brings the collapsed stack's article list — where iOS keeps the search
/// field — forward for a search-focus request, and leaves every other layout alone.
@Suite
struct CompactSearchNavigationTests {
    @Test
    func compactSidebarBringsTheArticleListForward() {
        #expect(CompactSearchNavigation.column(isCompact: true, current: .sidebar) == .content)
    }

    @Test
    func compactReaderBringsTheArticleListForward() {
        #expect(CompactSearchNavigation.column(isCompact: true, current: .detail) == .content)
    }

    @Test
    func compactArticleListStaysPut() {
        #expect(CompactSearchNavigation.column(isCompact: true, current: .content) == nil)
    }

    @Test
    func regularWidthNeverMoves() {
        #expect(CompactSearchNavigation.column(isCompact: false, current: .sidebar) == nil)
        #expect(CompactSearchNavigation.column(isCompact: false, current: .content) == nil)
        #expect(CompactSearchNavigation.column(isCompact: false, current: .detail) == nil)
    }
}
