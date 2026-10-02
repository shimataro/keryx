import KeryxShared
import Testing

/// Covers `SidebarReorderTargets`: where VoiceOver's move up / move down land a feed or folder.
@Suite
struct SidebarReorderTargetsTests {
    private typealias F = SidebarFixtures

    // Folders d1 (feeds a, b), d2 (c), d3 (empty), d4 (e); unfoldered feeds u1, u2.
    private let model = F.model()

    @Test
    func aFeedMovesAmongTheFeedsOfItsOwnFolder() {
        #expect(SidebarReorderTargets.move(for: .feed("b"), direction: .up, model: model)
            == .feed(feedId: "b", folderId: "d1", insertBeforeId: "a"))
        // Moving down the last position appends: nothing follows it to land before.
        #expect(SidebarReorderTargets.move(for: .feed("a"), direction: .down, model: model)
            == .feed(feedId: "a", folderId: "d1", insertBeforeId: nil))
    }

    @Test
    func anUnfolderedFeedMovesAmongTheUnfolderedOnes() {
        #expect(SidebarReorderTargets.move(for: .feed("u2"), direction: .up, model: model)
            == .feed(feedId: "u2", folderId: nil, insertBeforeId: "u1"))
    }

    @Test
    func aFolderMovesAmongTheFolders() {
        #expect(SidebarReorderTargets.move(for: .folder("d2"), direction: .up, model: model)
            == .folder(folderId: "d2", insertBeforeId: "d1"))
        #expect(SidebarReorderTargets.move(for: .folder("d1"), direction: .down, model: model)
            == .folder(folderId: "d1", insertBeforeId: "d3"))
    }

    @Test
    func theFirstAndLastOfAScopeCannotMoveFurther() {
        #expect(SidebarReorderTargets.move(for: .feed("a"), direction: .up, model: model) == nil)
        #expect(SidebarReorderTargets.move(for: .feed("b"), direction: .down, model: model) == nil)
        #expect(SidebarReorderTargets.move(for: .folder("d1"), direction: .up, model: model) == nil)
        #expect(SidebarReorderTargets.move(for: .folder("d4"), direction: .down, model: model) == nil)
    }

    @Test
    func rowsOutsideEveryScopeNeverMove() {
        #expect(SidebarReorderTargets.move(for: .feedInTag(feedId: "a", tagId: "t1"), direction: .down, model: model) == nil)
        #expect(SidebarReorderTargets.move(for: .tag("t1"), direction: .down, model: model) == nil)
        #expect(SidebarReorderTargets.move(for: .all, direction: .down, model: model) == nil)
        #expect(SidebarReorderTargets.move(for: .feed("missing"), direction: .down, model: model) == nil)
    }

    @Test
    func availabilityMatchesWhereAMoveExists() {
        let all = SidebarReorderTargets.availability(model: model)
        #expect(all[.feed("a")] == SidebarMoveAvailability(canMoveUp: false, canMoveDown: true))
        #expect(all[.feed("b")] == SidebarMoveAvailability(canMoveUp: true, canMoveDown: false))
        // The only feed of its folder: nowhere to go.
        #expect(all[.feed("c")] == SidebarMoveAvailability.none)
        #expect(all[.folder("d2")] == SidebarMoveAvailability(canMoveUp: true, canMoveDown: true))
        #expect(all[.tag("t1")] == nil)
        #expect(all[.feedInTag(feedId: "a", tagId: "t1")] == nil)
    }

    @Test
    func availabilityAgreesWithMoveForEveryRow() {
        for (item, available) in SidebarReorderTargets.availability(model: model) {
            #expect((SidebarReorderTargets.move(for: item, direction: .up, model: model) != nil) == available.canMoveUp)
            #expect((SidebarReorderTargets.move(for: item, direction: .down, model: model) != nil) == available.canMoveDown)
        }
    }
}
