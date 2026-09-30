import KeryxShared

/// Every `Feeds` field except the three a refresh rewrites without anything on screen reading them
/// (`etag`, `last_modified`, `updated_at`). `HomeObservable` rebuilds the sidebar, the selection
/// target, `feedsById` and the article rows' feed info only when the list of these changes, so a
/// refresh re-emitting `feeds` once per fetched feed no longer rebuilds all of them each time.
///
/// Those derived values hold the `Feeds` objects themselves, and keep the old ones while the key is
/// unchanged — so a field is left out here only if no view reads it. Anything that needs the fresh
/// conditional-request fields resolves the current row through `HomeObservable.currentFeed(id:)`.
struct FeedStructureKey: Equatable {
    let id: String
    let url: String
    let siteUrl: String?
    let title: String
    let description: String?
    let faviconUrl: String?
    let errorCount: Int64
    let lastError: String?
    let customTitle: String?
    let folderId: String?
    let deletedAt: Int64?
    let createdAt: Int64
    let sortOrder: Int64
    let folderUpdatedAt: Int64?
    let sortOrderUpdatedAt: Int64?
    let customTitleUpdatedAt: Int64?
    let deletedUpdatedAt: Int64?

    init(_ feed: Feeds) {
        id = feed.id
        url = feed.url
        siteUrl = feed.site_url
        title = feed.title
        description = feed.description_
        faviconUrl = feed.favicon_url
        errorCount = feed.error_count
        lastError = feed.last_error
        customTitle = feed.custom_title
        folderId = feed.folder_id
        deletedAt = feed.deleted_at?.int64Value
        createdAt = feed.created_at
        sortOrder = feed.sort_order
        folderUpdatedAt = feed.folder_updated_at?.int64Value
        sortOrderUpdatedAt = feed.sort_order_updated_at?.int64Value
        customTitleUpdatedAt = feed.custom_title_updated_at?.int64Value
        deletedUpdatedAt = feed.deleted_updated_at?.int64Value
    }

    /// The keys of `feeds`, in order — order matters, since it is the order the sidebar is built from.
    static func of(_ feeds: [Feeds]) -> [FeedStructureKey] { feeds.map(FeedStructureKey.init) }
}

/// The last accepted `feeds` emission's structure, deciding whether the next one changes it.
struct FeedStructureTracker {
    private(set) var keys: [FeedStructureKey] = []

    /// Records `feeds`' structure and returns whether it differs from the last one recorded — the
    /// signal for `HomeObservable` to rebuild what it derives from `feeds`. Starts as the empty
    /// list, matching `HomeObservable`'s own initial (empty) derived state.
    mutating func accept(_ feeds: [Feeds]) -> Bool {
        let next = FeedStructureKey.of(feeds)
        guard next != keys else { return false }
        keys = next
        return true
    }
}
