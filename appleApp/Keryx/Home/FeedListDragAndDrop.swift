import CoreTransferable
import KeryxShared
import SwiftUI
import UniformTypeIdentifiers

/// What a feed-list drag carries across the wire — decoded on the drop side to resolve into the
/// shared `FeedListDraggedItem` the drop-resolution functions take. A dedicated `Transferable`
/// (not a bare `String`, as the previous `.draggable(feed.id)` used) so a plain text drag from
/// another app is never mistaken for a feed-list reorder.
struct FeedListDragPayload: Codable, Transferable {
    enum Kind: String, Codable {
        case feed
        case folder
    }
    let kind: Kind
    let id: String

    static var transferRepresentation: some TransferRepresentation {
        CodableRepresentation(contentType: feedListDragUTType)
    }

    func toShared() -> FeedListDraggedItem {
        switch kind {
        case .feed: return FeedListDraggedItemFeed(feedId: id)
        case .folder: return FeedListDraggedItemFolder(folderId: id)
        }
    }
}

/// Shared between `FeedListDragPayload`'s own `Transferable` conformance and
/// `FeedListDropTargetModifier`'s `.onDrop(of:delegate:)`, so both sides agree on exactly one
/// exported type — a drag carrying anything else (plain text from another app, say) is rejected by
/// `FeedListRowDropDelegate.validateDrop` rather than mistaken for a feed-list reorder.
let feedListDragUTType = UTType(exportedAs: "works.merc.keryx.app.feedlistitem")

/// Swift-native mirror of which row/header is currently the drop target, used only for `@State`
/// identity (which row's highlight to clear on `isTargeted(false)`) — see `feedListDropTarget`'s
/// own KDoc for why this can't just compare the bridged `FeedListDropTarget` directly.
enum FeedListHoverKey: Equatable {
    case folder(String)
    case noFolder
    case feed(String)
    case tag(String)
}

/// Applies a resolved [FeedListDropAction] through the matching `HomeViewModel` call.
@MainActor
func applyFeedListDropAction(_ action: FeedListDropAction, home: HomeObservable) {
    switch onEnum(of: action) {
    case .moveFeed(let move):
        home.viewModel.moveFeed(feedId: move.feedId, folderId: move.folderId, targetFeedId: move.targetFeedId)
    case .attachTag(let attach):
        home.viewModel.setFeedTag(feedId: attach.feedId, tagId: attach.tagId, attached: true)
    case .reorderFolder(let reorder):
        home.viewModel.reorderFolders(draggedFolderId: reorder.draggedFolderId, targetFolderId: reorder.targetFolderId)
    }
}

extension View {
    /// Makes this row/header both a drag source (when `dragItem` is non-nil) and a drop target
    /// (`target`), sharing the pane-wide `activeBoundary`/`hoveredTagId`/`hoveredKey` state so every
    /// row's own highlight and the floating insertion line agree on one boundary at a time — mirrors
    /// Compose's own `activeBoundaryState`/`hoveredAttachTagIdState` (`FeedListDragController.kt`).
    /// Backed by a custom `DropDelegate` (`FeedListRowDropDelegate`), not the higher-level
    /// `.dropDestination(for:action:isTargeted:)`, because only `DropDelegate.dropUpdated(info:)`
    /// exposes a continuously-updated `info.location` while hovering — `isTargeted`'s callback
    /// carries no location at all — so both the live highlight and the eventual drop resolve the
    /// same top/bottom half from the same pointer position.
    func feedListDropTarget(
        _ target: FeedListDropTarget,
        hoverKey: FeedListHoverKey,
        home: HomeObservable,
        index: FeedListDropIndex,
        draggingItem: Binding<FeedListDragPayload?>,
        activeBoundary: Binding<DropBoundary?>,
        hoveredTagId: Binding<String?>,
        hoveredKey: Binding<FeedListHoverKey?>,
    ) -> some View {
        modifier(FeedListDropTargetModifier(
            target: target,
            hoverKey: hoverKey,
            home: home,
            index: index,
            draggingItem: draggingItem,
            activeBoundary: activeBoundary,
            hoveredTagId: hoveredTagId,
            hoveredKey: hoveredKey
        ))
    }

    /// Marks this row/header as draggable, carrying `item` — the drag payload is built lazily (only
    /// once a drag actually starts), which doubles as this row's only signal that a drag of `item`
    /// began, since SwiftUI's `.draggable` has no separate "drag started" callback. `draggingItem`
    /// is what every `feedListDropTarget` on the same pane reads to resolve its own hover highlight.
    func feedListDraggable(_ item: FeedListDragPayload, draggingItem: Binding<FeedListDragPayload?>) -> some View {
        // `.draggable(_:)` takes an `@autoclosure`, which only wraps a single expression — passing
        // this function call (rather than a `{ ... }` block) is what makes the side effect run
        // lazily, on drag start, instead of eagerly on every body evaluation.
        draggable(markDragStarted(item, into: draggingItem))
    }
}

/// Records `item` as the drag in progress and returns it, for `feedListDraggable`'s own
/// `@autoclosure` trick above.
@MainActor
private func markDragStarted(_ item: FeedListDragPayload, into draggingItem: Binding<FeedListDragPayload?>) -> FeedListDragPayload {
    draggingItem.wrappedValue = item
    return item
}

/// Applies `feedListDropTarget` only when `isEnabled` — lets a feed row that shouldn't itself be a
/// drop target (a copy nested under an expanded tag; see `FeedListView.feedRow`'s own `isDropTarget`
/// parameter) share the same call site as one that should, rather than branching the whole row.
struct ConditionalFeedListDropTarget: ViewModifier {
    let isEnabled: Bool
    let target: FeedListDropTarget
    let hoverKey: FeedListHoverKey
    let home: HomeObservable
    let index: FeedListDropIndex
    @Binding var draggingItem: FeedListDragPayload?
    @Binding var activeBoundary: DropBoundary?
    @Binding var hoveredTagId: String?
    @Binding var hoveredKey: FeedListHoverKey?

    func body(content: Content) -> some View {
        if isEnabled {
            content.feedListDropTarget(
                target,
                hoverKey: hoverKey,
                home: home,
                index: index,
                draggingItem: $draggingItem,
                activeBoundary: $activeBoundary,
                hoveredTagId: $hoveredTagId,
                hoveredKey: $hoveredKey
            )
        } else {
            content
        }
    }
}

private struct FeedListDropTargetModifier: ViewModifier {
    let target: FeedListDropTarget
    let hoverKey: FeedListHoverKey
    let home: HomeObservable
    let index: FeedListDropIndex
    @Binding var draggingItem: FeedListDragPayload?
    @Binding var activeBoundary: DropBoundary?
    @Binding var hoveredTagId: String?
    @Binding var hoveredKey: FeedListHoverKey?

    @State private var rowHeight: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .onGeometryChange(for: CGFloat.self, of: { $0.size.height }) { rowHeight = $0 }
            .onDrop(of: [feedListDragUTType], delegate: FeedListRowDropDelegate(
                target: target,
                hoverKey: hoverKey,
                home: home,
                index: index,
                draggingItem: $draggingItem,
                activeBoundary: $activeBoundary,
                hoveredTagId: $hoveredTagId,
                hoveredKey: $hoveredKey,
                rowHeight: rowHeight
            ))
    }
}

/// Resolves the drop as the drag actually moves over this row/header, not just at release —
/// `dropUpdated(info:)` is called continuously with `info.location`, which `dropDestination`'s own
/// `isTargeted` callback never provides. The drop itself is applied from the already-tracked
/// `draggingItem` (set at drag-start by `feedListDraggable`) rather than decoded from
/// `info.itemProviders`: every drag this pane accepts originates from this same pane, so there is no
/// need for `NSItemProvider`'s asynchronous `Transferable` decoding.
private struct FeedListRowDropDelegate: DropDelegate {
    let target: FeedListDropTarget
    let hoverKey: FeedListHoverKey
    let home: HomeObservable
    let index: FeedListDropIndex
    @Binding var draggingItem: FeedListDragPayload?
    @Binding var activeBoundary: DropBoundary?
    @Binding var hoveredTagId: String?
    @Binding var hoveredKey: FeedListHoverKey?
    let rowHeight: CGFloat

    func validateDrop(info: DropInfo) -> Bool {
        draggingItem != nil && info.hasItemsConforming(to: [feedListDragUTType])
    }

    func dropEntered(info: DropInfo) {
        updateHighlight(at: info.location)
    }

    func dropUpdated(info: DropInfo) -> DropProposal? {
        guard draggingItem != nil else { return DropProposal(operation: .forbidden) }
        updateHighlight(at: info.location)
        return DropProposal(operation: .move)
    }

    func dropExited(info: DropInfo) {
        guard hoveredKey == hoverKey else { return }
        hoveredKey = nil
        activeBoundary = nil
        hoveredTagId = nil
    }

    func performDrop(info: DropInfo) -> Bool {
        guard let dragging = draggingItem else { return false }
        let half = resolvedHalf(for: info.location)
        draggingItem = nil
        guard let action = FeedListDragKt.resolveFeedListDropAction(
            item: dragging.toShared(), target: target, half: half, index: index
        ) else { return false }
        applyFeedListDropAction(action, home: home)
        return true
    }

    private func resolvedHalf(for location: CGPoint) -> FeedListRowHalf {
        rowHeight > 0 && location.y >= rowHeight / 2 ? .bottom : .top
    }

    private func updateHighlight(at location: CGPoint) {
        guard let dragging = draggingItem else { return }
        hoveredKey = hoverKey
        let highlight = FeedListDragKt.resolveFeedListDropHighlight(
            item: dragging.toShared(), target: target, half: resolvedHalf(for: location), index: index
        )
        activeBoundary = highlight.first
        hoveredTagId = highlight.second as String?
    }
}

/// A thin insertion-line indicator — see `feedListDropTarget`'s own KDoc for why this is
/// deliberately simpler than Compose's paired/indented markers (`InsertionMarker` in
/// `FeedListDragAndDrop.kt`): the drag-and-drop batch keeps visuals minimal, leaving exact styling
/// to the later UI-review pass.
struct FeedListInsertionLine: View {
    var body: some View {
        Rectangle()
            .fill(Color.accentColor)
            .frame(height: 2)
    }
}
