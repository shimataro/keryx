import Testing

/// `SidebarIdRemap` tells whether a row key held from before a sync merge re-keyed its tag or folder
/// and the key the row has now name the same row — so an inline rename survives the re-key.
@Suite
struct SidebarIdRemapTests {
    private let remap = SidebarIdRemap(tags: ["t-old": "t-new"], folders: ["d-old": "d-new"])

    @Test
    func aMovedTagOrFolderKeyIsBroughtUpToDate() {
        #expect(remap.current("tag:t-old") == "tag:t-new")
        #expect(remap.current("folder:d-old") == "folder:d-new")
    }

    @Test
    func aKeyNothingMovedIsUnchanged() {
        #expect(remap.current("tag:t-new") == "tag:t-new")
        #expect(remap.current("tag:other") == "tag:other")
        #expect(remap.current("folder:d-new") == "folder:d-new")
        #expect(remap.current("all") == "all")
        #expect(remap.current("feed-in-folder:t-old") == "feed-in-folder:t-old")
    }

    @Test
    func aTagNestedFeedKeyFollowsItsTagButKeepsItsFeed() {
        #expect(remap.current("feed-in-tag:t-old:f1") == "feed-in-tag:t-new:f1")
        #expect(remap.current("feed-in-tag:other:t-old") == "feed-in-tag:other:t-old")
    }

    @Test
    func aTagIdAndAFolderIdOfTheSameTextAreNotConfused() {
        let both = SidebarIdRemap(tags: ["x": "tx"], folders: ["x": "fx"])
        #expect(both.current("tag:x") == "tag:tx")
        #expect(both.current("folder:x") == "folder:fx")
    }

    @Test
    func theOldAndTheNewKeyAreTheSameRowWhicheverTheListsUse() {
        #expect(remap.sameRow("tag:t-old", "tag:t-new"))
        #expect(remap.sameRow("tag:t-new", "tag:t-old"))
        #expect(!remap.sameRow("tag:t-old", "tag:other"))
        #expect(!SidebarIdRemap.empty.sameRow("tag:t-old", "tag:t-new"))
    }

    @Test
    func aRowIsFoundInTheKeysUnderEitherId() {
        #expect(remap.contains(["all", "tag:t-new"], row: "tag:t-old"))
        #expect(remap.contains(["all", "tag:t-old"], row: "tag:t-new"))
        #expect(!remap.contains(["all", "tag:other"], row: "tag:t-old"))
        #expect(remap.match(in: ["all", "tag:t-new"], for: "tag:t-old") == "tag:t-new")
        #expect(remap.match(in: ["all"], for: "tag:t-old") == nil)
    }
}
