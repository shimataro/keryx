import Testing

/// `ArticleListTopReset` decides when the iOS article list goes back to the top after a filter
/// switch: once the new filter's rows are on screen, never at the previous filter's rows (a scroll
/// aimed at them can be out of range once the new rows land, which crashes `UICollectionView`).
@Suite
struct ArticleListTopResetTests {
    private func input(_ filterKey: String, match: Bool, first: String?) -> ArticleListTopReset.Input {
        ArticleListTopReset.Input(filterKey: filterKey, rowsMatchFilter: match, firstRowId: first)
    }

    @Test
    func theFirstCompositionIsNotASwitch() {
        var reset = ArticleListTopReset()

        #expect(reset.update(input("all", match: true, first: "a1")) == nil)
    }

    @Test
    func theFirstRowsOfTheFilterTheListStartedUnderAreNotASwitch() {
        var reset = ArticleListTopReset()
        _ = reset.update(input("all", match: false, first: nil))

        #expect(reset.update(input("all", match: true, first: "a1")) == nil)
    }

    @Test
    func aSwitchWaitsWhileThePreviousFiltersRowsAreOnScreen() {
        var reset = ArticleListTopReset()
        _ = reset.update(input("all", match: true, first: "a1"))

        #expect(reset.update(input("folder:d1", match: false, first: "a1")) == nil)
    }

    @Test
    func aSwitchScrollsToTheNewFiltersFirstRowOnceItsRowsLand() {
        var reset = ArticleListTopReset()
        _ = reset.update(input("all", match: true, first: "a1"))
        _ = reset.update(input("folder:d1", match: false, first: "a1"))

        #expect(reset.update(input("folder:d1", match: true, first: "b1")) == "b1")
    }

    /// The rows can land before the filter does: they are already the new filter's at the switch.
    @Test
    func aSwitchWhoseRowsAlreadyLandedScrollsAtOnce() {
        var reset = ArticleListTopReset()
        _ = reset.update(input("all", match: true, first: "a1"))

        #expect(reset.update(input("folder:d1", match: true, first: "b1")) == "b1")
    }

    /// A folder holding every feed shows All's rows: only the match flag moves.
    @Test
    func aSwitchThatLeavesTheRowsUnchangedStillScrolls() {
        var reset = ArticleListTopReset()
        _ = reset.update(input("all", match: true, first: "a1"))
        _ = reset.update(input("folder:d1", match: false, first: "a1"))

        #expect(reset.update(input("folder:d1", match: true, first: "a1")) == "a1")
    }

    @Test
    func aSwitchToAnEmptyListScrollsNothingAndIsDone() {
        var reset = ArticleListTopReset()
        _ = reset.update(input("all", match: true, first: "a1"))
        _ = reset.update(input("folder:empty", match: false, first: "a1"))

        #expect(reset.update(input("folder:empty", match: true, first: nil)) == nil)
        // Articles arriving later in that folder do not pull the list back to the top.
        #expect(reset.update(input("folder:empty", match: true, first: "c1")) == nil)
    }

    @Test
    func rowsLandingAfterTheSwitchWasCarriedOutDoNotScroll() {
        var reset = ArticleListTopReset()
        _ = reset.update(input("all", match: true, first: "a1"))
        _ = reset.update(input("folder:d1", match: true, first: "b1"))

        #expect(reset.update(input("folder:d1", match: true, first: "b0")) == nil)
    }

    @Test
    func backToBackSwitchesScrollOnlyForTheLastFilter() {
        var reset = ArticleListTopReset()
        _ = reset.update(input("all", match: true, first: "a1"))
        _ = reset.update(input("folder:d1", match: false, first: "a1"))

        #expect(reset.update(input("feed:f2", match: false, first: "a1")) == nil)
        #expect(reset.update(input("feed:f2", match: true, first: "c1")) == "c1")
    }
}
