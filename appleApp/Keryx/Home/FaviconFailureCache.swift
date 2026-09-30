import Foundation

/// The favicon URLs whose last load failed, each remembered for `ttl` — `FaviconCache`'s negative
/// cache. Without it every row scrolled back into view, and every article row of the same feed,
/// re-requested a favicon that is missing or undecodable. After `ttl` the URL is tried again, so a
/// failure caused by a transient network error does not stick for the rest of the session.
///
/// The clock is passed in by the caller rather than read here, so the expiry is testable.
struct FaviconFailureCache {
    let ttl: TimeInterval
    private var failedAt: [String: Date] = [:]

    init(ttl: TimeInterval) {
        self.ttl = ttl
    }

    /// Whether `url` failed less than `ttl` ago, so loading it again should be skipped.
    func isSuppressed(_ url: String, now: Date) -> Bool {
        guard let failed = failedAt[url] else { return false }
        return now.timeIntervalSince(failed) < ttl
    }

    /// Remembers that loading `url` failed at `now`, dropping entries that have already expired so
    /// the table only ever holds the URLs currently suppressed.
    mutating func recordFailure(_ url: String, now: Date) {
        failedAt = failedAt.filter { now.timeIntervalSince($0.value) < ttl }
        failedAt[url] = now
    }

    /// Forgets a failure of `url` once it has loaded.
    mutating func recordSuccess(_ url: String) {
        failedAt[url] = nil
    }

    /// The number of URLs remembered, expired ones not yet dropped included.
    var count: Int { failedAt.count }
}
