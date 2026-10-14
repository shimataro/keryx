/// Which sidebar rows show the refreshing indicator, as plain values: the feeds that have been
/// refreshing long enough to be marked, and the collapsed folders holding one (`HomeViewModel`'s
/// `refreshingFeedIds` / `refreshingFolderIds`, which decide both for every UI). Like
/// `SidebarUnreadCounts`, the iOS collection view observes it itself, so a feed starting or finishing
/// reconfigures only the rows whose indicator changed.
struct SidebarRefreshing: Equatable, Sendable {
    var feeds: Set<String> = []
    var folders: Set<String> = []

    /// Whether the row counted by `source` shows the indicator — a feed (in its folder group or
    /// under a tag) or a collapsed folder; never a tag or a smart row.
    func isRefreshing(_ source: SidebarUnreadSource) -> Bool {
        switch source {
        case .feed(let id): feeds.contains(id)
        case .folder(let id): folders.contains(id)
        case .total, .starred, .tag: false
        }
    }

    func isRefreshing(_ item: SidebarItemID) -> Bool {
        item.unreadSource.map(isRefreshing) ?? false
    }

    /// The items among `items` whose indicator differs between `old` and `new`, in `items`' order.
    static func changedItems(
        _ items: some Sequence<SidebarItemID>,
        from old: SidebarRefreshing,
        to new: SidebarRefreshing
    ) -> [SidebarItemID] {
        guard old != new else { return [] }
        return items.filter { old.isRefreshing($0) != new.isRefreshing($0) }
    }
}
