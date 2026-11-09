import Testing

/// `ArticleListScrollGate` keeps the iOS article list's scrolls away from a list that is not on
/// screen, and carries out only the latest one when the list comes back.
@Suite
struct ArticleListScrollGateTests {
    private typealias Target = ArticleListScrollGate.Target

    private func input(canScroll: Bool, generation: Int = 0) -> ArticleListScrollGate.Input {
        ArticleListScrollGate.Input(canScroll: canScroll, remountGeneration: generation)
    }

    private func anyRow(_: String) -> Bool { true }

    private func visibleGate() -> ArticleListScrollGate {
        var gate = ArticleListScrollGate()
        _ = gate.update(input(canScroll: true))
        return gate
    }

    @Test
    func aListNotYetUpdatedHoldsAScroll() {
        var gate = ArticleListScrollGate()

        #expect(gate.request(Target(rowId: "a"), rowExists: anyRow) == nil)
    }

    @Test
    func aVisibleListScrollsAtOnce() {
        var gate = visibleGate()
        let target = Target(rowId: "a", onlyIfOffScreen: true)

        #expect(gate.request(target, rowExists: anyRow) == target)
    }

    @Test
    func aVisibleListIgnoresARowThatIsGone() {
        var gate = visibleGate()

        #expect(gate.request(Target(rowId: "a"), rowExists: { _ in false }) == nil)
    }

    @Test
    func aHiddenListHoldsTheScrollUntilItComesBack() {
        var gate = visibleGate()
        _ = gate.update(input(canScroll: false))

        #expect(gate.request(Target(rowId: "a"), rowExists: anyRow) == nil)
        #expect(gate.update(input(canScroll: false)) == nil)
        #expect(gate.update(input(canScroll: true)) == Target(rowId: "a"))
    }

    @Test
    func onlyTheLatestHeldScrollIsCarriedOut() {
        var gate = ArticleListScrollGate()
        _ = gate.request(Target(rowId: "a"), rowExists: anyRow)
        _ = gate.request(Target(rowId: "b", onlyIfOffScreen: true), rowExists: anyRow)

        #expect(gate.update(input(canScroll: true)) == Target(rowId: "b", onlyIfOffScreen: true))
    }

    @Test
    func aHeldScrollIsCarriedOutOnce() {
        var gate = ArticleListScrollGate()
        _ = gate.request(Target(rowId: "a"), rowExists: anyRow)
        _ = gate.update(input(canScroll: true))

        #expect(gate.update(input(canScroll: false)) == nil)
        #expect(gate.update(input(canScroll: true)) == nil)
    }

    @Test
    func aReleasedScrollWhoseRowIsGoneIsDropped() {
        var gate = ArticleListScrollGate()
        _ = gate.request(Target(rowId: "a"), rowExists: anyRow)

        let released = gate.update(input(canScroll: true))

        #expect(released == Target(rowId: "a"))
        #expect(gate.request(Target(rowId: "a"), rowExists: { _ in false }) == nil)
        #expect(gate.update(input(canScroll: false)) == nil)
        #expect(gate.update(input(canScroll: true)) == nil)
    }

    @Test
    func aRebuiltListDropsTheHeldScroll() {
        var gate = ArticleListScrollGate()
        _ = gate.update(input(canScroll: false, generation: 1))
        _ = gate.request(Target(rowId: "a"), rowExists: anyRow)

        #expect(gate.update(input(canScroll: true, generation: 2)) == nil)
    }

    @Test
    func theFirstUpdateIsNotARebuild() {
        var gate = ArticleListScrollGate()
        _ = gate.request(Target(rowId: "a"), rowExists: anyRow)

        #expect(gate.update(input(canScroll: true, generation: 3)) == Target(rowId: "a"))
    }
}
