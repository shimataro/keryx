import KeryxShared
import Testing

/// Covers `FeedStructureKey` / `FeedStructureTracker`, which decide whether a `feeds` emission makes
/// `HomeObservable` rebuild the sidebar and everything else it derives from the feed list.
@Suite
struct FeedStructureKeyTests {
    private func feed(
        _ id: String = "f1",
        title: String = "Feed",
        etag: String? = nil,
        lastModified: String? = nil,
        errorCount: Int64 = 0,
        lastError: String? = nil,
        customTitle: String? = nil,
        faviconUrl: String? = nil,
        siteUrl: String? = nil,
        folderId: String? = nil,
        sortOrder: Int64 = 0,
        updatedAt: Int64 = 0
    ) -> Feeds {
        Feeds(
            id: id, url: "https://example.com/\(id)", site_url: siteUrl, title: title, description: nil,
            favicon_url: faviconUrl, etag: etag, last_modified: lastModified, error_count: errorCount,
            last_error: lastError, custom_title: customTitle, folder_id: folderId, deleted_at: nil,
            updated_at: updatedAt, created_at: 0, sort_order: sortOrder, folder_updated_at: nil,
            sort_order_updated_at: nil, custom_title_updated_at: nil, deleted_updated_at: nil
        )
    }

    @Test
    func ignoresTheFieldsOnlyARefreshRewrites() {
        let base = FeedStructureKey(feed())
        #expect(FeedStructureKey(feed(etag: "\"v2\"")) == base)
        #expect(FeedStructureKey(feed(lastModified: "Wed, 30 Sep 2026 00:00:00 GMT")) == base)
        #expect(FeedStructureKey(feed(updatedAt: 1_000)) == base)
    }

    @Test
    func changesWithEveryFieldTheSidebarReads() {
        let base = FeedStructureKey(feed())
        #expect(FeedStructureKey(feed(title: "Renamed")) != base)
        #expect(FeedStructureKey(feed(customTitle: "Mine")) != base)
        #expect(FeedStructureKey(feed(faviconUrl: "https://example.com/f.ico")) != base)
        #expect(FeedStructureKey(feed(errorCount: 1)) != base)
        #expect(FeedStructureKey(feed(lastError: ConstantsKt.FEED_ERROR_REASON_GONE)) != base)
        #expect(FeedStructureKey(feed(siteUrl: "https://example.com")) != base)
        #expect(FeedStructureKey(feed(folderId: "d1")) != base)
        #expect(FeedStructureKey(feed(sortOrder: 5)) != base)
        #expect(FeedStructureKey(feed("f2")) != base)
    }

    @Test
    func trackerAcceptsOnlyAStructuralChange() {
        var tracker = FeedStructureTracker()
        // The initial empty emission matches the initial (empty) derived state.
        var changed = tracker.accept([])
        #expect(!changed)
        changed = tracker.accept([feed("a"), feed("b")])
        #expect(changed)
        // A refresh rewriting only etag / updated_at: nothing to rebuild.
        changed = tracker.accept([feed("a", etag: "x", updatedAt: 9), feed("b", lastModified: "y")])
        #expect(!changed)
        changed = tracker.accept([feed("a", errorCount: 1), feed("b")])
        #expect(changed)
        // Order is structure: the sidebar is built in emission order.
        changed = tracker.accept([feed("b"), feed("a", errorCount: 1)])
        #expect(changed)
        changed = tracker.accept([feed("b")])
        #expect(changed)
        #expect(tracker.keys.map(\.id) == ["b"])
    }
}
