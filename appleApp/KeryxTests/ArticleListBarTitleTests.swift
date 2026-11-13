import Testing

/// `ArticleListBarTitle` decides whether the iOS article list's bar names the list in place of its
/// heading row (before iOS 26).
@Suite
struct ArticleListBarTitleTests {
    private typealias BarTitle = ArticleListBarTitle<Int>

    @Test
    func aListThatHasNotReportedShowsNoTitle() {
        let title = BarTitle()

        #expect(!title.showsTitle(listShown: true, current: 0))
    }

    @Test
    func theTitleShowsOnlyWhileTheHeadingIsOffScreen() {
        var title = BarTitle()

        title.headerAppeared(in: 0, current: 0)
        #expect(!title.showsTitle(listShown: true, current: 0))

        title.headerDisappeared(from: 0, current: 0)
        #expect(title.showsTitle(listShown: true, current: 0))

        title.headerAppeared(in: 0, current: 0)
        #expect(!title.showsTitle(listShown: true, current: 0))
    }

    /// Coming back from the reader brings `onAppear` only for the rows on screen: a heading that had
    /// scrolled away reports nothing, and the bar keeps naming the list.
    @Test
    func theTitleStaysAfterComingBackWithTheHeadingScrolledAway() {
        var title = BarTitle()
        title.headerAppeared(in: 0, current: 0)
        title.headerDisappeared(from: 0, current: 0)

        // The reader is pushed and popped; the heading row, off screen, reports nothing.

        #expect(title.showsTitle(listShown: true, current: 0))
    }

    /// Another state of the pane (an empty list, a search with no results) shows its own heading,
    /// whatever the list reported last.
    @Test
    func noTitleWhileTheListIsNotTheContentShown() {
        var title = BarTitle()
        title.headerAppeared(in: 0, current: 0)
        title.headerDisappeared(from: 0, current: 0)

        #expect(!title.showsTitle(listShown: false, current: 0))
    }

    /// A rebuilt list is a new owner: the old one's `onDisappear`, arriving after the new list's
    /// heading came on screen, must not put the title over the visible heading.
    @Test
    func aLateReportFromARebuiltListIsIgnored() {
        var title = BarTitle()
        title.headerAppeared(in: 0, current: 0)

        title.headerAppeared(in: 1, current: 1)
        title.headerDisappeared(from: 0, current: 1)

        #expect(!title.showsTitle(listShown: true, current: 1))
    }

    /// A report from the previous list does not carry over to the rebuilt one.
    @Test
    func theRebuiltListStartsWithNoTitle() {
        var title = BarTitle()
        title.headerAppeared(in: 0, current: 0)
        title.headerDisappeared(from: 0, current: 0)

        #expect(!title.showsTitle(listShown: true, current: 1))
    }

    /// A rebuilt list whose heading leaves the screen before ever reporting it on screen (a restored
    /// selection scrolled into view at once) still gets the title.
    @Test
    func aRebuiltListWhoseHeadingOnlyDisappearsShowsTheTitle() {
        var title = BarTitle()
        title.headerAppeared(in: 0, current: 0)

        title.headerDisappeared(from: 1, current: 1)

        #expect(title.showsTitle(listShown: true, current: 1))
    }
}
