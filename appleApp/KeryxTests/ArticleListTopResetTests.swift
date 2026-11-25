import Testing

/// `ArticleListTopReset` decides when the iOS article list goes back to the top after a filter
/// switch: once the new filter's rows are on screen, never at the previous filter's rows (a scroll
/// aimed at them can be out of range once the new rows land, which crashes `UICollectionView`).
@Suite
struct ArticleListTopResetTests {
    // Selection generations, named after the filter each test selects with them.
    private let all: Int64 = 0
    private let folderD1: Int64 = 1
    private let folderEmpty: Int64 = 1
    private let feedF2: Int64 = 2

    private func input(_ selectionKey: Int64, match: Bool, first: String?) -> ArticleListTopReset.Input {
        ArticleListTopReset.Input(selectionKey: selectionKey, rowsMatchFilter: match, firstRowId: first)
    }

    @Test
    func theFirstCompositionIsNotASwitch() {
        var reset = ArticleListTopReset()

        #expect(reset.update(input(all, match: true, first: "a1")) == nil)
    }

    @Test
    func theFirstRowsOfTheFilterTheListStartedUnderAreNotASwitch() {
        var reset = ArticleListTopReset()
        _ = reset.update(input(all, match: false, first: nil))

        #expect(reset.update(input(all, match: true, first: "a1")) == nil)
    }

    @Test
    func aSwitchWaitsWhileThePreviousFiltersRowsAreOnScreen() {
        var reset = ArticleListTopReset()
        _ = reset.update(input(all, match: true, first: "a1"))

        #expect(reset.update(input(folderD1, match: false, first: "a1")) == nil)
    }

    @Test
    func aSwitchScrollsToTheNewFiltersFirstRowOnceItsRowsLand() {
        var reset = ArticleListTopReset()
        _ = reset.update(input(all, match: true, first: "a1"))
        _ = reset.update(input(folderD1, match: false, first: "a1"))

        #expect(reset.update(input(folderD1, match: true, first: "b1")) == "b1")
    }

    /// The rows can land before the filter does: they are already the new filter's at the switch.
    @Test
    func aSwitchWhoseRowsAlreadyLandedScrollsAtOnce() {
        var reset = ArticleListTopReset()
        _ = reset.update(input(all, match: true, first: "a1"))

        #expect(reset.update(input(folderD1, match: true, first: "b1")) == "b1")
    }

    /// A folder holding every feed shows All's rows: only the match flag moves.
    @Test
    func aSwitchThatLeavesTheRowsUnchangedStillScrolls() {
        var reset = ArticleListTopReset()
        _ = reset.update(input(all, match: true, first: "a1"))
        _ = reset.update(input(folderD1, match: false, first: "a1"))

        #expect(reset.update(input(folderD1, match: true, first: "a1")) == "a1")
    }

    @Test
    func aSwitchToAnEmptyListScrollsNothingAndIsDone() {
        var reset = ArticleListTopReset()
        _ = reset.update(input(all, match: true, first: "a1"))
        _ = reset.update(input(folderEmpty, match: false, first: "a1"))

        #expect(reset.update(input(folderEmpty, match: true, first: nil)) == nil)
        // Articles arriving later in that folder do not pull the list back to the top.
        #expect(reset.update(input(folderEmpty, match: true, first: "c1")) == nil)
    }

    @Test
    func rowsLandingAfterTheSwitchWasCarriedOutDoNotScroll() {
        var reset = ArticleListTopReset()
        _ = reset.update(input(all, match: true, first: "a1"))
        _ = reset.update(input(folderD1, match: true, first: "b1"))

        #expect(reset.update(input(folderD1, match: true, first: "b0")) == nil)
    }

    @Test
    func backToBackSwitchesScrollOnlyForTheLastFilter() {
        var reset = ArticleListTopReset()
        _ = reset.update(input(all, match: true, first: "a1"))
        _ = reset.update(input(folderD1, match: false, first: "a1"))

        #expect(reset.update(input(feedF2, match: false, first: "a1")) == nil)
        #expect(reset.update(input(feedF2, match: true, first: "c1")) == "c1")
    }

    /// A merge re-keying the selected tag or folder changes the filter but keeps the generation: the
    /// rows briefly belong to the old id, then to the new one, and the list never scrolls.
    @Test
    func aReKeyedFilterUnderTheSameGenerationIsNotASwitch() {
        var reset = ArticleListTopReset()
        _ = reset.update(input(folderD1, match: true, first: "a1"))

        #expect(reset.update(input(folderD1, match: false, first: "a1")) == nil)
        #expect(reset.update(input(folderD1, match: true, first: "a1")) == nil)
    }
}
