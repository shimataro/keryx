import KeryxShared
import Testing

/// Covers `SidebarModel`, the sidebar structure `HomeObservable` derives once per change: sort
/// orders, the folder/tag groupings, and the rendered row order.
@Suite
struct SidebarModelTests {
    private func feed(_ id: String, sortOrder: Int64, folderId: String? = nil) -> Feeds {
        Feeds(
            id: id, url: "https://example.com/\(id)", site_url: nil, title: id, description: nil,
            favicon_url: nil, etag: nil, last_modified: nil, error_count: 0, last_error: nil,
            custom_title: nil, folder_id: folderId, deleted_at: nil, updated_at: 0, created_at: 0,
            sort_order: sortOrder, folder_updated_at: nil, sort_order_updated_at: nil,
            custom_title_updated_at: nil, deleted_updated_at: nil
        )
    }

    private func folder(_ id: String, sortOrder: Int64) -> Folders {
        Folders(id: id, name: id, sort_order: sortOrder, deleted_at: nil, updated_at: 0, created_at: 0)
    }

    private func tag(_ id: String, sortOrder: Int64) -> Tags {
        Tags(id: id, name: id, color: nil, sort_order: sortOrder, deleted_at: nil, updated_at: 0, created_at: 0)
    }

    private func model(
        feeds: [Feeds],
        folders: [Folders] = [],
        tags: [Tags] = [],
        feedTagMap: [String: Set<String>] = [:],
        collapsedFolderIds: Set<String> = [],
        expandedTagIds: Set<String> = []
    ) -> SidebarModel {
        SidebarModel(
            feeds: feeds, folders: folders, tags: tags, feedTagMap: feedTagMap,
            collapsedFolderIds: collapsedFolderIds, expandedTagIds: expandedTagIds
        )
    }

    @Test
    func sortsFoldersFeedsAndTagsBySortOrder() {
        let m = model(
            feeds: [feed("b", sortOrder: 2), feed("a", sortOrder: 1)],
            folders: [folder("y", sortOrder: 2), folder("x", sortOrder: 1)],
            tags: [tag("t2", sortOrder: 2), tag("t1", sortOrder: 1)]
        )
        #expect(m.sortedFeeds.map(\.id) == ["a", "b"])
        #expect(m.sortedFolders.map(\.id) == ["x", "y"])
        #expect(m.sortedTags.map(\.id) == ["t1", "t2"])
    }

    @Test
    func groupsFeedsByFolderInSortOrder() {
        let m = model(
            feeds: [feed("b", sortOrder: 2, folderId: "x"), feed("a", sortOrder: 1, folderId: "x"), feed("c", sortOrder: 3)],
            folders: [folder("x", sortOrder: 1)]
        )
        #expect(m.feeds(inFolder: "x").map(\.id) == ["a", "b"])
        #expect(m.unassignedFeeds.map(\.id) == ["c"])
        #expect(m.feeds(inFolder: "missing").isEmpty)
    }

    @Test
    func feedInAMissingFolderIsUnassigned() {
        let m = model(feeds: [feed("a", sortOrder: 1, folderId: "gone")], folders: [folder("x", sortOrder: 1)])
        #expect(m.unassignedFeeds.map(\.id) == ["a"])
        #expect(m.feeds(inFolder: "x").isEmpty)
    }

    @Test
    func mapsFeedsToTheirTags() {
        let m = model(
            feeds: [feed("b", sortOrder: 2), feed("a", sortOrder: 1)],
            tags: [tag("t1", sortOrder: 1), tag("t2", sortOrder: 2)],
            feedTagMap: ["a": ["t1"], "b": ["t1", "t2"]]
        )
        #expect(m.feeds(taggedWith: "t1").map(\.id) == ["a", "b"])
        #expect(m.feeds(taggedWith: "t2").map(\.id) == ["b"])
        #expect(m.feeds(taggedWith: "none").isEmpty)
    }

    @Test
    func orderedRowsFollowCollapsedFoldersAndExpandedTags() {
        let feeds = [feed("a", sortOrder: 1, folderId: "x"), feed("b", sortOrder: 2)]
        let folders = [folder("x", sortOrder: 1)]
        let tags = [tag("t", sortOrder: 1)]
        let feedTagMap: [String: Set<String>] = ["b": ["t"]]

        let expanded = model(feeds: feeds, folders: folders, tags: tags, feedTagMap: feedTagMap, expandedTagIds: ["t"])
        let expectedExpanded: [FeedListRowSelection] = [
            FeedListRowSelectionAll(), FeedListRowSelectionStarred(),
            FeedListRowSelectionFolder(folderId: "x"), FeedListRowSelectionFeedInFolderGroup(feedId: "a"),
            FeedListRowSelectionFeedInFolderGroup(feedId: "b"),
            FeedListRowSelectionTag(tagId: "t"), FeedListRowSelectionFeedInTag(feedId: "b", tagId: "t"),
        ]
        #expect(expanded.orderedRowKeys == expectedExpanded.map(feedListRowSelectionKey))

        let collapsed = model(feeds: feeds, folders: folders, tags: tags, feedTagMap: feedTagMap, collapsedFolderIds: ["x"])
        let expectedCollapsed: [FeedListRowSelection] = [
            FeedListRowSelectionAll(), FeedListRowSelectionStarred(),
            FeedListRowSelectionFolder(folderId: "x"),
            FeedListRowSelectionFeedInFolderGroup(feedId: "b"),
            FeedListRowSelectionTag(tagId: "t"),
        ]
        #expect(collapsed.orderedRowKeys == expectedCollapsed.map(feedListRowSelectionKey))
        #expect(collapsed.orderedRows.count == collapsed.orderedRowKeys.count)
    }
}
