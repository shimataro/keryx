import KeryxShared

/// Where a drag over the iOS sidebar would drop, in the collection view's own terms.
enum SidebarDropPosition: Equatable, Sendable {
    /// Onto the row itself (UIKit's `.insertIntoDestinationIndexPath`): a feed into a folder or the
    /// unfoldered group, or a feed tagged.
    case into(SidebarItemID)
    /// Between rows (UIKit's `.insertAtDestinationIndexPath`): at `index` among `section`'s visible
    /// items, counted with the dragged row — and, for a folder, its feeds — left out.
    ///
    /// That is how UIKit numbers a move's destination: the dragged item's own slot closes up and
    /// the gap it opens is the index the item would end up at, the same as
    /// `UICollectionView.moveItem(at:to:)`'s. A dragged folder's children stay out of it too, since
    /// the outline collapses the folder while it is lifted.
    case gap(section: SidebarSection, index: Int)
}

/// Maps a drag over the iOS sidebar onto the shared drop rules: the position (`into` or `gap`)
/// becomes a `FeedListDropTarget` and half, which `resolveFeedListDropAction` then resolves exactly
/// as it does for Compose and the macOS list. A position with no action is refused.
enum SidebarDropResolver {
    /// The top part of a folder row that still counts as the gap above it, so a feed can be
    /// dropped just above a folder (at the end of the folder before it) as well as into it.
    static let folderGapBandFraction = 0.25

    /// The row a drag of `payload` over `item` drops onto, or `nil` when it drops between rows
    /// instead. A feed goes into a folder (below the folder row's top quarter), into the "No folder"
    /// header or onto a tag (anywhere on the row); a folder only ever goes between folders.
    ///
    /// - Parameter fraction: How far down the row the drag is, from 0 (top) to 1 (bottom).
    static func intoTarget(over item: SidebarItemID, fraction: Double, dragging payload: FeedListDragPayload) -> SidebarItemID? {
        guard payload.kind == .feed else { return nil }
        switch item {
        case .folder: return fraction >= folderGapBandFraction ? item : nil
        case .noFolderHeader, .tag: return item
        default: return nil
        }
    }

    /// The gap UIKit proposes, as a position — `destination` is its destination index path as
    /// (section index into `outline.sections`, item index). While the drag is over the gap it has
    /// already opened, UIKit reports the dragged item's own index path again (`source`), which says
    /// nothing about where the gap is, so `previous` is kept then rather than flickering the gap
    /// back. `nil` for a section index the outline doesn't have.
    static func gapPosition(
        destination: (section: Int, item: Int),
        source: (section: Int, item: Int)?,
        previous: SidebarDropPosition?,
        outline: SidebarOutline
    ) -> SidebarDropPosition? {
        if let source, source == destination, let previous { return previous }
        guard outline.sections.indices.contains(destination.section) else { return nil }
        return .gap(section: outline.sections[destination.section].section, index: destination.item)
    }

    /// The rows the gaps of `section` sit between while `dragged` is lifted: its visible items
    /// without `dragged` and its descendants.
    static func gapRows(in section: SidebarOutline.Section, dragging dragged: SidebarItemID) -> [SidebarItemID] {
        let removed = Set([dragged] + section.descendants(of: dragged))
        return section.visibleItems.filter { !removed.contains($0) }
    }

    /// The shared drop target and half `position` stands for, or `nil` where dropping `dragged`
    /// means nothing — anywhere in All/Starred or the tags, above a section's header, between the
    /// Folders header and the first folder for a feed, or just below a collapsed folder (whose
    /// feeds are hidden, so the gap would silently append to it).
    static func target(
        for position: SidebarDropPosition,
        dragging dragged: SidebarItemID,
        outline: SidebarOutline
    ) -> (target: FeedListDropTarget, half: FeedListRowHalf)? {
        guard let payload = dragged.dragPayload else { return nil }
        switch position {
        case .into(let item):
            guard intoTarget(over: item, fraction: 1, dragging: payload) != nil else { return nil }
            switch item {
            case .folder(let id): return (FeedListDropTargetFolderHeader(folderId: id), .top)
            case .noFolderHeader: return (FeedListDropTargetNoFolderHeader.shared, .top)
            case .tag(let id): return (FeedListDropTargetTagHeader(tagId: id), .top)
            default: return nil
            }
        case .gap(let sectionID, let index):
            guard let section = outline.section(sectionID), index > 0 else { return nil }
            let rows = gapRows(in: section, dragging: dragged)
            let previous = index <= rows.count ? rows[index - 1] : nil
            let next = index < rows.count ? rows[index] : nil
            switch payload.kind {
            case .feed: return feedGapTarget(in: section, previous: previous, next: next)
            case .folder: return folderGapTarget(in: section, previous: previous, next: next)
            }
        }
    }

    /// The action dropping `dragged` at `position` applies, through the shared
    /// `resolveFeedListDropAction` — `nil` when the drop is refused.
    static func action(
        for position: SidebarDropPosition,
        dragging dragged: SidebarItemID,
        outline: SidebarOutline,
        index: FeedListDropIndex
    ) -> FeedListDropAction? {
        guard let payload = dragged.dragPayload,
              let resolved = target(for: position, dragging: dragged, outline: outline) else { return nil }
        return FeedListDragKt.resolveFeedListDropAction(
            item: payload.toShared(), target: resolved.target, half: resolved.half, index: index
        )
    }

    /// The folder to spring open while `dragged` hovers at `position`: a collapsed folder a feed
    /// would drop into, so its feeds become reachable mid-drag — matching Compose's own
    /// spring-loaded folder (`FeedListDragAndDrop.kt`). The caller runs the timer itself, rather than
    /// UIKit's `isSpringLoaded`, which selects the row when it fires.
    static func springLoadFolder(
        at position: SidebarDropPosition,
        dragging dragged: SidebarItemID,
        outline: SidebarOutline
    ) -> String? {
        guard dragged.dragPayload?.kind == .feed,
              case .into(let item) = position,
              case .folder(let folderId) = item,
              let section = outline.section(.folders),
              !section.expanded.contains(item) else { return nil }
        return folderId
    }

    // MARK: - Gaps

    private static func feedGapTarget(
        in section: SidebarOutline.Section,
        previous: SidebarItemID?,
        next: SidebarItemID?
    ) -> (target: FeedListDropTarget, half: FeedListRowHalf)? {
        guard section.section == .folders || section.section == .noFolder else { return nil }
        if case .feed(let id) = next { return (FeedListDropTargetFeedRow(feedId: id), .top) }
        switch previous {
        case .feed(let id):
            // The end of that feed's group — its folder, or the unfoldered feeds.
            return (FeedListDropTargetFeedRow(feedId: id), .bottom)
        case .folder(let id) where section.expanded.contains(.folder(id)):
            // An expanded folder followed by no feed is an empty one.
            return (FeedListDropTargetFolderHeader(folderId: id), .top)
        case .noFolderHeader:
            return (FeedListDropTargetNoFolderHeader.shared, .top)
        default:
            return nil
        }
    }

    private static func folderGapTarget(
        in section: SidebarOutline.Section,
        previous: SidebarItemID?,
        next: SidebarItemID?
    ) -> (target: FeedListDropTarget, half: FeedListRowHalf)? {
        guard section.section == .folders else { return nil }
        switch next {
        case .folder(let id):
            return (FeedListDropTargetFolderHeader(folderId: id), .top)
        case .feed(let id):
            // Inside another folder's feeds: `resolveFeedListDropAction` puts the folder just
            // after that one, as Compose does for a folder dropped onto a feed.
            return (FeedListDropTargetFeedRow(feedId: id), .top)
        default:
            break
        }
        switch previous {
        case .folder(let id): return (FeedListDropTargetFolderHeader(folderId: id), .bottom)
        case .feed(let id): return (FeedListDropTargetFeedRow(feedId: id), .bottom)
        default: return nil
        }
    }
}
