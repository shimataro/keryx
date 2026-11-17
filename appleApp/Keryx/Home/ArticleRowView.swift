import KeryxShared
import SwiftUI

/// One article-list row. A `View` of its own — rather than a method of `ArticleListView` — and
/// `Equatable` over exactly what it draws, so a selection or focus change re-renders only the rows
/// whose `isSelected`/`paneFocused` actually changed instead of every visible row. It deliberately
/// takes no `HomeObservable`: reading one of its properties here would tie every row to it again.
struct ArticleRowView: View, Equatable {
    let model: ArticleRowModel
    let isSelected: Bool
    /// Whether the row shows the brief gray highlight of a collapsed list returned to from the reader.
    let isReturnFlashing: Bool
    /// Whether the article list holds the pane focus in the key window. Only macOS dims the
    /// selection without it; a touch-first iOS keeps one selection color, as Android does.
    let paneFocused: Bool
    /// Not observed, only called — actions reach `HomeViewModel` directly.
    let viewModel: HomeViewModel
    let onSelect: () -> Void
    /// Selects the row for a right-click's menu (macOS; an iOS long press never selects).
    let onContextMenuSelect: () -> Void
    /// The shared article-URL copy handler (`HomeObservable.copyArticleUrl`), for the menu's Copy URL
    /// (see `ArticleRowMenu.perform`).
    let onCopyUrl: () -> Void
    /// Whether the pointer is over this row (macOS) — what `ArticleRowMenuState.opensBySelecting`
    /// predicts a right-click's selection from. Written only by `.selectsOnContextMenu`, from the same
    /// hover the right-click's selection runs on, so the prediction and the selection always concern
    /// the same row (including a cell reused under a stationary pointer). Only this row re-renders
    /// when it changes; it is deliberately not part of `==`, which a `@State` change bypasses anyway.
    @State private var pointerIsOver = false

    // Looked up once rather than on every body evaluation of every row; the app's language cannot
    // change while it runs.
    private static let unreadStateLabel = L("article_state_unread")
    private static let starredStateLabel = L("article_state_starred")

    /// The closures are rebuilt by every parent evaluation and never compared: they only forward to
    /// actions keyed by this row's own article, which `model` already covers.
    nonisolated static func == (lhs: ArticleRowView, rhs: ArticleRowView) -> Bool {
        MainActor.assumeIsolated {
            lhs.model == rhs.model && lhs.isSelected == rhs.isSelected
                && lhs.isReturnFlashing == rhs.isReturnFlashing && lhs.paneFocused == rhs.paneFocused
        }
    }

    var body: some View {
        Button(action: onSelect) {
            ArticleRowLabel(model: model, isSelected: isSelected, isReturnFlashing: isReturnFlashing, paneFocused: paneFocused)
        }
        .buttonStyle(.plain)
        .accessibilityValue(stateAccessibilityValue)
        // The selection is otherwise only a background fill, which VoiceOver cannot see.
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        #if os(macOS)
        .selectsOnContextMenu(id: model.id, pointerIsOver: $pointerIsOver, perform: onContextMenuSelect)
        .contextMenu {
            // A right-click on a hovered, unselected row selects it first, matching Compose's own
            // `articleRowContextMenuOpen` (`ArticleRowComponents.kt`) — the actual selection runs via
            // `.selectsOnContextMenu` above, not as a side effect of this builder (see
            // `ContextMenuSelectionTracker`'s own doc for why). A menu opened with the pointer elsewhere
            // (keyboard, VoiceOver) selects nothing, so its labels are the row's current state.
            let selectedByOpen = ArticleRowMenuState.opensBySelecting(isSelected: isSelected, pointerIsOver: pointerIsOver)
            ForEach(Array(ArticleRowMenuState.items(for: model, selectedByOpen: selectedByOpen).enumerated()), id: \.offset) { _, item in
                if item.startsGroup { Divider() }
                Button(item.title) {
                    ArticleRowMenu.perform(item.action, on: model, viewModel: viewModel, onCopyUrl: onCopyUrl)
                }
                .disabled(!item.isEnabled)
            }
        }
        #else
        // The same menu, put on the list's cell instead (`ArticleRowContextMenu`): a SwiftUI
        // `.contextMenu` here makes a swipe start with the row standing still, then jumping.
        .background {
            ArticleRowContextMenu(source: ArticleRowMenuSource(
                model: model,
                isSelected: isSelected,
                paneFocused: paneFocused,
                viewModel: viewModel,
                onCopyUrl: onCopyUrl
            ))
        }
        #endif
    }

    /// What the unread dot and the star show, for VoiceOver: empty for a read, unstarred article.
    private var stateAccessibilityValue: String {
        [
            model.isRead ? nil : Self.unreadStateLabel,
            model.isStarred ? Self.starredStateLabel : nil,
        ]
        .compactMap { $0 }
        .joined(separator: ", ")
    }
}
