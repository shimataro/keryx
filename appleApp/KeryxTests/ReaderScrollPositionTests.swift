import Testing

@Suite
struct ReaderScrollPositionTests {
    @Test
    func emptyStorageHoldsNoPosition() {
        #expect(ReaderScrollPosition.stored(articleId: "", fraction: 0.5) == nil)
        #expect(ReaderScrollPosition.stored(articleId: "a", fraction: 0.5) == ReaderScrollPosition(articleId: "a", fraction: 0.5))
    }

    @Test
    func theSelectedArticlesOwnPageIsRestored() {
        let position = ReaderScrollPosition(articleId: "a", fraction: 0.5)
        #expect(position.initialFraction(forPage: "a", selectedId: "a") == 0.5)
    }

    @Test
    func aNeighbourPageIsNotRestored() {
        let position = ReaderScrollPosition(articleId: "a", fraction: 0.5)
        #expect(position.initialFraction(forPage: "b", selectedId: "a") == nil)
    }

    @Test
    func thePageIsNotRestoredOnceAnotherArticleIsSelected() {
        let position = ReaderScrollPosition(articleId: "a", fraction: 0.5)
        #expect(position.initialFraction(forPage: "a", selectedId: "b") == nil)
        #expect(position.initialFraction(forPage: "a", selectedId: nil) == nil)
    }

    @Test
    func theTopOrAnInvalidFractionRestoresNothingAndTooFarIsClamped() {
        #expect(ReaderScrollPosition(articleId: "a", fraction: 0).initialFraction(forPage: "a", selectedId: "a") == nil)
        #expect(ReaderScrollPosition(articleId: "a", fraction: .nan).initialFraction(forPage: "a", selectedId: "a") == nil)
        #expect(ReaderScrollPosition(articleId: "a", fraction: 1.4).initialFraction(forPage: "a", selectedId: "a") == 1)
    }
}
