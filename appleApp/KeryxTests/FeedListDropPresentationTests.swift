import Foundation
import KeryxShared
import Testing

/// Covers the pure drop-feedback rules in `FeedListDropPresentation.swift` that the sidebar's
/// `DropDelegate` builds its macOS drag feedback from. Which action a drop applies is the shared
/// `resolveFeedListDropAction`'s job and is tested on the Kotlin side, not here.
@Suite
struct FeedListDropPresentationTests {
    // MARK: - feedListDropFeedback

    @Test
    func onlyAFeedIsDroppedOntoARow() {
        #expect(feedListDropFeedback(for: .feed) == .dropOn)
        #expect(feedListDropFeedback(for: .folder) == .invalid)
    }

    // MARK: - FeedListInsertGroup.accepts

    @Test
    func eachGroupAcceptsOnlyItsOwnKind() {
        #expect(FeedListInsertGroup.feeds(folderId: "d1", feedIds: []).accepts(.feed))
        #expect(!FeedListInsertGroup.feeds(folderId: "d1", feedIds: []).accepts(.folder))
        #expect(FeedListInsertGroup.folders(folderIds: []).accepts(.folder))
        #expect(!FeedListInsertGroup.folders(folderIds: []).accepts(.feed))
    }

    // MARK: - feedListInsertTarget

    private func feedRowTarget(_ insert: (target: FeedListDropTarget, half: FeedListRowHalf)?) -> (String, FeedListRowHalf)? {
        guard let insert, let row = insert.target as? FeedListDropTargetFeedRow else { return nil }
        return (row.feedId, insert.half)
    }

    private func folderHeaderTarget(_ insert: (target: FeedListDropTarget, half: FeedListRowHalf)?) -> (String, FeedListRowHalf)? {
        guard let insert, let header = insert.target as? FeedListDropTargetFolderHeader else { return nil }
        return (header.folderId, insert.half)
    }

    @Test
    func insertingBeforeAFeedIsThatFeedsTopHalf() {
        let group = FeedListInsertGroup.feeds(folderId: "d1", feedIds: ["f1", "f2", "f3"])
        #expect(feedRowTarget(feedListInsertTarget(in: group, at: 0)).map { $0 == ("f1", .top) } == true)
        #expect(feedRowTarget(feedListInsertTarget(in: group, at: 2)).map { $0 == ("f3", .top) } == true)
    }

    @Test
    func insertingAtTheEndIsTheLastFeedsBottomHalf() {
        let group = FeedListInsertGroup.feeds(folderId: nil, feedIds: ["f1", "f2"])
        #expect(feedRowTarget(feedListInsertTarget(in: group, at: 2)).map { $0 == ("f2", .bottom) } == true)
    }

    @Test
    func insertingIntoAnEmptyGroupTargetsItsHeader() {
        let folder = feedListInsertTarget(in: .feeds(folderId: "d1", feedIds: []), at: 0)
        #expect(folderHeaderTarget(folder).map { $0 == ("d1", .top) } == true)
        let unfoldered = feedListInsertTarget(in: .feeds(folderId: nil, feedIds: []), at: 0)
        #expect(unfoldered?.target is FeedListDropTargetNoFolderHeader)
    }

    @Test
    func insertingAmongFoldersTargetsFolderHeaders() {
        let group = FeedListInsertGroup.folders(folderIds: ["d1", "d2"])
        #expect(folderHeaderTarget(feedListInsertTarget(in: group, at: 1)).map { $0 == ("d2", .top) } == true)
        #expect(folderHeaderTarget(feedListInsertTarget(in: group, at: 2)).map { $0 == ("d2", .bottom) } == true)
        #expect(feedListInsertTarget(in: .folders(folderIds: []), at: 0) == nil)
    }

    // MARK: - feedListInsertTarget through the shared rules

    /// Folder `d1` holds `f1`, `f2`; `f3` is unfoldered; folders are `d1`, `d2`. A Kotlin `null`
    /// map value is `NSNull` on this side.
    private let dropIndex = FeedListDropIndex(
        folderIdOfFeed: ["f1": "d1", "f2": "d1", "f3": NSNull()],
        nextFeedInGroup: ["f1": "f2", "f2": NSNull(), "f3": NSNull()],
        firstFeedIdOfGroup: ["d1": "f1", "d2": NSNull(), NSNull(): "f3"],
        nextFolderId: ["d1": "d2", "d2": NSNull()]
    )

    private func resolvedMove(_ feedId: String, into group: FeedListInsertGroup, at offset: Int) -> FeedListDropActionMoveFeed? {
        guard let insert = feedListInsertTarget(in: group, at: offset) else { return nil }
        return FeedListDragKt.resolveFeedListDropAction(
            item: FeedListDraggedItemFeed(feedId: feedId), target: insert.target, half: insert.half, index: dropIndex
        ) as? FeedListDropActionMoveFeed
    }

    @Test
    func movingAFeedToTheEndOfAFolderAppends() {
        let move = resolvedMove("f3", into: .feeds(folderId: "d1", feedIds: ["f1", "f2"]), at: 2)
        #expect(move?.folderId == "d1")
        #expect(move != nil && move?.targetFeedId == nil)
    }

    @Test
    func movingAFeedBetweenFeedsInsertsBeforeTheNextOne() {
        let move = resolvedMove("f3", into: .feeds(folderId: "d1", feedIds: ["f1", "f2"]), at: 1)
        #expect(move?.folderId == "d1")
        #expect(move?.targetFeedId == "f2")
    }

    @Test
    func movingAFeedIntoAnEmptyFolderLandsInIt() {
        let move = resolvedMove("f3", into: .feeds(folderId: "d2", feedIds: []), at: 0)
        #expect(move?.folderId == "d2")
        #expect(move != nil && move?.targetFeedId == nil)
    }

    @Test
    func reorderingAFolderToTheEndAppends() {
        guard let insert = feedListInsertTarget(in: .folders(folderIds: ["d1", "d2"]), at: 2) else {
            Issue.record("no insert target")
            return
        }
        let reorder = FeedListDragKt.resolveFeedListDropAction(
            item: FeedListDraggedItemFolder(folderId: "d1"), target: insert.target, half: insert.half, index: dropIndex
        ) as? FeedListDropActionReorderFolder
        #expect(reorder?.draggedFolderId == "d1")
        #expect(reorder != nil && reorder?.targetFolderId == nil)
    }

    // MARK: - springLoadingDelay

    @Test
    func unsetSpringLoadingUsesSystemDefault() {
        #expect(springLoadingDelay(lookup: { _ in nil }) == .milliseconds(500))
    }

    @Test
    func configuredDelayIsHonored() {
        let values: [String: Any] = ["com.apple.springing.enabled": true, "com.apple.springing.delay": 1.25]
        #expect(springLoadingDelay(lookup: { values[$0] }) == .milliseconds(1250))
    }

    @Test
    func delayStoredAsStringIsHonored() {
        #expect(springLoadingDelay(lookup: { $0 == "com.apple.springing.delay" ? "0.8" : nil }) == .milliseconds(800))
    }

    @Test
    func disabledSpringLoadingReturnsNil() {
        let values: [String: Any] = ["com.apple.springing.enabled": false, "com.apple.springing.delay": 0.5]
        #expect(springLoadingDelay(lookup: { values[$0] }) == nil)
    }
}
