import KeryxShared
#if os(iOS)
import SwiftUI
import UIKit
#endif

/// Runs an article row menu's items (`ArticleRowMenuState.items`) — the one handler every UI's row
/// menu calls, so macOS's SwiftUI menu and the iOS cell's `UIMenu` cannot drift apart in what an item
/// does.
@MainActor
enum ArticleRowMenu {
    /// - Parameters:
    ///   - onCopyUrl: the shared article-URL copy handler (`HomeObservable.copyArticleUrl`), so the
    ///     menu's Copy URL flashes the reader's ✓ — or gets the in-app confirmation — exactly like the
    ///     menu bar's command and the reader's button.
    static func perform(
        _ action: ArticleRowMenuItem.Action,
        on model: ArticleRowModel,
        viewModel: HomeViewModel,
        onCopyUrl: () -> Void
    ) {
        switch action {
        case .setStarred(let starred):
            viewModel.setStarred(article: model.row, starred: starred)
        case .setRead(let read):
            viewModel.setRead(article: model.row, read: read)
        case .openInBrowser:
            openInBrowserIfAllowed(model.url)
        case .copyUrl:
            onCopyUrl()
        }
    }
}

#if os(iOS)
extension ArticleRowMenu {
    /// An article row's long-press menu as UIKit shows it: the shared items
    /// (`ArticleRowMenuState.items`) as a `UIMenu`, run through `perform`, with the row's look
    /// (`ArticleRowLabel`) as the preview. An iOS long press never selects the row, so the menu is the
    /// row's current state.
    ///
    /// - Parameter previewSize: the size of the row's cell, which the preview takes.
    static func configuration(
        for model: ArticleRowModel,
        isSelected: Bool,
        paneFocused: Bool,
        previewSize: CGSize,
        viewModel: HomeViewModel,
        onCopyUrl: @escaping () -> Void
    ) -> UIContextMenuConfiguration {
        let selectedByOpen = ArticleRowMenuState.opensBySelecting(isSelected: isSelected, pointerIsOver: false)
        let items = ArticleRowMenuState.items(for: model, selectedByOpen: selectedByOpen)
        return UIContextMenuConfiguration(identifier: nil, previewProvider: {
            let preview = UIHostingController(rootView: ArticleRowLabel(
                model: model,
                isSelected: isSelected,
                isReturnFlashing: false,
                paneFocused: paneFocused
            ))
            preview.preferredContentSize = previewSize
            return preview
        }, actionProvider: { _ in
            menu(items) { action in
                perform(action, on: model, viewModel: viewModel, onCopyUrl: onCopyUrl)
            }
        })
    }

    /// The items as a `UIMenu`, each group (`ArticleRowMenuItem.startsGroup`) an inline section so the
    /// menu draws the same separators as the SwiftUI menu.
    private static func menu(
        _ items: [ArticleRowMenuItem],
        perform: @escaping (ArticleRowMenuItem.Action) -> Void
    ) -> UIMenu {
        var groups: [[UIAction]] = []
        for item in items {
            if item.startsGroup || groups.isEmpty { groups.append([]) }
            groups[groups.count - 1].append(UIAction(
                title: item.title,
                attributes: item.isEnabled ? [] : .disabled
            ) { _ in perform(item.action) })
        }
        return UIMenu(children: groups.map { UIMenu(options: .displayInline, children: $0) })
    }
}
#endif
