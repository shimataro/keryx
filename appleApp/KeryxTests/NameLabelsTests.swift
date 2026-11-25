import KeryxShared
import Testing

/// `NameLabel` / `TagFolderLabelSet`: the native copy of the shared labels snapshot that every tag and
/// folder name on screen is rendered from.
@Suite
struct NameLabelsTests {
    private func tag(_ id: String, _ name: String) -> Tags {
        Tags(
            id: id, name: name, color: nil, sort_order: 0, deleted_at: nil, updated_at: 0, created_at: 0,
            name_updated_at: nil, color_updated_at: nil, sort_order_updated_at: nil, v2_id: nil
        )
    }

    private func folder(_ id: String, _ name: String) -> Folders {
        Folders(
            id: id, name: name, sort_order: 0, deleted_at: nil, updated_at: 0, created_at: 0,
            name_updated_at: nil, sort_order_updated_at: nil, v2_id: nil
        )
    }

    @Test
    func aUniquelyNamedRowShowsItsBareName() {
        #expect(nameWithOrdinal("Work", nil) == "Work")
        #expect(NameLabel(name: "Work", ordinal: nil).text == "Work")
    }

    /// The pattern comes from the String Catalog, which this logic-only bundle does not carry: the
    /// lookup falls back to the key, so only that the ordinal path is taken is asserted.
    @Test
    func aNumberedRowDoesNotShowItsBareName() {
        #expect(nameWithOrdinal("Work", 2) != "Work")
    }

    @Test
    func theKotlinLabelsAreCopiedWithNamesAndOrdinals() {
        let kotlin = NameOrdinalsKt.tagFolderLabels(
            tags: [tagRow("t1", "Work", 5), tagRow("t2", "Work", 9)],
            folders: [folderRow("d1", "Home", 1)]
        )
        let labels = TagFolderLabelSet(kotlin)
        #expect(labels.tags["t1"] == NameLabel(name: "Work", ordinal: nil))
        #expect(labels.tags["t2"] == NameLabel(name: "Work", ordinal: 2))
        #expect(labels.folders["d1"] == NameLabel(name: "Home", ordinal: nil))
    }

    @Test
    func aRowWithNoEntryShowsItsOwnNameBare() {
        let labels = TagFolderLabelSet(tags: ["t1": NameLabel(name: "Renamed", ordinal: 2)], folders: [:])
        #expect(labels.label(for: tag("t1", "Stale")) == NameLabel(name: "Renamed", ordinal: 2))
        #expect(labels.label(for: tag("t9", "Fresh")) == NameLabel(name: "Fresh", ordinal: nil))
        #expect(labels.name(of: folder("d9", "Fresh")) == "Fresh")
        #expect(TagFolderLabelSet.empty.name(of: tag("t1", "Solo")) == "Solo")
    }

    @Test
    func theResolvedArticleListNameCarriesTheSelectedRowsOrdinal() {
        let kotlin = NameOrdinalsKt.tagFolderLabels(
            tags: [tagRow("t1", "Work", 5), tagRow("t2", "Work", 9)], folders: []
        )
        let resolved = FeedListModelKt.articleListOrdinalName(
            filter: ArticleFilterTag(tagId: "t2"), feeds: [], labels: kotlin, allLabel: "All", starredLabel: "Starred"
        )
        #expect(NameLabel(resolved) == NameLabel(name: "Work", ordinal: 2))
        let missing = FeedListModelKt.articleListOrdinalName(
            filter: ArticleFilterTag(tagId: "gone"), feeds: [], labels: kotlin, allLabel: "All", starredLabel: "Starred"
        )
        #expect(NameLabel(missing) == NameLabel(name: "All", ordinal: nil))
    }

    private func tagRow(_ id: String, _ name: String, _ createdAt: Int64) -> Tags {
        Tags(
            id: id, name: name, color: nil, sort_order: 0, deleted_at: nil, updated_at: 0, created_at: createdAt,
            name_updated_at: nil, color_updated_at: nil, sort_order_updated_at: nil, v2_id: nil
        )
    }

    private func folderRow(_ id: String, _ name: String, _ createdAt: Int64) -> Folders {
        Folders(
            id: id, name: name, sort_order: 0, deleted_at: nil, updated_at: 0, created_at: createdAt,
            name_updated_at: nil, sort_order_updated_at: nil, v2_id: nil
        )
    }
}
