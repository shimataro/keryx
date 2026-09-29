import KeryxShared

/// Everything the sidebar derives from the feed list's structure — sort orders, the folder and tag
/// groupings, the drop index and the rendered row order — computed once per change of that
/// structure by `HomeObservable` rather than on every sidebar body evaluation. The sidebar's body
/// also depends on the unread counts and refresh activity, which change on every article read and
/// every feed a refresh fetches, while none of this does.
struct SidebarModel {
    let sortedFolders: [Folders]
    let sortedFeeds: [Feeds]
    let sortedTags: [Tags]
    /// Feeds with no folder — including one whose `folder_id` points at a folder that no longer
    /// exists (deleted on another device, not yet synced here), via the shared `groupFeedsByFolder`,
    /// matching Compose's own fallback.
    let unassignedFeeds: [Feeds]
    let feedsByFolderId: [String: [Feeds]]
    let feedsByTagId: [String: [Feeds]]
    /// Mirrors Compose's own `derivedStateOf { buildFeedListDropIndex(feeds, folders) }`
    /// (`FeedListPane.kt`).
    let dropIndex: FeedListDropIndex
    /// The rendered rows in visual order (`buildOrderedFeedListRows`), and their selection keys.
    let orderedRows: [FeedListRowSelection]
    let orderedRowKeys: [String]

    init(
        feeds: [Feeds],
        folders: [Folders],
        tags: [Tags],
        feedTagMap: [String: Set<String>],
        collapsedFolderIds: Set<String>,
        expandedTagIds: Set<String>
    ) {
        sortedFolders = folders.sorted { $0.sort_order < $1.sort_order }
        sortedFeeds = feeds.sorted { $0.sort_order < $1.sort_order }
        sortedTags = tags.sorted { $0.sort_order < $1.sort_order }

        var unassigned: [Feeds] = []
        var byFolder: [String: [Feeds]] = [:]
        for group in FeedListModelKt.groupFeedsByFolder(feeds: sortedFeeds, folders: sortedFolders) {
            let groupFeeds = group.second as? [Feeds] ?? []
            if let folder = group.first {
                byFolder[folder.id] = groupFeeds
            } else {
                unassigned = groupFeeds
            }
        }
        unassignedFeeds = unassigned
        feedsByFolderId = byFolder

        var byTag: [String: [Feeds]] = [:]
        for tag in sortedTags {
            byTag[tag.id] = FeedListModelKt.feedsForTag(feeds: sortedFeeds, feedTagMap: feedTagMap, tagId: tag.id)
        }
        feedsByTagId = byTag

        dropIndex = FeedListDragKt.buildFeedListDropIndex(feeds: sortedFeeds, folders: sortedFolders)
        orderedRows = FeedListModelKt.buildOrderedFeedListRows(
            tags: tags,
            folders: folders,
            feeds: feeds,
            collapsedFolderIds: collapsedFolderIds,
            expandedTagIds: expandedTagIds,
            feedTagMap: feedTagMap
        )
        orderedRowKeys = orderedRows.map(feedListRowSelectionKey)
    }

    static var empty: SidebarModel {
        SidebarModel(feeds: [], folders: [], tags: [], feedTagMap: [:], collapsedFolderIds: [], expandedTagIds: [])
    }

    func feeds(inFolder folderId: String) -> [Feeds] { feedsByFolderId[folderId] ?? [] }

    func feeds(taggedWith tagId: String) -> [Feeds] { feedsByTagId[tagId] ?? [] }
}
