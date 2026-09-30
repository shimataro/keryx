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

    /// The single-pass `allItems` returns what the previous implementation — each top-level node
    /// followed by its searched-for descendants — did, for every disclosure state.
    @Test(arguments: [(true, true), (false, true), (true, false), (false, false)])
    func allItemsMatchesThePerNodeDescendantWalk(foldersExpanded: Bool, tagsExpanded: Bool) {
        let outline = F.outline(foldersExpanded: foldersExpanded, tagsExpanded: tagsExpanded)
        #expect(outline.allItems == legacyAllItems(outline))
        let empty = F.outline(feeds: [], folders: [], tags: [], feedTagMap: [:])
        #expect(empty.allItems == legacyAllItems(empty))
    }

    @Test
    func allItemsIsDepthFirstInDisplayOrder() {
        #expect(F.outline().allItems == [
            .all, .starred,
            .sectionHeader(.folders), .folder("d1"), .feed("a"), .feed("b"), .folder("d2"), .feed("c"),
            .folder("d3"), .folder("d4"), .feed("e"),
            .noFolderHeader, .feed("u1"), .feed("u2"),
            .sectionHeader(.tags), .tag("t1"), .feedInTag(feedId: "a", tagId: "t1"), .tag("t2"),
        ])
    }

    /// The previous `allItems`, kept here as the reference the new one is checked against.
    private func legacyAllItems(_ outline: SidebarOutline) -> [SidebarItemID] {
        func find(_ nodes: [SidebarOutline.Node], _ item: SidebarItemID) -> SidebarOutline.Node? {
            for node in nodes {
                if node.id == item { return node }
                if let found = find(node.children, item) { return found }
            }
            return nil
        }
        func flatten(_ node: SidebarOutline.Node) -> [SidebarItemID] {
            node.children.flatMap { [$0.id] + flatten($0) }
        }
        return outline.sections.flatMap { section in
            section.nodes.flatMap { node in [node.id] + (find(section.nodes, node.id).map(flatten) ?? []) }
        }
    }

    @Test
    func allItemsIncludesHiddenOnes() {
        let items = F.outline(foldersExpanded: false).allItems
        #expect(items.contains(.feed("e")))
        #expect(items.contains(.feed("a")))
        #expect(items.count == Set(items).count)
    }
}
