import KeryxShared

/// What a sidebar row shows in its icon slot.
enum SidebarRowIcon: Equatable, Sendable {
    /// All / Starred / a folder.
    case symbol(String)
    /// A feed: its favicon, or a letter avatar while loading or without one.
    case favicon(url: String?)
    /// A tag: its color dot.
    case tagColor(hex: String?)
}

/// Everything one iOS sidebar row displays, as plain values — what its hosted cell is configured
/// from. Comparing the previous and the current contents (`changedItems`) tells the collection view
/// which cells to reconfigure in place, so an unread count ticking during a refresh never rebuilds
/// the list.
struct SidebarRowContent: Equatable, Sendable {
    let title: String
    /// `nil` for a section header.
    let icon: SidebarRowIcon?
    let unreadCount: Int64
    let isErroring: Bool
    let isGone: Bool
    /// `.none` or `.echo`; the drop highlight is the cell's own drop state, not content.
    let highlight: SidebarRowHighlight
    /// Whether the row is showing its in-place name editor.
    let isRenaming: Bool

    /// The contents of every item in `outline`, hidden ones included.
    ///
    /// - Parameters:
    ///   - selectedRow: The shared selection, for the echo highlight.
    ///   - filter: The article list's current filter, for the echo highlight.
    ///   - renamingRowKey: `SidebarDialogState.renamingRowKey`.
    static func build(
        outline: SidebarOutline,
        model: SidebarModel,
        unreadByFeed: [String: Int64],
        unreadByFolder: [String: Int64],
        unreadByTag: [String: Int64],
        totalUnread: Int64,
        starredUnreadCount: Int64,
        selectedRow: FeedListRowSelection,
        filter: ArticleFilter,
        renamingRowKey: String?
    ) -> [SidebarItemID: SidebarRowContent] {
        let feeds = Dictionary(model.sortedFeeds.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let folders = Dictionary(model.sortedFolders.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })
        let tags = Dictionary(model.sortedTags.map { ($0.id, $0) }, uniquingKeysWith: { first, _ in first })

        func row(_ item: SidebarItemID, title: String, icon: SidebarRowIcon, unread: Int64, feed: Feeds? = nil) -> SidebarRowContent {
            let isGone = feed?.last_error == ConstantsKt.FEED_ERROR_REASON_GONE
            return SidebarRowContent(
                title: title,
                icon: icon,
                unreadCount: unread,
                isErroring: (feed?.error_count ?? 0) > 0 || isGone,
                isGone: isGone,
                highlight: item.rowSelection.map { highlight(for: $0, selectedRow: selectedRow, filter: filter) } ?? .none,
                isRenaming: renamingRowKey != nil && item.selectionKey == renamingRowKey
            )
        }

        func header(_ key: String) -> SidebarRowContent {
            SidebarRowContent(
                title: L(key), icon: nil, unreadCount: 0, isErroring: false, isGone: false,
                highlight: .none, isRenaming: false
            )
        }

        var contents: [SidebarItemID: SidebarRowContent] = [:]
        for item in outline.allItems {
            switch item {
            case .all:
                contents[item] = row(item, title: L("home_all_feeds"), icon: .symbol("tray.full"), unread: totalUnread)
            case .starred:
                contents[item] = row(item, title: L("home_starred"), icon: .symbol("star"), unread: starredUnreadCount)
            case .sectionHeader(let section):
                contents[item] = header(section == .tags ? "home_tags" : "home_folders")
            case .noFolderHeader:
                contents[item] = header("home_no_folder")
            case .folder(let id):
                guard let folder = folders[id] else { continue }
                contents[item] = row(item, title: folder.name, icon: .symbol("folder"), unread: unreadByFolder[id] ?? 0)
            case .tag(let id):
                guard let tag = tags[id] else { continue }
                contents[item] = row(item, title: tag.name, icon: .tagColor(hex: tag.color), unread: unreadByTag[id] ?? 0)
            case .feed(let id), .feedInTag(let id, _):
                guard let feed = feeds[id] else { continue }
                contents[item] = row(
                    item, title: feed.displayTitle(), icon: .favicon(url: feed.favicon_url),
                    unread: unreadByFeed[id] ?? 0, feed: feed
                )
            }
        }
        return contents
    }

    /// `.echo` — the faint SECONDARY tone of the Compose app's `RowSelectionTone` (`FeedListPane.kt`'s
    /// `toneFor`) — for every *other* rendered copy of the selected filter: a feed shown under both
    /// its folder group and an expanded tag. The same rule as `FeedListView.highlight(for:)`.
    static func highlight(
        for row: FeedListRowSelection,
        selectedRow: FeedListRowSelection,
        filter: ArticleFilter
    ) -> SidebarRowHighlight {
        guard !feedListRowSelectionsEqual(row, selectedRow), articleFiltersEqual(row.filter, filter) else { return .none }
        return .echo
    }

    /// The items present in both `old` and `new` whose contents differ — the cells to reconfigure.
    /// An item only in one of them is a structural change, which the section snapshots handle.
    static func changedItems(
        from old: [SidebarItemID: SidebarRowContent],
        to new: [SidebarItemID: SidebarRowContent]
    ) -> Set<SidebarItemID> {
        Set(new.compactMap { item, content in
            guard let previous = old[item], previous != content else { return nil }
            return item
        })
    }
}
