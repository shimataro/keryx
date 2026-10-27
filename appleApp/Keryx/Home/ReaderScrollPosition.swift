import KeryxShared

/// The iOS reader's scroll position as `HomeView` keeps it in scene storage, so a relaunch after iOS
/// terminated the app in the background reopens the restored article where the user left it — kept
/// free of SwiftUI so the standalone `KeryxTests` bundle can compile it directly.
///
/// Only the article the reader shows (the selection) is recorded, as a fraction of its scrollable
/// height (`ReaderScrollRestoration`). It is offered back only to that article's own page, only while
/// it is still the selection, and never to the neighbours the pager keeps ready for a swipe.
struct ReaderScrollPosition: Equatable {
    let articleId: String
    let fraction: Double

    /// The position scene storage holds, or nil when it holds none (`articleId` empty).
    static func stored(articleId: String, fraction: Double) -> ReaderScrollPosition? {
        articleId.isEmpty ? nil : ReaderScrollPosition(articleId: articleId, fraction: fraction)
    }

    /// The fraction page `pageId` scrolls to once its first document has loaded, if any.
    /// Clamped by the shared rule (`restorableScrollFraction`); nil for the top or an invalid value.
    func initialFraction(forPage pageId: String, selectedId: String?) -> Double? {
        guard pageId == articleId, pageId == selectedId else { return nil }
        return ReaderScrollRestorationKt.restorableScrollFraction(fraction: fraction)?.doubleValue
    }
}
