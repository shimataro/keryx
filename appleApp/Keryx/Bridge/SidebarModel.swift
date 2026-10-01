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
    /// `orderedRows` by their keys — how a key the native source list reports resolves back to its
    /// row without re-deriving every row's key.
    let orderedRowsByKey: [String: FeedListRowSelection]
    /// What each feed, folder and tag row shows apart from its unread count and highlight, by id —
    /// shared by every rendered copy of the same feed (see `SidebarRowStaticContent`).
    let feedContents: [String: SidebarRowStaticContent]
    let folderContents: [String: SidebarRowStaticContent]
    let tagContents: [String: SidebarRowStaticContent]
    /// Every rendered folder, tag and feed row's selection and key, built here once per structure
    /// change rather than several times per row on every source-list evaluation — see
    /// `SidebarRowSelection`. All / Starred and the headers are left out.
    let rowSelections: [SidebarItemID: SidebarRowSelection]

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
        let keys = orderedRows.map(feedListRowSelectionKey)
        orderedRowKeys = keys
        orderedRowsByKey = Dictionary(zip(keys, orderedRows), uniquingKeysWith: { first, _ in first })

        var feedContents: [String: SidebarRowStaticContent] = [:]
        for feed in sortedFeeds where feedContents[feed.id] == nil {
            feedContents[feed.id] = SidebarRowStaticContent(feed: feed)
        }
        self.feedContents = feedContents
        folderContents = Dictionary(
            sortedFolders.map { ($0.id, SidebarRowStaticContent(title: $0.name, icon: .symbol("folder"))) },
            uniquingKeysWith: { first, _ in first }
        )
        tagContents = Dictionary(
            sortedTags.map { ($0.id, SidebarRowStaticContent(title: $0.name, icon: .tagColor(hex: $0.color))) },
            uniquingKeysWith: { first, _ in first }
        )

        var selections: [SidebarItemID: SidebarRowSelection] = [:]
        func add(_ item: SidebarItemID) {
            guard selections[item] == nil, let selection = SidebarRowSelection(item) else { return }
            selections[item] = selection
        }
        for folder in sortedFolders {
            add(.folder(folder.id))
            byFolder[folder.id]?.forEach { add(.feed($0.id)) }
        }
        unassigned.forEach { add(.feed($0.id)) }
        for tag in sortedTags {
            add(.tag(tag.id))
            byTag[tag.id]?.forEach { add(.feedInTag(feedId: $0.id, tagId: tag.id)) }
        }
        rowSelections = selections
    }

    static var empty: SidebarModel {
        SidebarModel(feeds: [], folders: [], tags: [], feedTagMap: [:], collapsedFolderIds: [], expandedTagIds: [])
    }

    func feeds(inFolder folderId: String) -> [Feeds] { feedsByFolderId[folderId] ?? [] }

    func feeds(taggedWith tagId: String) -> [Feeds] { feedsByTagId[tagId] ?? [] }

    /// The selection and key of the rendered row `item` — built on the spot for an item this model
    /// has none for (All / Starred), which only happens for rows whose selection is cheap anyway.
    func rowSelection(_ item: SidebarItemID) -> SidebarRowSelection? {
        rowSelections[item] ?? SidebarRowSelection(item)
    }
}

/// A rendered sidebar row's shared selection value and its `feedListRowSelectionKey`, built once
/// per structure change: the source list needs both for every row (its tag, selection highlight,
/// context menu and drag guard), and building them per row per evaluation meant a Kotlin object and
/// a sealed-type switch each time.
struct SidebarRowSelection {
    let instance: FeedListRowSelection
    let key: String

    init(_ instance: FeedListRowSelection) {
        self.instance = instance
        key = feedListRowSelectionKey(instance)
    }

    /// `nil` for a header, which is never selected.
    init?(_ item: SidebarItemID) {
        guard let instance = item.rowSelection else { return nil }
        self.init(instance)
    }
}
