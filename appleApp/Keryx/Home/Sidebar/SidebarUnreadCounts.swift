/// Which of `HomeObservable`'s unread counts a sidebar row shows.
enum SidebarUnreadSource: Hashable, Sendable {
    case total
    case starred
    case feed(String)
    case folder(String)
    case tag(String)
}

extension SidebarItemID {
    /// The unread count this item's row shows — `nil` for a header, which shows none. A feed's copy
    /// under a tag shows the feed's own count, the same as its copy in its folder group.
    var unreadSource: SidebarUnreadSource? {
        switch self {
        case .all: return .total
        case .starred: return .starred
        case .sectionHeader, .noFolderHeader: return nil
        case .folder(let id): return .folder(id)
        case .tag(let id): return .tag(id)
        case .feed(let id), .feedInTag(let id, _): return .feed(id)
        }
    }
}

/// A snapshot of `HomeObservable`'s five unread counts, as plain values. The iOS sidebar's
/// collection view observes the counts itself rather than through `SidebarRenderState`, so a count
/// ticking (an article read, a feed fetched during a refresh) reconfigures only the cells whose count
/// changed — never re-evaluating `FeedListView` or rebuilding the outline and every row's contents.
struct SidebarUnreadCounts: Equatable, Sendable {
    var byFeed: [String: Int64] = [:]
    var byFolder: [String: Int64] = [:]
    var byTag: [String: Int64] = [:]
    var total: Int64 = 0
    var starred: Int64 = 0

    func count(for source: SidebarUnreadSource) -> Int64 {
        switch source {
        case .total: total
        case .starred: starred
        case .feed(let id): byFeed[id] ?? 0
        case .folder(let id): byFolder[id] ?? 0
        case .tag(let id): byTag[id] ?? 0
        }
    }

    /// The count `item`'s row shows — 0 for a header.
    func count(for item: SidebarItemID) -> Int64 {
        item.unreadSource.map(count(for:)) ?? 0
    }

    /// The items among `items` whose shown count differs between `old` and `new` — the cells to
    /// reconfigure, in `items`' order.
    static func changedItems(
        _ items: some Sequence<SidebarItemID>,
        from old: SidebarUnreadCounts,
        to new: SidebarUnreadCounts
    ) -> [SidebarItemID] {
        guard old != new else { return [] }
        return items.filter { item in
            guard let source = item.unreadSource else { return false }
            return old.count(for: source) != new.count(for: source)
        }
    }
}
