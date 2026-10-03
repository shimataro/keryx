import KeryxShared
import Testing

/// Covers `ArticleListHeaderIcon`: the article list heading shows the selected item's own icon.
@Suite
struct ArticleListHeaderIconTests {
    private typealias F = SidebarFixtures

    private func icon(_ filter: ArticleFilter) -> SidebarRowIcon {
        ArticleListHeaderIcon.resolve(filter: filter, model: F.model())
    }

    @Test
    func allAndStarredUseTheirSidebarSymbols() {
        #expect(icon(ArticleFilterAll()) == .symbol("doc.text.fill"))
        #expect(icon(ArticleFilterStarred()) == .symbol("star"))
    }

    @Test
    func aFeedShowsItsFavicon() {
        #expect(icon(ArticleFilterFeed(feedId: "a")) == .favicon(url: "https://example.com/a.ico"))
    }

    @Test
    func aFolderShowsTheFolderSymbolAndATagItsColorDot() {
        #expect(icon(ArticleFilterFolder(folderId: "d1")) == .symbol("folder"))
        #expect(icon(ArticleFilterTag(tagId: "t1")) == .tagColor(hex: nil))
    }

    @Test
    func aMissingFeedFolderOrTagFallsBackToAll() {
        #expect(icon(ArticleFilterFeed(feedId: "gone")) == .symbol("doc.text.fill"))
        #expect(icon(ArticleFilterFolder(folderId: "gone")) == .symbol("doc.text.fill"))
        #expect(icon(ArticleFilterTag(tagId: "gone")) == .symbol("doc.text.fill"))
    }
}
