import KeryxShared
import Testing

/// Covers `SidebarOutline`, the iOS sidebar's section trees and disclosure state.
@Suite
struct SidebarOutlineTests {
    private typealias F = SidebarFixtures

    @Test
    func emptyFolderAndTagSectionsAreLeftOutButNoFolderStays() {
        let outline = F.outline(feeds: [], folders: [], tags: [], feedTagMap: [:])
        #expect(outline.sections.map(\.section) == [.smart, .noFolder])
        #expect(outline.section(.smart)?.visibleItems == [.all, .starred])
        #expect(outline.section(.noFolder)?.visibleItems == [.noFolderHeader])
    }

    @Test
    func sectionsFollowTheSortOrders() {
        let outline = F.outline(
            feeds: F.feeds.reversed(),
            folders: F.folders.reversed(),
            tags: F.tags.reversed()
        )
        #expect(outline.sections.map(\.section) == [.smart, .folders, .noFolder, .tags])
        #expect(outline.section(.folders)?.visibleItems == [
            .sectionHeader(.folders), .folder("d1"), .feed("a"), .feed("b"), .folder("d2"), .feed("c"),
            .folder("d3"), .folder("d4"),
        ])
        #expect(outline.section(.noFolder)?.visibleItems == [.noFolderHeader, .feed("u1"), .feed("u2")])
        #expect(outline.section(.tags)?.visibleItems == [
            .sectionHeader(.tags), .tag("t1"), .feedInTag(feedId: "a", tagId: "t1"), .tag("t2"),
        ])
    }

    @Test
    func aFeedInAMissingFolderIsUnfoldered() {
        let outline = F.outline(feeds: [F.feed("x", sortOrder: 1, folderId: "gone")], folders: [F.folder("d1", sortOrder: 1)])
        #expect(outline.section(.noFolder)?.visibleItems == [.noFolderHeader, .feed("x")])
        #expect(outline.section(.folders)?.visibleItems == [.sectionHeader(.folders), .folder("d1")])
    }

    @Test
    func collapsedItemsKeepTheirChildrenHidden() {
        let outline = F.outline()
        let folders = outline.section(.folders)
        #expect(folders?.expanded == [.sectionHeader(.folders), .folder("d1"), .folder("d2"), .folder("d3")])
        #expect(folders?.descendants(of: .folder("d4")) == [.feed("e")])
        #expect(folders?.visibleItems.contains(.feed("e")) == false)
        #expect(outline.section(.tags)?.expanded == [.sectionHeader(.tags), .tag("t1")])
    }

    @Test
    func collapsedSectionHeadersHideTheirWholeSection() {
        let outline = F.outline(foldersExpanded: false, tagsExpanded: false)
        #expect(outline.section(.folders)?.visibleItems == [.sectionHeader(.folders)])
        #expect(outline.section(.tags)?.visibleItems == [.sectionHeader(.tags)])
        // The per-folder / per-tag state is kept for when the header is expanded again.
        #expect(outline.section(.folders)?.expanded.contains(.folder("d1")) == true)
        #expect(outline.section(.tags)?.expanded.contains(.tag("t1")) == true)
    }

    @Test
    func descendantsOfTheSectionHeaderAreTheWholeTree() {
        let folders = F.outline().section(.folders)
        #expect(folders?.descendants(of: .sectionHeader(.folders)) == [
            .folder("d1"), .feed("a"), .feed("b"), .folder("d2"), .feed("c"), .folder("d3"), .folder("d4"), .feed("e"),
        ])
        #expect(folders?.descendants(of: .feed("a")) == [])
        #expect(folders?.descendants(of: .tag("t1")) == [])
    }

    @Test
    func visibleRowsAreTheSharedRowOrder() {
        let model = F.model()
        let outline = F.outline()
        #expect(outline.visibleRows.compactMap(\.selectionKey) == model.orderedRowKeys)
    }

    @Test
    func allItemsIncludesHiddenOnes() {
        let items = F.outline(foldersExpanded: false).allItems
        #expect(items.contains(.feed("e")))
        #expect(items.contains(.feed("a")))
        #expect(items.count == Set(items).count)
    }
}
