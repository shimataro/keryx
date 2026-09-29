import Testing

/// `CompactSidebarSelection` keeps a collapsed sidebar's rows behaving as navigation links — the
/// current row included, which is what previously stopped re-opening the same list after popping back.
@Suite
struct CompactSidebarSelectionTests {
    @Test
    func displayedKeyIsNoneWhileTheSidebarIsTopmost() {
        #expect(CompactSidebarSelection.displayedKey(selectedKey: "all", sidebarIsTopmost: true) == nil)
    }

    @Test
    func displayedKeyIsTheSelectionOtherwise() {
        #expect(CompactSidebarSelection.displayedKey(selectedKey: "all", sidebarIsTopmost: false) == "all")
    }

    @Test
    func tappingTheCurrentRowNavigatesWithoutChangingTheFilter() {
        let result = CompactSidebarSelection.tap(key: "all", selectedKey: "all")
        #expect(!result.changesFilter)
        #expect(result.navigates)
    }

    @Test
    func tappingAnotherRowChangesTheFilterAndNavigates() {
        let result = CompactSidebarSelection.tap(key: "starred", selectedKey: "all")
        #expect(result.changesFilter)
        #expect(result.navigates)
    }
}
