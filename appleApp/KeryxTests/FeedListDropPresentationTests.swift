import KeryxShared
import Testing

/// Covers the pure drop-feedback rules in `FeedListDropPresentation.swift` that the sidebar's
/// `DropDelegate` builds its macOS drag feedback from. Which action a drop applies is the shared
/// `resolveFeedListDropAction`'s job and is tested on the Kotlin side, not here.
@Suite
struct FeedListDropPresentationTests {
    // MARK: - feedListDropFeedback

    @Test
    func noBoundaryAndNoTagIsInvalid() {
        #expect(feedListDropFeedback(isFeedDrag: false, hoverKey: .folder("d1"), boundary: nil, attachTagId: nil) == .invalid)
        #expect(feedListDropFeedback(isFeedDrag: true, hoverKey: .feed("f1"), boundary: nil, attachTagId: nil) == .invalid)
    }

    @Test
    func feedOverHeaderIsDropOn() {
        #expect(feedListDropFeedback(
            isFeedDrag: true, hoverKey: .folder("d1"), boundary: DropBoundaryBeforeFeed(feedId: "f1"), attachTagId: nil
        ) == .dropOn)
        #expect(feedListDropFeedback(
            isFeedDrag: true, hoverKey: .noFolder, boundary: DropBoundaryAppendFeeds(folderId: nil), attachTagId: nil
        ) == .dropOn)
        #expect(feedListDropFeedback(isFeedDrag: true, hoverKey: .tag("t1"), boundary: nil, attachTagId: "t1") == .dropOn)
    }

    @Test
    func feedOverFeedRowIsInsertion() {
        #expect(feedListDropFeedback(
            isFeedDrag: true, hoverKey: .feed("f2"), boundary: DropBoundaryBeforeFeed(feedId: "f2"), attachTagId: nil
        ) == .insertion)
    }

    @Test
    func folderOverFolderHeaderIsInsertion() {
        #expect(feedListDropFeedback(
            isFeedDrag: false, hoverKey: .folder("d2"), boundary: DropBoundaryBeforeFolder(folderId: "d2"), attachTagId: nil
        ) == .insertion)
    }

    // MARK: - feedListRowHalf

    @Test
    func rowHalfSplitsAtMidpoint() {
        #expect(feedListRowHalf(locationY: 0, rowHeight: 20) == .top)
        #expect(feedListRowHalf(locationY: 9.9, rowHeight: 20) == .top)
        #expect(feedListRowHalf(locationY: 10, rowHeight: 20) == .bottom)
        #expect(feedListRowHalf(locationY: 19, rowHeight: 20) == .bottom)
    }

    @Test
    func unmeasuredRowResolvesToTop() {
        #expect(feedListRowHalf(locationY: 15, rowHeight: 0) == .top)
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
