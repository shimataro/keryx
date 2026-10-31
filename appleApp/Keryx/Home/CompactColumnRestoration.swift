/// Which column the collapsed split view (iPhone, compact width) comes back on after iOS terminated
/// the app in the background — kept free of SwiftUI so the standalone `KeryxTests` bundle can
/// compile it directly.
///
/// `HomeView` keeps the column in scene storage, which iOS restores when it relaunches a scene it
/// terminated itself and discards when the user quits the app from the app switcher, so the app
/// reopens where it was left only in the first case. The reader is only restored together with the
/// article it showed (`HomeViewModel` restores the selection): with nothing selected the restored
/// stack shows the article list instead of an empty reader.
enum CompactColumnRestoration {
    typealias Column = CompactSearchNavigation.Column

    /// The column a stored `value` (a `Column` raw value) names; anything unrecognised is the
    /// sidebar, where a fresh launch starts.
    static func column(storageValue value: String) -> Column {
        Column(rawValue: value) ?? .sidebar
    }

    /// The column to show for a restored `column`, given whether an article is selected.
    static func restored(_ column: Column, hasSelectedArticle: Bool) -> Column {
        column == .detail && !hasSelectedArticle ? .content : column
    }
}
