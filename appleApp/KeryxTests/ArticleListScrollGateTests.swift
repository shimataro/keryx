import Testing

/// `ArticleListScrollGate` keeps the iOS article list's scrolls away from a list that is not on
/// screen, and carries out only the latest one when the list comes back.
@Suite
struct ArticleListScrollGateTests {
    private typealias Target = ArticleListScrollGate.Target

    private func input(visible: Bool, generation: Int = 0) -> ArticleListScrollGate.Input {
        ArticleListScrollGate.Input(isVisible: visible, remountGeneration: generation)
    }

    private func anyRow(_: String) -> Bool { true }

    private func visibleGate() -> ArticleListScrollGate {
        var gate = ArticleListScrollGate()
        _ = gate.update(input(visible: true), rowExists: anyRow)
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
        _ = gate.update(input(visible: false), rowExists: anyRow)

        #expect(gate.request(Target(rowId: "a"), rowExists: anyRow) == nil)
        #expect(gate.update(input(visible: false), rowExists: anyRow) == nil)
        #expect(gate.update(input(visible: true), rowExists: anyRow) == Target(rowId: "a"))
    }

    @Test
    func onlyTheLatestHeldScrollIsCarriedOut() {
        var gate = ArticleListScrollGate()
        _ = gate.request(Target(rowId: "a"), rowExists: anyRow)
        _ = gate.request(Target(rowId: "b", onlyIfOffScreen: true), rowExists: anyRow)

        #expect(gate.update(input(visible: true), rowExists: anyRow) == Target(rowId: "b", onlyIfOffScreen: true))
    }

    @Test
    func aHeldScrollIsCarriedOutOnce() {
        var gate = ArticleListScrollGate()
        _ = gate.request(Target(rowId: "a"), rowExists: anyRow)
        _ = gate.update(input(visible: true), rowExists: anyRow)

        #expect(gate.update(input(visible: false), rowExists: anyRow) == nil)
        #expect(gate.update(input(visible: true), rowExists: anyRow) == nil)
    }

    @Test
    func aHeldScrollWhoseRowIsGoneIsDropped() {
        var gate = ArticleListScrollGate()
        _ = gate.request(Target(rowId: "a"), rowExists: anyRow)

        #expect(gate.update(input(visible: true), rowExists: { _ in false }) == nil)
        #expect(gate.update(input(visible: false), rowExists: anyRow) == nil)
        #expect(gate.update(input(visible: true), rowExists: anyRow) == nil)
    }

    @Test
    func aRebuiltListDropsTheHeldScroll() {
        var gate = ArticleListScrollGate()
        _ = gate.update(input(visible: false, generation: 1), rowExists: anyRow)
        _ = gate.request(Target(rowId: "a"), rowExists: anyRow)

        #expect(gate.update(input(visible: true, generation: 2), rowExists: anyRow) == nil)
    }

    @Test
    func theFirstUpdateIsNotARebuild() {
        var gate = ArticleListScrollGate()
        _ = gate.request(Target(rowId: "a"), rowExists: anyRow)

        #expect(gate.update(input(visible: true, generation: 3), rowExists: anyRow) == Target(rowId: "a"))
    }
}
