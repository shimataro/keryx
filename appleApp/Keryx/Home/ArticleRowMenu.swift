import KeryxShared

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
