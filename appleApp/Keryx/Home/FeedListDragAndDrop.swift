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

// MARK: - macOS 14-compatible geometry observation

/// Replaces `.onGeometryChange` (macOS 15+) with a `PreferenceKey`-based equivalent
/// compatible with macOS 14.0 / iOS 17.0.
/// TODO: Replace with `.onGeometryChange` when deployment target is raised to macOS 15+.
private struct SizePreferenceKey: PreferenceKey {
    static let defaultValue: CGSize = .zero
    static func reduce(value: inout CGSize, nextValue: () -> CGSize) {
        value = nextValue()
    }
}

extension View {
    /// Observes the view's size using GeometryReader + PreferenceKey.
    func onSizeChanged(perform: @escaping (CGSize) -> Void) -> some View {
        background(
            GeometryReader { geometry in
                Color.clear
                    .preference(key: SizePreferenceKey.self, value: geometry.size)
            }
        )
        .onPreferenceChange(SizePreferenceKey.self, perform: perform)
    }
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
    /// Makes this row/header a drop target (`target`), sharing the pane-wide
    /// `activeBoundary`/`dropOnKey`/`hoveredKey` state so every row's own highlight and the
    /// insertion line agree on one target at a time — mirrors Compose's own
    /// `activeBoundaryState`/`hoveredAttachTagIdState` (`FeedListDragController.kt`).
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
        dropOnKey: Binding<FeedListHoverKey?>,
        hoveredKey: Binding<FeedListHoverKey?>,
    ) -> some View {
        modifier(FeedListDropTargetModifier(
            target: target,
            hoverKey: hoverKey,
            home: home,
            index: index,
            draggingItem: draggingItem,
            activeBoundary: activeBoundary,
            dropOnKey: dropOnKey,
            hoveredKey: hoveredKey
        ))
    }

    /// Marks this sidebar row as draggable, carrying `item`, through `.itemProvider` — the
    /// `List`'s own row-drag hook, so the source list (`NSTableView`) itself decides what a click
    /// and what a drag is, anywhere in the row, exactly as Notes and Finder do. The drag image is
    /// the list's own row image.
    ///
    /// Neither SwiftUI gesture-based drag source works here: `.onDrag`/`.draggable` take the
    /// mouse-down wherever the row's content is actually drawn (icon and title), so a click there
    /// never reaches the list's selection while a click on the row's empty space does; and a
    /// `Button` row swallows the drag altogether.
    ///
    /// The provider closure runs when the drag begins, which is this pane's only "drag started"
    /// signal — every `feedListDropTarget` reads `draggingItem` to validate and highlight, so it
    /// must be set before the first hover.
    func feedListDraggable(_ item: FeedListDragPayload, draggingItem: Binding<FeedListDragPayload?>) -> some View {
        itemProvider {
            draggingItem.wrappedValue = item
            let provider = NSItemProvider()
            provider.register(item)
            return provider
        }
    }
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
    @Binding var dropOnKey: FeedListHoverKey?
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
                dropOnKey: $dropOnKey,
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
    @Binding var dropOnKey: FeedListHoverKey?
    @Binding var hoveredKey: FeedListHoverKey?

    @State private var rowHeight: CGFloat = 0

    func body(content: Content) -> some View {
        content
            .onSizeChanged { rowHeight = $0.height }
            .onDrop(of: [feedListDragUTType], delegate: FeedListRowDropDelegate(
                target: target,
                hoverKey: hoverKey,
                home: home,
                index: index,
                draggingItem: $draggingItem,
                activeBoundary: $activeBoundary,
                dropOnKey: $dropOnKey,
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
    @Binding var dropOnKey: FeedListHoverKey?
    @Binding var hoveredKey: FeedListHoverKey?
    let rowHeight: CGFloat

    func validateDrop(info: DropInfo) -> Bool {
        draggingItem != nil && info.hasItemsConforming(to: [feedListDragUTType])
    }

    func dropEntered(info: DropInfo) {
        _ = updateHighlight(at: info.location)
    }

    /// `.forbidden` wherever the shared rules resolve no action, so the cursor carries no move
    /// badge and a release there slides the item back to where it came from.
    func dropUpdated(info: DropInfo) -> DropProposal? {
        let feedback = updateHighlight(at: info.location)
        return DropProposal(operation: feedback == .invalid ? .forbidden : .move)
    }

    func dropExited(info: DropInfo) {
        guard hoveredKey == hoverKey else { return }
        clearHover()
    }

    func performDrop(info: DropInfo) -> Bool {
        guard let dragging = draggingItem else { return false }
        let half = feedListRowHalf(locationY: info.location.y, rowHeight: rowHeight)
        draggingItem = nil
        clearHover()
        guard let action = FeedListDragKt.resolveFeedListDropAction(
            item: dragging.toShared(), target: target, half: half, index: index
        ) else { return false }
        applyFeedListDropAction(action, home: home)
        return true
    }

    private func clearHover() {
        hoveredKey = nil
        activeBoundary = nil
        dropOnKey = nil
    }

    private func updateHighlight(at location: CGPoint) -> FeedListDropFeedback {
        guard let dragging = draggingItem else { return .invalid }
        hoveredKey = hoverKey
        let highlight = FeedListDragKt.resolveFeedListDropHighlight(
            item: dragging.toShared(),
            target: target,
            half: feedListRowHalf(locationY: location.y, rowHeight: rowHeight),
            index: index
        )
        let feedback = feedListDropFeedback(
            isFeedDrag: dragging.kind == .feed,
            hoverKey: hoverKey,
            boundary: highlight.first,
            attachTagId: highlight.second as String?
        )
        activeBoundary = feedback == .insertion ? highlight.first : nil
        dropOnKey = feedback == .dropOn ? hoverKey : nil
        return feedback
    }
}

/// How far a row's background (`feedListRowBackground`) reaches past the row's content, so the
/// drop highlight lines up with where the native sidebar selection is drawn.
let feedListRowHighlightOutset = CGSize(width: 6, height: 3)

/// The insertion indicator `NSOutlineView` draws between rows: an accent-colored line starting
/// from a small hollow circle. It is drawn on the row the item would land beside, from that row's
/// content edge, so the native outline's own indentation puts it at the right level — a nested
/// feed, or a top-level folder.
struct FeedListInsertionLine: View {
    var body: some View {
        HStack(spacing: 0) {
            Circle()
                .strokeBorder(Color.accentColor, lineWidth: 2)
                .frame(width: 7, height: 7)
            Rectangle()
                .fill(Color.accentColor)
                .frame(height: 2)
        }
        .allowsHitTesting(false)
        .accessibilityHidden(true)
    }
}
