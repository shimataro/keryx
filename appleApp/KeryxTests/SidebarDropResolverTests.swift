import KeryxShared
import Testing

/// Covers `SidebarDropResolver`: how a drag over the iOS sidebar's collection view — onto a row or
/// into a gap, numbered the way UIKit numbers a move's destination — maps onto the shared
/// `resolveFeedListDropAction`.
///
/// The fixture's Folders section, all expanded but d4:
/// `[header, d1, a, b, d2, c, d3 (empty), d4 (collapsed; e hidden)]`; "No folder": `[header, u1, u2]`.
@Suite
struct SidebarDropResolverTests {
    private typealias F = SidebarFixtures
    private let outline = F.outline()
    private let index = F.model().dropIndex

    private func action(_ position: SidebarDropPosition, dragging dragged: SidebarItemID, outline: SidebarOutline? = nil, index: FeedListDropIndex? = nil) -> String? {
        SidebarDropResolver.action(
            for: position, dragging: dragged, outline: outline ?? self.outline, index: index ?? self.index
        ).map(describe)
    }

    private func describe(_ action: FeedListDropAction) -> String {
        switch onEnum(of: action) {
        case .moveFeed(let move): return "move \(move.feedId) to \(move.folderId ?? "-") before \(move.targetFeedId ?? "-")"
        case .attachTag(let attach): return "tag \(attach.feedId) with \(attach.tagId)"
        case .reorderFolder(let reorder): return "folder \(reorder.draggedFolderId) before \(reorder.targetFolderId ?? "-")"
        }
    }

    // MARK: - Into

    @Test(arguments: [
        (SidebarItemID.folder("d1"), 0.1, nil),
        (.folder("d1"), 0.24, nil),
        (.folder("d1"), 0.25, SidebarItemID.folder("d1")),
        (.folder("d1"), 0.9, .folder("d1")),
        (.noFolderHeader, 0.0, .noFolderHeader),
        (.tag("t1"), 0.1, .tag("t1")),
        (.feed("a"), 0.5, nil),
        (.feedInTag(feedId: "a", tagId: "t1"), 0.5, nil),
        (.sectionHeader(.folders), 0.5, nil),
        (.all, 0.5, nil),
    ] as [(SidebarItemID, Double, SidebarItemID?)])
    func aFeedDropsOntoFoldersBelowTheirTopQuarterAndOntoHeadersAndTags(item: SidebarItemID, fraction: Double, expected: SidebarItemID?) {
        #expect(SidebarDropResolver.intoTarget(over: item, fraction: fraction, dragging: FeedListDragPayload(kind: .feed, id: "u1")) == expected)
    }

    @Test
    func aFolderNeverDropsOntoARow() {
        let payload = FeedListDragPayload(kind: .folder, id: "d2")
        for item in [SidebarItemID.folder("d1"), .noFolderHeader, .tag("t1")] {
            #expect(SidebarDropResolver.intoTarget(over: item, fraction: 0.5, dragging: payload) == nil)
            #expect(action(.into(item), dragging: .folder("d2")) == nil)
        }
    }

    @Test
    func droppingAFeedOntoARow() {
        #expect(action(.into(.folder("d1")), dragging: .feed("u1")) == "move u1 to d1 before a")
        #expect(action(.into(.folder("d3")), dragging: .feed("u1")) == "move u1 to d3 before -")
        #expect(action(.into(.noFolderHeader), dragging: .feed("a")) == "move a to - before u1")
        #expect(action(.into(.tag("t2")), dragging: .feed("a")) == "tag a with t2")
        #expect(action(.into(.tag("t1")), dragging: .feedInTag(feedId: "a", tagId: "t1")) == "tag a with t1")
    }

    // MARK: - Gaps

    @Test(arguments: [
        (0, nil),                           // above the Folders header
        (1, nil),                           // the section's start, before the first folder
        (2, "move u1 to d1 before a"),      // d1 | a
        (3, "move u1 to d1 before b"),      // a | b
        (4, "move u1 to d1 before -"),      // b | d2: the end of d1
        (5, "move u1 to d2 before c"),      // d2 | c
        (6, "move u1 to d2 before -"),      // c | d3
        (7, "move u1 to d3 before -"),      // below the empty d3
        (8, nil),                           // below the collapsed d4
        (9, nil),                           // past the end
    ] as [(Int, String?)])
    func aFeedFromAnotherSectionBetweenFolderRows(gap: Int, expected: String?) {
        #expect(action(.gap(section: .folders, index: gap), dragging: .feed("u1")) == expected)
    }

    @Test
    func aFeedMovedWithinItsFolderCountsGapsWithoutItself() {
        // [header, d1, b, d2, c, d3, d4] with a lifted.
        #expect(action(.gap(section: .folders, index: 2), dragging: .feed("a")) == "move a to d1 before b")
        #expect(action(.gap(section: .folders, index: 3), dragging: .feed("a")) == "move a to d1 before -")
        #expect(action(.gap(section: .folders, index: 4), dragging: .feed("a")) == "move a to d2 before c")
    }

    @Test
    func aFeedBetweenUnfolderedFeeds() {
        #expect(action(.gap(section: .noFolder, index: 0), dragging: .feed("a")) == nil)
        #expect(action(.gap(section: .noFolder, index: 1), dragging: .feed("a")) == "move a to - before u1")
        #expect(action(.gap(section: .noFolder, index: 2), dragging: .feed("a")) == "move a to - before u2")
        #expect(action(.gap(section: .noFolder, index: 3), dragging: .feed("a")) == "move a to - before -")
        // Within the group, u1 lifted: [header, u2].
        #expect(action(.gap(section: .noFolder, index: 2), dragging: .feed("u1")) == "move u1 to - before -")
    }

    @Test
    func aFeedBelowAnEmptyNoFolderHeader() {
        let feeds = F.feeds.filter { $0.folder_id != nil }
        let outline = F.outline(feeds: feeds)
        let index = F.model(feeds: feeds).dropIndex
        #expect(action(.gap(section: .noFolder, index: 1), dragging: .feed("a"), outline: outline, index: index) == "move a to - before -")
    }

    @Test
    func gapsInAllStarredAndTheTagsAreRefused() {
        for gap in 0...4 {
            #expect(action(.gap(section: .smart, index: gap), dragging: .feed("u1")) == nil)
            #expect(action(.gap(section: .tags, index: gap), dragging: .feed("u1")) == nil)
            #expect(action(.gap(section: .tags, index: gap), dragging: .feedInTag(feedId: "a", tagId: "t1")) == nil)
        }
    }

    @Test(arguments: [
        (0, nil),                     // above the Folders header
        (1, "folder d1 before d2"),   // header | d2: the first place (d1 is already there)
        (2, "folder d1 before d3"),   // d2 | c: inside d2, so after it
        (3, "folder d1 before d3"),   // c | d3
        (4, "folder d1 before d4"),   // d3 | d4
        (5, "folder d1 before -"),    // after d4: the end
    ] as [(Int, String?)])
    func aFolderBetweenFoldersCountsGapsWithoutItselfAndItsFeeds(gap: Int, expected: String?) {
        // [header, d2, c, d3, d4] with d1 (and a, b) lifted.
        #expect(action(.gap(section: .folders, index: gap), dragging: .folder("d1")) == expected)
    }

    @Test
    func aFolderOutsideTheFoldersSectionIsRefused() {
        for section in [SidebarSection.smart, .noFolder, .tags] {
            for gap in 0...3 {
                #expect(action(.gap(section: section, index: gap), dragging: .folder("d1")) == nil)
            }
        }
    }

    @Test
    func undraggableRowsResolveNothing() {
        #expect(action(.into(.folder("d1")), dragging: .tag("t1")) == nil)
        #expect(action(.gap(section: .folders, index: 2), dragging: .all) == nil)
    }

    // MARK: - gapPosition

    @Test
    func gapPositionNamesTheSectionOfTheDestination() {
        // Sections: smart, folders, noFolder, tags.
        let position = SidebarDropResolver.gapPosition(destination: (2, 1), source: (1, 2), previous: nil, outline: outline)
        #expect(position == .gap(section: .noFolder, index: 1))
        #expect(SidebarDropResolver.gapPosition(destination: (4, 0), source: nil, previous: nil, outline: outline) == nil)
    }

    @Test
    func theDraggedItemsOwnIndexPathKeepsThePreviousPosition() {
        let previous = SidebarDropPosition.gap(section: .folders, index: 6)
        #expect(SidebarDropResolver.gapPosition(destination: (1, 2), source: (1, 2), previous: previous, outline: outline) == previous)
        #expect(SidebarDropResolver.gapPosition(destination: (1, 2), source: (1, 2), previous: nil, outline: outline) == .gap(section: .folders, index: 2))
        #expect(SidebarDropResolver.gapPosition(destination: (1, 3), source: (1, 2), previous: previous, outline: outline) == .gap(section: .folders, index: 3))
    }

    // MARK: - Spring loading

    @Test
    func onlyACollapsedFolderAFeedWouldDropIntoSpringsOpen() {
        #expect(SidebarDropResolver.springLoadFolder(at: .into(.folder("d4")), dragging: .feed("u1"), outline: outline) == "d4")
        #expect(SidebarDropResolver.springLoadFolder(at: .into(.folder("d1")), dragging: .feed("u1"), outline: outline) == nil)
        #expect(SidebarDropResolver.springLoadFolder(at: .into(.folder("d4")), dragging: .folder("d1"), outline: outline) == nil)
        #expect(SidebarDropResolver.springLoadFolder(at: .into(.tag("t2")), dragging: .feed("u1"), outline: outline) == nil)
        #expect(SidebarDropResolver.springLoadFolder(at: .gap(section: .folders, index: 8), dragging: .feed("u1"), outline: outline) == nil)
    }
}
