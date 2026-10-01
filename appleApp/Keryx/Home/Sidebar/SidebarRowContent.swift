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

/// The part of a sidebar row's display that only changes with the sidebar's structure — its title,
/// icon and error state — derived once per `SidebarModel` rebuild. Both sidebars lay the per-change
/// parts (unread count, highlight, rename state) over it, so an unread count ticking during a
/// refresh no longer re-reads the Kotlin rows.
struct SidebarRowStaticContent: Equatable, Sendable {
    let title: String
    let icon: SidebarRowIcon
    var isErroring = false
    var isGone = false

    static var all: SidebarRowStaticContent { SidebarRowStaticContent(title: L("home_all_feeds"), icon: .symbol("tray.full")) }
    static var starred: SidebarRowStaticContent { SidebarRowStaticContent(title: L("home_starred"), icon: .symbol("star")) }
}

extension SidebarRowStaticContent {
    /// A feed row: its display title and favicon, and whether its last fetch failed or it is gone
    /// (410) — the gone marker counts as erroring, matching Compose's own `FeedErrorIndicator`.
    init(feed: Feeds) {
        let isGone = feed.last_error == ConstantsKt.FEED_ERROR_REASON_GONE
        self.init(
            title: feed.displayTitle(),
            icon: .favicon(url: feed.favicon_url),
            isErroring: feed.error_count > 0 || isGone,
            isGone: isGone
        )
    }
}

/// Everything one iOS sidebar row displays apart from its unread count, as plain values — what its
/// hosted cell is configured from. Comparing the previous and the current contents (`changedItems`)
/// tells the collection view which cells to reconfigure in place. The unread count is deliberately
/// not part of it: the collection view observes the counts itself (`SidebarUnreadCounts`), so a count
/// ticking during a refresh or on every article read never rebuilds these contents.
struct SidebarRowContent: Equatable, Sendable {
    let title: String
    /// `nil` for a section header.
    let icon: SidebarRowIcon?
    let isErroring: Bool
    let isGone: Bool
    /// `.none` or `.echo`; the drop highlight is the cell's own drop state, not content.
    let highlight: SidebarRowHighlight
    /// Whether the row is showing its in-place name editor.
    let isRenaming: Bool

    /// The contents of every item in `outline`, hidden ones included.
    ///
    /// - Parameters:
    ///   - selectionDisplayed: Whether the list shows the selection at all — not while the collapsed
    ///     sidebar is the topmost column (`CompactSidebarSelection.displayedKey`). Without a selected
    ///     row on screen, echoing its other copies would single out a row nobody picked.
    ///   - selectedRow: The shared selection, for the echo highlight.
    ///   - filter: The article list's current filter, for the echo highlight.
    ///   - renamingRowKey: `SidebarDialogState.renamingRowKey`.
    static func build(
        outline: SidebarOutline,
        model: SidebarModel,
        selectionDisplayed: Bool,
        selectedRow: FeedListRowSelection,
        filter: ArticleFilter,
        renamingRowKey: String?
    ) -> [SidebarItemID: SidebarRowContent] {
        func row(_ item: SidebarItemID, _ base: SidebarRowStaticContent) -> SidebarRowContent {
            SidebarRowContent(
                title: base.title,
                icon: base.icon,
                isErroring: base.isErroring,
                isGone: base.isGone,
                highlight: selectionDisplayed
                    ? model.rowSelection(item).map { highlight(for: $0.instance, selectedRow: selectedRow, filter: filter) } ?? .none
                    : .none,
                isRenaming: renamingRowKey != nil && item.selectionKey == renamingRowKey
            )
        }

        func header(_ key: String) -> SidebarRowContent {
            SidebarRowContent(
                title: L(key), icon: nil, isErroring: false, isGone: false,
                highlight: .none, isRenaming: false
            )
        }

        let items = outline.allItems
        var contents: [SidebarItemID: SidebarRowContent] = [:]
        contents.reserveCapacity(items.count)
        for item in items {
            switch item {
            case .all:
                contents[item] = row(item, .all)
            case .starred:
                contents[item] = row(item, .starred)
            case .sectionHeader(let section):
                contents[item] = header(section == .tags ? "home_tags" : "home_folders")
            case .noFolderHeader:
                contents[item] = header("home_no_folder")
            case .folder(let id):
                guard let base = model.folderContents[id] else { continue }
                contents[item] = row(item, base)
            case .tag(let id):
                guard let base = model.tagContents[id] else { continue }
                contents[item] = row(item, base)
            case .feed(let id), .feedInTag(let id, _):
                guard let base = model.feedContents[id] else { continue }
                contents[item] = row(item, base)
            }
        }
        return contents
    }

    /// `.echo` — the role of the SECONDARY tone of the Compose app's `RowSelectionTone`
    /// (`FeedListPane.kt`'s `toneFor`) — for every *other* rendered copy of the selected filter: a feed shown under both
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
