import KeryxShared

/// A direction a sidebar row can be moved in without dragging — what VoiceOver's "Move up" / "Move
/// down" actions do.
enum SidebarMoveDirection: Equatable, Sendable {
    case up
    case down

    var delta: Int { self == .up ? -1 : 1 }
}

/// Which directions a row can move in within its own reorder scope — a row that is already first or
/// last there exposes no action for that direction at all, rather than one that does nothing.
struct SidebarMoveAvailability: Equatable, Sendable {
    var canMoveUp = false
    var canMoveDown = false

    static let none = SidebarMoveAvailability()
}

/// The mutation a move resolves to — the same one a completed drop applies.
enum SidebarReorderMove: Equatable {
    /// `HomeViewModel.moveFeed`: the feed lands in `folderId` (`nil`: no folder) before
    /// `insertBeforeId` (`nil`: at the end).
    case feed(feedId: String, folderId: String?, insertBeforeId: String?)
    /// `HomeViewModel.reorderFolders`.
    case folder(folderId: String, insertBeforeId: String?)
}

/// Resolves the move-up / move-down actions over the sidebar's reorder scopes, the same ones
/// Compose's feed list offers them in (`FeedListPane.kt`): a feed moves among the feeds of its own
/// folder group (or the unfoldered ones), a folder among the folders. A feed's copy under a tag is not
/// part of any scope (it is never reordered), and neither are tags. The landing position is the
/// shared `reorderTargetWithinScope`. Kept free of UIKit so the standalone `KeryxTests` bundle can
/// compile it directly.
enum SidebarReorderTargets {
    private struct Scope {
        let ids: [String]
        let item: (String) -> SidebarItemID
        let move: (_ id: String, _ insertBeforeId: String?) -> SidebarReorderMove
    }

    private static func scopes(_ model: SidebarModel) -> [Scope] {
        func feedScope(folderId: String?, feeds: [Feeds]) -> Scope {
            Scope(
                ids: feeds.map(\.id),
                item: { SidebarItemID.feed($0) },
                move: { .feed(feedId: $0, folderId: folderId, insertBeforeId: $1) }
            )
        }
        var scopes = [
            Scope(
                ids: model.sortedFolders.map(\.id),
                item: { SidebarItemID.folder($0) },
                move: { .folder(folderId: $0, insertBeforeId: $1) }
            ),
        ]
        for folder in model.sortedFolders {
            scopes.append(feedScope(folderId: folder.id, feeds: model.feeds(inFolder: folder.id)))
        }
        scopes.append(feedScope(folderId: nil, feeds: model.unassignedFeeds))
        return scopes
    }

    /// The move of `item` one place in `direction`, or `nil` when it has none.
    static func move(for item: SidebarItemID, direction: SidebarMoveDirection, model: SidebarModel) -> SidebarReorderMove? {
        for scope in scopes(model) {
            guard let index = scope.ids.firstIndex(where: { scope.item($0) == item }) else { continue }
            let target = FeedListModelKt.reorderTargetWithinScope(
                orderedIds: scope.ids, index: Int32(index), delta: Int32(direction.delta)
            )
            return target.map { scope.move(scope.ids[index], $0.insertBeforeId) }
        }
        return nil
    }

    /// Every reorderable row's available directions — computed in one pass, since it is rebuilt with
    /// the row contents on every change.
    static func availability(model: SidebarModel) -> [SidebarItemID: SidebarMoveAvailability] {
        var result: [SidebarItemID: SidebarMoveAvailability] = [:]
        for scope in scopes(model) {
            for (index, id) in scope.ids.enumerated() {
                result[scope.item(id)] = SidebarMoveAvailability(
                    canMoveUp: index > 0, canMoveDown: index < scope.ids.count - 1
                )
            }
        }
        return result
    }
}
