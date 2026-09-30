import KeryxShared
import Testing

/// Covers `SidebarDropPreview`: the outline a drop is predicted to leave must be the one the model
/// rebuilds once the repositories have applied the same move.
@Suite
struct SidebarDropPreviewTests {
    private typealias F = SidebarFixtures

    private func move(_ feedId: String, into folderId: String?, before targetId: String?) -> FeedListDropAction {
        FeedListDropActionMoveFeed(feedId: feedId, folderId: folderId, targetFeedId: targetId)
    }

    private func predicted(_ action: FeedListDropAction) -> SidebarOutline? {
        SidebarDropPreview.outline(after: action, in: F.outline())
    }

    private func visible(_ outline: SidebarOutline?, _ section: SidebarSection) -> [SidebarItemID]? {
        outline?.section(section)?.nodes.flatMap { node in [node.id] + (outline?.section(section)?.descendants(of: node.id) ?? []) }
    }

    @Test
    func reorderingAFolderCarriesItsFeeds() {
        let outline = predicted(FeedListDropActionReorderFolder(draggedFolderId: "d1", targetFolderId: "d3"))
        let expected = F.outline(folders: [
            F.folder("d2", sortOrder: 1), F.folder("d1", sortOrder: 2), F.folder("d3", sortOrder: 3), F.folder("d4", sortOrder: 4),
        ])
        #expect(outline == expected)
    }

    @Test
    func reorderingAFolderToTheEndAppends() {
        let outline = predicted(FeedListDropActionReorderFolder(draggedFolderId: "d1", targetFolderId: nil))
        let expected = F.outline(folders: [
            F.folder("d2", sortOrder: 1), F.folder("d3", sortOrder: 2), F.folder("d4", sortOrder: 3), F.folder("d1", sortOrder: 4),
        ])
        #expect(outline == expected)
    }

    @Test
    func reorderingAFeedWithinItsFolder() {
        let outline = predicted(move("b", into: "d1", before: "a"))
        let expected = F.outline(feeds: [
            F.feed("b", sortOrder: 0, folderId: "d1"), F.feed("a", sortOrder: 1, folderId: "d1"),
            F.feed("c", sortOrder: 3, folderId: "d2"), F.feed("e", sortOrder: 4, folderId: "d4"),
            F.feed("u1", sortOrder: 5), F.feed("u2", sortOrder: 6),
        ])
        #expect(outline == expected)
    }

    @Test(arguments: [
        ("a", "d2", nil as String?),
        ("a", "d3", nil),
        ("a", "d4", nil),
        ("c", "d1", "b"),
    ])
    func movingAFeedIntoAFolder(feedId: String, folderId: String, before targetId: String?) {
        let outline = predicted(move(feedId, into: folderId, before: targetId))
        #expect(outline != nil)
        #expect(visible(outline, .folders)?.contains(.feed(feedId)) == true)
        let owner = outline?.section(.folders)?.nodes.first?.children.first { folder in
            outline?.section(.folders)?.descendants(of: folder.id).contains(.feed(feedId)) == true
        }
        #expect(owner?.id == .folder(folderId))
        let siblings = owner?.children.map(\.id) ?? []
        if let targetId {
            #expect(siblings.firstIndex(of: .feed(feedId))! + 1 == siblings.firstIndex(of: .feed(targetId)))
        } else {
            #expect(siblings.last == .feed(feedId))
        }
    }

    @Test
    func movingAFeedOutOfItsFolderAppendsToNoFolder() {
        let outline = predicted(move("a", into: nil, before: nil))
        #expect(outline?.section(.noFolder)?.nodes.map(\.id) == [.noFolderHeader, .feed("u1"), .feed("u2"), .feed("a")])
        #expect(outline?.section(.folders)?.descendants(of: .folder("d1")) == [.feed("b")])
    }

    @Test
    func movingAnUnfolderedFeedAmongTheOthers() {
        let outline = predicted(move("u2", into: nil, before: "u1"))
        #expect(outline?.section(.noFolder)?.nodes.map(\.id) == [.noFolderHeader, .feed("u2"), .feed("u1")])
    }

    @Test
    func theCopyUnderATagStaysPut() {
        let outline = predicted(move("a", into: "d2", before: nil))
        #expect(outline?.section(.tags) == F.outline().section(.tags))
    }

    @Test
    func taggingAFeedChangesNoStructure() {
        #expect(predicted(FeedListDropActionAttachTag(feedId: "a", tagId: "t2")) == nil)
    }

    @Test
    func unknownRowsAndNoOpsGiveNothing() {
        #expect(predicted(move("nope", into: "d1", before: nil)) == nil)
        #expect(predicted(move("a", into: "nope", before: nil)) == nil)
        #expect(predicted(move("a", into: "d1", before: "a")) == nil)
        #expect(predicted(FeedListDropActionReorderFolder(draggedFolderId: "nope", targetFolderId: nil)) == nil)
    }
}
