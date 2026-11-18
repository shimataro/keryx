/// A scroll `ArticleListView` asks `ArticleCollectionView` for, carried out once per `generation`
/// against the rows the list holds once it is on screen.
struct ArticleListScrollRequest: Equatable {
    /// A row to bring into view.
    struct Target: Equatable {
        var rowId: String
        /// Whether a row already on screen stays where it is (bringing the selection into view).
        var onlyIfOffScreen = false
    }

    enum Kind: Equatable {
        /// Bring a row into view.
        case reveal(Target)
        /// The new-articles pill's jump to the fresh end.
        case freshEnd(newestFirst: Bool)
    }

    var generation = 0
    var kind: Kind?

    /// The next request, replacing this one.
    func next(_ kind: Kind) -> ArticleListScrollRequest {
        ArticleListScrollRequest(generation: generation + 1, kind: kind)
    }

    /// Whether `ArticleListView` is showing its rows right now — the only time a scroll asked of it
    /// can reach the list. The rows and `listBody` arrive separately, so rows can be there while the
    /// body is still pending; a search shows the list only once its query has terms and results.
    static func listShowsRows(bodyIsContent: Bool, rowCount: Int, searchActive: Bool, searchShowsList: Bool) -> Bool {
        bodyIsContent && rowCount > 0 && (!searchActive || searchShowsList)
    }
}
