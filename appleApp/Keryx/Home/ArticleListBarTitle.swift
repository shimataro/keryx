/// Decides whether the iOS article list's bar names the list in place of its heading row
/// (`ArticleListView`, before iOS 26 only): the heading is the list's first row, and once it has
/// scrolled away the bar's principal item shows the title instead.
///
/// The heading row reports itself through `onAppear` / `onDisappear`, tagged with the `List` it
/// belongs to (`Owner`). Only those reports move the state, so two things that used to break it
/// cannot:
/// - Coming back from the reader brings `onAppear` only for the rows on screen. A heading that had
///   scrolled away stays reported as off screen, and the bar keeps the title.
/// - A rebuilt `List` (a new owner) can see the old one's `onDisappear` arrive after its own
///   reports; a report from any owner but the current one is ignored.
///
/// Whether the list is the content on screen at all is the caller's to say (`listShown`), from the
/// state it draws rather than from an event, so an empty or search state never shows the title over
/// its own heading.
struct ArticleListBarTitle<Owner: Hashable> {
    /// The current owner's last heading report: whether its heading is on screen. nil until it
    /// reports, which counts as on screen, so a list just built shows nothing in the bar before its
    /// first layout.
    private var lastReport: (owner: Owner, onScreen: Bool)?

    /// `owner`'s heading came on screen; `current` is the `List` on screen now.
    mutating func headerAppeared(in owner: Owner, current: Owner) {
        guard owner == current else { return }
        lastReport = (owner, true)
    }

    /// `owner`'s heading left the screen; `current` is the `List` on screen now.
    mutating func headerDisappeared(from owner: Owner, current: Owner) {
        guard owner == current else { return }
        lastReport = (owner, false)
    }

    /// Whether the bar shows the title: `listShown` (the list, not another state of the pane, is on
    /// screen) and `current`'s heading has been reported off screen.
    func showsTitle(listShown: Bool, current: Owner) -> Bool {
        guard listShown, let lastReport, lastReport.owner == current else { return false }
        return !lastReport.onScreen
    }
}
