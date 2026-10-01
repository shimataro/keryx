import KeryxShared
import Testing

/// Covers `ReaderPagerIndex`, the reader pager's per-emission ids and positions.
@Suite
struct ReaderPagerIndexTests {
    private func row(_ id: String, isRead: Int64 = 0) -> ArticleListRow {
        ArticleListRow(
            id: id,
            feed_id: "f1",
            title: "Title",
            url: "https://example.com/\(id)",
            published_at: nil,
            created_at: 0,
            is_read: isRead,
            is_starred: 0
        )
    }

    @Test
    func indexesRowsInListOrder() {
        let index = ReaderPagerIndex.build([row("a"), row("b"), row("c")], reusing: .empty)
        #expect(index.ids == ["a", "b", "c"])
        #expect(index.indexById == ["a": 0, "b": 1, "c": 2])
    }

    @Test
    func emptyRowsBuildTheEmptyIndex() {
        let index = ReaderPagerIndex.build([], reusing: ReaderPagerIndex(ids: ["a"]))
        #expect(index.ids.isEmpty)
        #expect(index.indexById.isEmpty)
        #expect(index == .empty)
    }

    @Test
    func unchangedIdsReuseThePreviousIndex() {
        let previous = ReaderPagerIndex.build([row("a"), row("b")], reusing: .empty)
        // Same ids, new read state: the index itself does not change.
        let next = ReaderPagerIndex.build([row("a", isRead: 1), row("b", isRead: 1)], reusing: previous)
        #expect(next == previous)
        #expect(next.indexById == previous.indexById)
    }

    @Test
    func reorderedIdsRebuildTheIndex() {
        let previous = ReaderPagerIndex.build([row("a"), row("b")], reusing: .empty)
        let next = ReaderPagerIndex.build([row("b"), row("a")], reusing: previous)
        #expect(next != previous)
        #expect(next.ids == ["b", "a"])
        #expect(next.indexById == ["b": 0, "a": 1])
    }

    @Test
    func indexByIdIsConsistentWithIds() {
        let index = ReaderPagerIndex(ids: ["x", "y", "z"])
        for (i, id) in index.ids.enumerated() {
            #expect(index.indexById[id] == i)
        }
    }

    @Test
    func repeatedIdKeepsItsLastPosition() {
        let index = ReaderPagerIndex(ids: ["a", "b", "a"])
        #expect(index.indexById == ["a": 2, "b": 1])
    }

    @Test
    func buildsOffTheMainActor() async {
        let index = await ReaderPagerIndex.buildInBackground([row("a"), row("b")], reusing: .empty)
        #expect(index.ids == ["a", "b"])
        #expect(index.indexById == ["a": 0, "b": 1])
    }
}
