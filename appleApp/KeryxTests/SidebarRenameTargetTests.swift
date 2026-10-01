import KeryxShared
import Testing

/// Covers `SidebarRenameTarget`: what the iOS rename sheet edits for each kind of row.
@Suite
struct SidebarRenameTargetTests {
    private typealias F = SidebarFixtures

    private func resolve(_ item: SidebarItemID, feeds: [Feeds] = F.feeds) -> SidebarRenameTarget? {
        SidebarRenameTarget.resolve(
            item, folders: F.folders, tags: F.tags, feedsById: Dictionary(uniqueKeysWithValues: feeds.map { ($0.id, $0) })
        )
    }

    @Test
    func aFolderStartsFromItsNameAndForbidsBlankAndDuplicates() {
        let target = resolve(.folder("d1"))
        #expect(target?.kind == .folder(id: "d1"))
        #expect(target?.initialName == "name-d1")
        #expect(target?.allowBlank == false)
        #expect(target?.duplicateMessageKey == "home_folder_name_duplicate")
    }

    @Test
    func aTagStartsFromItsNameAndForbidsBlankAndDuplicates() {
        let target = resolve(.tag("t1"))
        #expect(target?.kind == .tag(id: "t1"))
        #expect(target?.initialName == "name-t1")
        #expect(target?.allowBlank == false)
        #expect(target?.duplicateMessageKey == "home_tag_name_duplicate")
    }

    @Test
    func aFeedMayBeBlankedAndFallsBackToItsParsedTitle() {
        let feeds = [F.feed("a", sortOrder: 1, customTitle: "Mine")]
        let target = resolve(.feed("a"), feeds: feeds)
        #expect(target?.kind == .feed(id: "a"))
        #expect(target?.initialName == "Mine")
        #expect(target?.placeholder == "a")
        #expect(target?.allowBlank == true)
        #expect(target?.duplicateMessageKey == nil)
    }

    @Test
    func aFeedUnderATagRenamesTheSameFeed() {
        #expect(resolve(.feedInTag(feedId: "a", tagId: "t1"))?.kind == .feed(id: "a"))
    }

    @Test
    func rowsWithoutANameOrWithoutAnEntityResolveToNothing() {
        #expect(resolve(.all) == nil)
        #expect(resolve(.starred) == nil)
        #expect(resolve(.sectionHeader(.folders)) == nil)
        #expect(resolve(.noFolderHeader) == nil)
        #expect(resolve(.folder("missing")) == nil)
        #expect(resolve(.feed("missing")) == nil)
    }
}
