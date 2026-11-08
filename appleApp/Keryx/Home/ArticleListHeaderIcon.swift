import KeryxShared

/// The icon shown beside the article list's heading: the same one the subscription list gives the
/// selected item (`SidebarRowStaticContent`), so All / Starred / a folder / a tag / a feed read the
/// same in both places.
enum ArticleListHeaderIcon {
    /// A filter whose feed, folder or tag is gone (deleted on another device and not yet synced)
    /// falls back to All's icon, as the shared `articleListTitle` falls back to All's name.
    static func resolve(filter: ArticleFilter, model: SidebarModel) -> SidebarRowIcon {
        let content: SidebarRowStaticContent? = switch onEnum(of: filter) {
        case .all: .all
        case .starred: .starred
        case .feed(let f): model.feedContents[f.feedId]
        case .folder(let f): model.folderContents[f.folderId]
        case .tag(let t): model.tagContents[t.tagId]
        }
        return (content ?? .all).icon
    }
}
