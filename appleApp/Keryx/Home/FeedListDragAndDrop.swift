import KeryxShared
import SwiftUI
import UniformTypeIdentifiers

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
    /// Makes this folder row, tag row or "No folder" header a target a dragged feed is dropped
    /// *onto* (moved into that folder or the unfoldered group, or tagged), sharing the pane-wide
    /// `dropOnKey`/`hoveredKey` state so exactly one row is highlighted at a time. Dropping
    /// *between* rows is the outline's own `.onInsert` instead (`performFeedListInsert`), which also
    /// draws the insertion line.
    func feedListDropTarget(
        _ target: FeedListDropTarget,
        hoverKey: FeedListHoverKey,
        home: HomeObservable,
        index: FeedListDropIndex,
        draggingItem: Binding<FeedListDragPayload?>,
        dropOnKey: Binding<FeedListHoverKey?>,
        hoveredKey: Binding<FeedListHoverKey?>,
    ) -> some View {
        onDrop(of: [feedListFeedDragType], delegate: FeedListDropOnDelegate(
            target: target,
            hoverKey: hoverKey,
            home: home,
            index: index,
            draggingItem: draggingItem,
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
    /// signal — every drop target reads `draggingItem` to validate and act, so it must be set
    /// before the first hover. The payload is registered under its kind's own type
    /// (`FeedListDragPayload.contentType`), visible to this process only.
    ///
    /// `enabled` is off while the row's name is being edited in place, so a press-and-sweep to select
    /// text isn't taken as a row drag (Compose's `feedListReorderDrag(enabled = inlineEdit == null)`).
    /// Disabling passes `nil` to `.itemProvider` rather than branching around it, so the row keeps
    /// its view identity when an edit starts or ends instead of being rebuilt underneath the editor.
    func feedListDraggable(
        _ item: FeedListDragPayload,
        draggingItem: Binding<FeedListDragPayload?>,
        enabled: Bool = true
    ) -> some View {
        itemProvider(enabled ? {
            draggingItem.wrappedValue = item
            let provider = NSItemProvider()
            let data = try? JSONEncoder().encode(item)
            provider.registerDataRepresentation(
                forTypeIdentifier: item.contentType.identifier,
                visibility: .ownProcess
            ) { completion in
                completion(data, nil)
                return nil
            }
            return provider
        } : nil)
    }
}

/// Applies a drop *between* rows, from `.onInsert`: `offset` is where in `group` the outline put
/// the insertion line. It is translated into the shared drop target and half
/// (`feedListInsertTarget`) and resolved through the same `resolveFeedListDropAction` the Compose
/// app uses, so both apps apply the same rules. The item comes from `draggingItem` rather than the
/// providers — every drag this pane accepts starts in this pane.
@MainActor
func performFeedListInsert(
    into group: FeedListInsertGroup,
    at offset: Int,
    draggingItem: Binding<FeedListDragPayload?>,
    index: FeedListDropIndex,
    home: HomeObservable
) {
    guard let dragging = draggingItem.wrappedValue else { return }
    draggingItem.wrappedValue = nil
    guard group.accepts(dragging.kind),
          let insert = feedListInsertTarget(in: group, at: offset),
          let action = FeedListDragKt.resolveFeedListDropAction(
              item: dragging.toShared(), target: insert.target, half: insert.half, index: index
          ) else { return }
    applyFeedListDropAction(action, home: home)
}

/// Tracks a dragged feed hovering a row it would be dropped onto, for that row's highlight
/// (`dropOnKey`) and the spring-loaded folder (`hoveredKey`). The drop itself is applied from the
/// already-tracked `draggingItem` (set at drag start by `feedListDraggable`) rather than decoded from
/// `info.itemProviders`.
private struct FeedListDropOnDelegate: DropDelegate {
    let target: FeedListDropTarget
    let hoverKey: FeedListHoverKey
    let home: HomeObservable
    let index: FeedListDropIndex
    @Binding var draggingItem: FeedListDragPayload?
    @Binding var dropOnKey: FeedListHoverKey?
    @Binding var hoveredKey: FeedListHoverKey?

    func validateDrop(info: DropInfo) -> Bool {
        draggingItem != nil && info.hasItemsConforming(to: [feedListFeedDragType])
    }

    func dropEntered(info: DropInfo) {
        _ = updateHighlight()
    }

    /// `.forbidden` wherever the drop would do nothing, so the cursor carries no move badge and a
    /// release there slides the item back to where it came from.
    func dropUpdated(info: DropInfo) -> DropProposal? {
        DropProposal(operation: updateHighlight() == .invalid ? .forbidden : .move)
    }

    func dropExited(info: DropInfo) {
        guard hoveredKey == hoverKey else { return }
        clearHover()
    }

    func performDrop(info: DropInfo) -> Bool {
        guard let dragging = draggingItem else { return false }
        draggingItem = nil
        clearHover()
        // A header's own action doesn't depend on which half of it the item is over.
        guard feedListDropFeedback(for: dragging.kind) == .dropOn,
              let action = FeedListDragKt.resolveFeedListDropAction(
                  item: dragging.toShared(), target: target, half: .top, index: index
              ) else { return false }
        applyFeedListDropAction(action, home: home)
        return true
    }

    private func clearHover() {
        hoveredKey = nil
        dropOnKey = nil
    }

    private func updateHighlight() -> FeedListDropFeedback {
        guard let dragging = draggingItem else { return .invalid }
        hoveredKey = hoverKey
        let feedback = feedListDropFeedback(for: dragging.kind)
        dropOnKey = feedback == .dropOn ? hoverKey : nil
        return feedback
    }
}
