#if os(iOS)
import KeryxShared
import UIKit

/// One drag from the iOS sidebar, carried as the drag session's `localContext`: the lifted row, the
/// last drop position proposed, and the spring-loading folder being waited on.
final class SidebarDragContext {
    let item: SidebarItemID
    var lastPosition: SidebarDropPosition?
    var springFolderId: String?
    var springTask: Task<Void, Never>?

    init(item: SidebarItemID) {
        self.item = item
    }
}

/// Drag and drop in the iOS sidebar: a feed moved into a folder, out of every folder or onto a tag,
/// feeds and folders reordered between rows — the same shared rules as Compose and the macOS list
/// (`resolveFeedListDropAction`), reached through `SidebarDropResolver`. The collection view owns
/// these delegates itself, which is why the iOS sidebar is not a SwiftUI `List` (see "Sidebar (iOS)"
/// in `docs/app-architecture.md`).
extension SidebarCollectionViewController: UICollectionViewDragDelegate, UICollectionViewDropDelegate {
    // MARK: - Drag

    /// Only the lifted row is dragged: never a header, All/Starred, a tag, or the row being renamed.
    func collectionView(
        _ collectionView: UICollectionView,
        itemsForBeginning session: UIDragSession,
        at indexPath: IndexPath
    ) -> [UIDragItem] {
        guard session.localContext == nil,
              let item = dataSource.itemIdentifier(for: indexPath),
              item.dragPayload != nil,
              state?.contents[item]?.isRenaming != true else { return [] }
        // Nothing leaves the app, so the provider carries no data; the item travels as the local
        // object.
        let dragItem = UIDragItem(itemProvider: NSItemProvider())
        dragItem.localObject = item
        session.localContext = SidebarDragContext(item: item)
        return [dragItem]
    }

    func collectionView(_ collectionView: UICollectionView, itemsForAddingTo session: UIDragSession, at indexPath: IndexPath, point: CGPoint) -> [UIDragItem] {
        []
    }

    func collectionView(_ collectionView: UICollectionView, dragSessionIsRestrictedToDraggingApplication session: UIDragSession) -> Bool {
        true
    }

    func collectionView(_ collectionView: UICollectionView, dragSessionWillBegin session: UIDragSession) {
        dragInProgress = true
    }

    /// Lifting an expanded folder collapses it in the data source without telling the outline's
    /// handlers, and it stays collapsed afterwards — so the state is applied again here, along with
    /// whatever arrived mid-drag.
    func collectionView(_ collectionView: UICollectionView, dragSessionDidEnd session: UIDragSession) {
        (session.localContext as? SidebarDragContext)?.springTask?.cancel()
        dragInProgress = false
        if let latest = deferredState ?? state { apply(latest, force: true) }
    }

    // MARK: - Drop

    private func dragContext(_ session: UIDropSession) -> SidebarDragContext? {
        session.localDragSession?.localContext as? SidebarDragContext
    }

    func collectionView(_ collectionView: UICollectionView, canHandle session: UIDropSession) -> Bool {
        dragContext(session) != nil
    }

    func collectionView(
        _ collectionView: UICollectionView,
        dropSessionDidUpdate session: UIDropSession,
        withDestinationIndexPath destinationIndexPath: IndexPath?
    ) -> UICollectionViewDropProposal {
        guard let context = dragContext(session), let outline = state?.outline else {
            return UICollectionViewDropProposal(operation: .forbidden)
        }
        let position = position(for: session, destination: destinationIndexPath, context: context, outline: outline)
        context.lastPosition = position
        updateSpringLoading(position, context: context, outline: outline)
        guard let position,
              SidebarDropResolver.action(for: position, dragging: context.item, outline: outline, index: actions.dropIndex()) != nil else {
            return UICollectionViewDropProposal(operation: .forbidden)
        }
        switch position {
        case .into: return UICollectionViewDropProposal(operation: .move, intent: .insertIntoDestinationIndexPath)
        case .gap: return UICollectionViewDropProposal(operation: .move, intent: .insertAtDestinationIndexPath)
        }
    }

    /// Onto the row under the finger when the resolver takes it as a drop *onto* it, otherwise the
    /// gap UIKit proposes. While a gap is open the finger is over the dragged row's own placeholder,
    /// which is never a row to drop onto.
    private func position(
        for session: UIDropSession,
        destination: IndexPath?,
        context: SidebarDragContext,
        outline: SidebarOutline
    ) -> SidebarDropPosition? {
        let location = session.location(in: collectionView)
        if let payload = context.item.dragPayload,
           let indexPath = collectionView.indexPathForItem(at: location),
           let item = dataSource.itemIdentifier(for: indexPath), item != context.item,
           let frame = collectionView.layoutAttributesForItem(at: indexPath)?.frame, frame.height > 0,
           let into = SidebarDropResolver.intoTarget(
               over: item, fraction: Double((location.y - frame.minY) / frame.height), dragging: payload
           ) {
            return .into(into)
        }
        guard let destination else { return nil }
        let source = dataSource.indexPath(for: context.item).map { (section: $0.section, item: $0.item) }
        return SidebarDropResolver.gapPosition(
            destination: (destination.section, destination.item),
            source: source,
            previous: context.lastPosition,
            outline: outline
        )
    }

    func collectionView(_ collectionView: UICollectionView, performDropWith coordinator: UICollectionViewDropCoordinator) {
        guard let context = dragContext(coordinator.session), let outline = state?.outline else { return }
        context.springTask?.cancel()
        // For a gap, the coordinator's destination is where the gap really is (the last update may
        // have reported the dragged row's own index path instead).
        let position: SidebarDropPosition?
        if coordinator.proposal.intent == .insertAtDestinationIndexPath, let destination = coordinator.destinationIndexPath {
            position = SidebarDropResolver.gapPosition(
                destination: (destination.section, destination.item), source: nil, previous: nil, outline: outline
            )
        } else {
            position = context.lastPosition
        }
        guard let position,
              let action = SidebarDropResolver.action(for: position, dragging: context.item, outline: outline, index: actions.dropIndex()),
              let dragItem = coordinator.items.first?.dragItem else { return }
        switch position {
        case .into(let item):
            // The default preview is a snapshot of the dragged row that fills the target's bounds, so
            // for the whole drop animation the dragged feed's name would sit over the target's title.
            // Shrinking it into the row's center keeps the title readable.
            if let indexPath = dataSource.indexPath(for: item),
               let center = collectionView.layoutAttributesForItem(at: indexPath)?.center {
                let target = UIDragPreviewTarget(
                    container: collectionView,
                    center: center,
                    transform: CGAffineTransform(scaleX: 0.05, y: 0.05)
                )
                coordinator.drop(dragItem, to: target)
            }
        case .gap:
            if let destination = coordinator.destinationIndexPath {
                coordinator.drop(dragItem, toItemAt: destination)
            }
        }
        actions.applyDrop(action)
    }

    func collectionView(_ collectionView: UICollectionView, dropSessionDidExit session: UIDropSession) {
        guard let context = dragContext(session) else { return }
        context.lastPosition = nil
        context.springTask?.cancel()
        context.springFolderId = nil
    }

    // MARK: - Spring loading

    /// Holding a dragged feed over a collapsed folder opens it after the system's spring-loading
    /// delay, so its feeds become reachable drop targets mid-drag — Compose's own spring-loaded
    /// folder (`FeedListDragAndDrop.kt`). Timed here rather than with UIKit's `isSpringLoaded`,
    /// which selects the row when it fires.
    private func updateSpringLoading(_ position: SidebarDropPosition?, context: SidebarDragContext, outline: SidebarOutline) {
        let folderId = position.flatMap { SidebarDropResolver.springLoadFolder(at: $0, dragging: context.item, outline: outline) }
        guard folderId != context.springFolderId else { return }
        context.springTask?.cancel()
        context.springFolderId = folderId
        guard let folderId, let delay = springLoadingDelay() else { return }
        context.springTask = Task { @MainActor [weak self, weak context] in
            try? await Task.sleep(for: delay)
            // Still over the same folder once the pause is over.
            guard !Task.isCancelled, let self, let context, context.springFolderId == folderId else { return }
            self.springOpen(folderId)
        }
    }
}
#endif
