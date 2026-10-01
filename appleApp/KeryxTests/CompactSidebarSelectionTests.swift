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

/// `CompactArticleSelection` drops the collapsed article list's selection from the screen once the
/// reader is popped, as iOS lists do.
@Suite
struct CompactArticleSelectionTests {
    @Test
    func displayedIdIsNoneWhileTheArticleListIsTopmost() {
        #expect(CompactArticleSelection.displayedId(selectedId: "a1", articleListIsTopmost: true) == nil)
    }

    @Test
    func displayedIdIsTheSelectionOtherwise() {
        #expect(CompactArticleSelection.displayedId(selectedId: "a1", articleListIsTopmost: false) == "a1")
        #expect(CompactArticleSelection.displayedId(selectedId: nil, articleListIsTopmost: false) == nil)
    }
}
