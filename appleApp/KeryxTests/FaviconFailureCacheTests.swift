import Foundation
import Testing

/// Covers `FaviconFailureCache`, the favicon loader's negative cache: how long a failed URL is left
/// alone, and when it is tried again. The clock is passed in, so every case is deterministic.
@Suite
struct FaviconFailureCacheTests {
    private let start = Date(timeIntervalSinceReferenceDate: 1_000_000)
    private let url = "https://example.com/favicon.ico"

    @Test
    func anUnknownUrlIsNotSuppressed() {
        let cache = FaviconFailureCache(ttl: 60)
        #expect(!cache.isSuppressed(url, now: start))
    }

    @Test
    func aFailedUrlIsSuppressedUntilTheTtlElapses() {
        var cache = FaviconFailureCache(ttl: 60)
        cache.recordFailure(url, now: start)
        #expect(cache.isSuppressed(url, now: start))
        #expect(cache.isSuppressed(url, now: start.addingTimeInterval(59.9)))
        #expect(!cache.isSuppressed(url, now: start.addingTimeInterval(60)))
        #expect(!cache.isSuppressed(url, now: start.addingTimeInterval(3600)))
    }

    @Test
    func onlyTheFailedUrlIsSuppressed() {
        var cache = FaviconFailureCache(ttl: 60)
        cache.recordFailure(url, now: start)
        #expect(!cache.isSuppressed("https://example.com/other.ico", now: start))
    }

    @Test
    func failingAgainRestartsTheTtl() {
        var cache = FaviconFailureCache(ttl: 60)
        cache.recordFailure(url, now: start)
        cache.recordFailure(url, now: start.addingTimeInterval(70))
        #expect(cache.isSuppressed(url, now: start.addingTimeInterval(100)))
        #expect(!cache.isSuppressed(url, now: start.addingTimeInterval(130)))
    }

    @Test
    func aSuccessForgetsTheFailure() {
        var cache = FaviconFailureCache(ttl: 60)
        cache.recordFailure(url, now: start)
        cache.recordSuccess(url)
        #expect(!cache.isSuppressed(url, now: start))
        #expect(cache.count == 0)
    }

    @Test
    func recordingAFailureDropsExpiredEntries() {
        var cache = FaviconFailureCache(ttl: 60)
        cache.recordFailure("https://a.example/favicon.ico", now: start)
        cache.recordFailure("https://b.example/favicon.ico", now: start.addingTimeInterval(30))
        cache.recordFailure(url, now: start.addingTimeInterval(61))
        // a expired (61 s old), b is 31 s old and still suppressed.
        #expect(cache.count == 2)
        #expect(cache.isSuppressed("https://b.example/favicon.ico", now: start.addingTimeInterval(61)))
    }
}
