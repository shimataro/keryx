import KeryxShared
import Testing

/// Covers the sealed-type comparison/keying functions in `FeedListSelectionEquality.swift`, whose
/// `switch`es fall back to `default: false` — a real Kotlin subtype the compiler doesn't force this
/// file to update for would silently compare as "not equal" instead of failing to build, and
/// `feedListRowSelectionKey`'s key space needs to stay collision-free by hand.
@Suite
struct FeedListSelectionEqualityTests {
    // MARK: - articleFiltersEqual

    @Test
    func sameCaseAndPayloadIsEqual() {
        #expect(articleFiltersEqual(ArticleFilterAll(), ArticleFilterAll()))
        #expect(articleFiltersEqual(ArticleFilterStarred(), ArticleFilterStarred()))
        #expect(articleFiltersEqual(ArticleFilterFeed(feedId: "f1"), ArticleFilterFeed(feedId: "f1")))
        #expect(articleFiltersEqual(ArticleFilterFolder(folderId: "d1"), ArticleFilterFolder(folderId: "d1")))
        #expect(articleFiltersEqual(ArticleFilterTag(tagId: "t1"), ArticleFilterTag(tagId: "t1")))
    }

    @Test
    func differentPayloadIsNotEqual() {
        #expect(!articleFiltersEqual(ArticleFilterFeed(feedId: "f1"), ArticleFilterFeed(feedId: "f2")))
        #expect(!articleFiltersEqual(ArticleFilterFolder(folderId: "d1"), ArticleFilterFolder(folderId: "d2")))
        #expect(!articleFiltersEqual(ArticleFilterTag(tagId: "t1"), ArticleFilterTag(tagId: "t2")))
    }

    @Test
    func differentCaseIsNotEqual() {
        #expect(!articleFiltersEqual(ArticleFilterAll(), ArticleFilterStarred()))
        #expect(!articleFiltersEqual(ArticleFilterFeed(feedId: "f1"), ArticleFilterFolder(folderId: "f1")))
    }

    // MARK: - feedListRowSelectionsEqual

    @Test
    func sameRowSelectionCaseAndPayloadIsEqual() {
        #expect(feedListRowSelectionsEqual(FeedListRowSelectionAll(), FeedListRowSelectionAll()))
        #expect(feedListRowSelectionsEqual(FeedListRowSelectionStarred(), FeedListRowSelectionStarred()))
        #expect(feedListRowSelectionsEqual(
            FeedListRowSelectionFeedInFolderGroup(feedId: "f1"),
            FeedListRowSelectionFeedInFolderGroup(feedId: "f1")
        ))
        #expect(feedListRowSelectionsEqual(
            FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1"),
            FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1")
        ))
        #expect(feedListRowSelectionsEqual(FeedListRowSelectionFolder(folderId: "d1"), FeedListRowSelectionFolder(folderId: "d1")))
        #expect(feedListRowSelectionsEqual(FeedListRowSelectionTag(tagId: "t1"), FeedListRowSelectionTag(tagId: "t1")))
    }

    @Test
    func sameFeedUnderFolderGroupAndTagIsNotEqual() {
        // The same feed rendered under its folder group vs. an expanded tag is two distinct rows.
        #expect(!feedListRowSelectionsEqual(
            FeedListRowSelectionFeedInFolderGroup(feedId: "f1"),
            FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1")
        ))
    }

    @Test
    func differentTagForTheSameFeedIsNotEqual() {
        #expect(!feedListRowSelectionsEqual(
            FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1"),
            FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t2")
        ))
    }

    // MARK: - feedListRowSelectionKey

    @Test
    func keyDistinguishesFolderGroupFromTagCopyOfTheSameFeed() {
        let folderKey = feedListRowSelectionKey(FeedListRowSelectionFeedInFolderGroup(feedId: "f1"))
        let tagKey = feedListRowSelectionKey(FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1"))
        #expect(folderKey != tagKey)
    }

    @Test
    func keyDistinguishesDifferentTagsForTheSameFeed() {
        let tagKeyA = feedListRowSelectionKey(FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1"))
        let tagKeyB = feedListRowSelectionKey(FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t2"))
        #expect(tagKeyA != tagKeyB)
    }

    @Test
    func keyIsStableForEquivalentInstances() {
        let a = feedListRowSelectionKey(FeedListRowSelectionFolder(folderId: "d1"))
        let b = feedListRowSelectionKey(FeedListRowSelectionFolder(folderId: "d1"))
        #expect(a == b)
    }

    // MARK: - dropBoundariesEqual

    @Test
    func bothNilIsEqual() {
        #expect(dropBoundariesEqual(nil, nil))
    }

    @Test
    func oneNilIsNotEqual() {
        #expect(!dropBoundariesEqual(DropBoundaryAppendFolders.shared, nil))
        #expect(!dropBoundariesEqual(nil, DropBoundaryAppendFolders.shared))
    }

    @Test
    func sameCaseAndPayloadDropBoundaryIsEqual() {
        #expect(dropBoundariesEqual(DropBoundaryBeforeFeed(feedId: "f1"), DropBoundaryBeforeFeed(feedId: "f1")))
        #expect(dropBoundariesEqual(DropBoundaryAppendFeeds(folderId: "d1"), DropBoundaryAppendFeeds(folderId: "d1")))
        #expect(dropBoundariesEqual(DropBoundaryAppendFeeds(folderId: nil), DropBoundaryAppendFeeds(folderId: nil)))
        #expect(dropBoundariesEqual(DropBoundaryBeforeFolder(folderId: "d1"), DropBoundaryBeforeFolder(folderId: "d1")))
        #expect(dropBoundariesEqual(DropBoundaryAppendFolders.shared, DropBoundaryAppendFolders.shared))
    }

    @Test
    func differentPayloadDropBoundaryIsNotEqual() {
        #expect(!dropBoundariesEqual(DropBoundaryBeforeFeed(feedId: "f1"), DropBoundaryBeforeFeed(feedId: "f2")))
        #expect(!dropBoundariesEqual(DropBoundaryAppendFeeds(folderId: "d1"), DropBoundaryAppendFeeds(folderId: nil)))
    }

    @Test
    func differentCaseDropBoundaryIsNotEqual() {
        #expect(!dropBoundariesEqual(DropBoundaryBeforeFeed(feedId: "f1"), DropBoundaryBeforeFolder(folderId: "f1")))
    }

    // MARK: - feedListRowSelection(forKey:in:)

    private let orderedRows: [FeedListRowSelection] = [
        FeedListRowSelectionAll(),
        FeedListRowSelectionStarred(),
        FeedListRowSelectionFolder(folderId: "d1"),
        FeedListRowSelectionFeedInFolderGroup(feedId: "f1"),
        FeedListRowSelectionTag(tagId: "t1"),
        FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1"),
    ]

    @Test
    func everyRowIsFoundByItsOwnKey() {
        for row in orderedRows {
            let found = feedListRowSelection(forKey: feedListRowSelectionKey(row), in: orderedRows)
            #expect(found.map { feedListRowSelectionsEqual($0, row) } == true)
        }
    }

    @Test
    func sameFeedUnderFolderAndTagResolvesToTheRightCopy() {
        let inTag = feedListRowSelection(forKey: "feed-in-tag:t1:f1", in: orderedRows)
        #expect(inTag.map { feedListRowSelectionsEqual($0, FeedListRowSelectionFeedInTag(feedId: "f1", tagId: "t1")) } == true)
        let inFolder = feedListRowSelection(forKey: "feed-in-folder:f1", in: orderedRows)
        #expect(inFolder.map { feedListRowSelectionsEqual($0, FeedListRowSelectionFeedInFolderGroup(feedId: "f1")) } == true)
    }

    @Test
    func unknownKeyIsNil() {
        #expect(feedListRowSelection(forKey: "folder:missing", in: orderedRows) == nil)
        #expect(feedListRowSelection(forKey: "all", in: []) == nil)
    }
}
