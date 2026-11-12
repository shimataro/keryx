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

#if os(macOS)
extension View {
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
    /// signal — `SidebarDropGuard` reads `draggingItem` to validate and act, so it must be set
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
#endif
