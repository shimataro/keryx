import Testing

/// `ArticleListRemount` rebuilds the article list only when a phone-width stack brings it forward
/// from the sidebar, so every other route keeps the list's scroll position.
@Suite
struct ArticleListRemountTests {
    @Test
    func compactSidebarToArticleListRebuilds() {
        #expect(ArticleListRemount.isNeeded(isCompact: true, from: .sidebar, to: .content))
    }

    @Test
    func compactReturnFromTheReaderKeepsTheList() {
        #expect(!ArticleListRemount.isNeeded(isCompact: true, from: .detail, to: .content))
    }

    @Test
    func compactOpeningTheReaderKeepsTheList() {
        #expect(!ArticleListRemount.isNeeded(isCompact: true, from: .content, to: .detail))
    }

    @Test
    func compactSidebarToTheReaderKeepsTheList() {
        #expect(!ArticleListRemount.isNeeded(isCompact: true, from: .sidebar, to: .detail))
    }

    @Test
    func regularWidthNeverRebuilds() {
        #expect(!ArticleListRemount.isNeeded(isCompact: false, from: .sidebar, to: .content))
    }
}
