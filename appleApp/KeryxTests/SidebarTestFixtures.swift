import KeryxShared

/// Real Kotlin `Feeds`/`Folders`/`Tags` rows for the iOS sidebar model tests.
enum SidebarFixtures {
    static func feed(
        _ id: String,
        sortOrder: Int64,
        folderId: String? = nil,
        customTitle: String? = nil,
        errorCount: Int64 = 0,
        lastError: String? = nil
    ) -> Feeds {
        Feeds(
            id: id, url: "https://example.com/\(id)", site_url: nil, title: id, description: nil,
            favicon_url: "https://example.com/\(id).ico", etag: nil, last_modified: nil,
            error_count: errorCount, last_error: lastError, custom_title: customTitle, folder_id: folderId,
            deleted_at: nil, updated_at: 0, created_at: 0, sort_order: sortOrder, folder_updated_at: nil,
            sort_order_updated_at: nil, custom_title_updated_at: nil, deleted_updated_at: nil
        )
    }

    static func folder(_ id: String, sortOrder: Int64) -> Folders {
        Folders(id: id, name: "name-\(id)", sort_order: sortOrder, deleted_at: nil, updated_at: 0, created_at: 0)
    }

    static func tag(_ id: String, sortOrder: Int64, color: String? = nil) -> Tags {
        Tags(id: id, name: "name-\(id)", color: color, sort_order: sortOrder, deleted_at: nil, updated_at: 0, created_at: 0)
    }

    /// Folders d1 (feeds a, b), d2 (feed c), d3 (empty) and d4 (feed e, collapsed); unfoldered
    /// feeds u1, u2; tag t1 (feed a, expanded) and t2 (no feeds).
    static var feeds: [Feeds] { [
        feed("a", sortOrder: 1, folderId: "d1"),
        feed("b", sortOrder: 2, folderId: "d1"),
        feed("c", sortOrder: 3, folderId: "d2"),
        feed("e", sortOrder: 4, folderId: "d4"),
        feed("u1", sortOrder: 5),
        feed("u2", sortOrder: 6),
    ] }
    static var folders: [Folders] {
        [folder("d1", sortOrder: 1), folder("d2", sortOrder: 2), folder("d3", sortOrder: 3), folder("d4", sortOrder: 4)]
    }
    static var tags: [Tags] { [tag("t1", sortOrder: 1), tag("t2", sortOrder: 2)] }
    static let feedTagMap: [String: Set<String>] = ["a": ["t1"]]
    static let collapsedFolderIds: Set<String> = ["d4"]
    static let expandedTagIds: Set<String> = ["t1"]

    static func model(
        feeds: [Feeds] = feeds,
        folders: [Folders] = folders,
        tags: [Tags] = tags,
        feedTagMap: [String: Set<String>] = feedTagMap,
        collapsedFolderIds: Set<String> = collapsedFolderIds,
        expandedTagIds: Set<String> = expandedTagIds
    ) -> SidebarModel {
        SidebarModel(
            feeds: feeds, folders: folders, tags: tags, feedTagMap: feedTagMap,
            collapsedFolderIds: collapsedFolderIds, expandedTagIds: expandedTagIds
        )
    }

    static func outline(
        feeds: [Feeds] = feeds,
        folders: [Folders] = folders,
        tags: [Tags] = tags,
        feedTagMap: [String: Set<String>] = feedTagMap,
        collapsedFolderIds: Set<String> = collapsedFolderIds,
        expandedTagIds: Set<String> = expandedTagIds,
        foldersExpanded: Bool = true,
        tagsExpanded: Bool = true
    ) -> SidebarOutline {
        SidebarOutline(
            model: model(
                feeds: feeds, folders: folders, tags: tags, feedTagMap: feedTagMap,
                collapsedFolderIds: collapsedFolderIds, expandedTagIds: expandedTagIds
            ),
            collapsedFolderIds: collapsedFolderIds,
            expandedTagIds: expandedTagIds,
            foldersExpanded: foldersExpanded,
            tagsExpanded: tagsExpanded
        )
    }
}
