/// Decides whether a reader page's document rebuild waits before starting (`ReaderPageView`).
///
/// Only a burst of article switches waits: a switch that lands within `window` of the previous one
/// (J/K held down, rapid clicks) waits that long, so the burst builds only the article it stops on —
/// each newer switch cancels the older one's wait. The first switch after a quiet spell builds at
/// once, and so does every rebuild that keeps the same article (a star or read toggle publishing a
/// new instance, a theme or font-size change), so a single click never pays the wait.
struct ReaderRebuildThrottle {
    /// How close together two article switches must be to count as one burst.
    static let window: Duration = .milliseconds(75)

    private var hasArticle = false
    private var lastArticleId: String?
    private var lastSwitchAt: ContinuousClock.Instant?

    /// How long a rebuild for `articleId` (nil for the blank state) at `now` waits before starting,
    /// or nil to start at once. Records the switch, so call it once per rebuild.
    mutating func delay(for articleId: String?, now: ContinuousClock.Instant) -> Duration? {
        if hasArticle && articleId == lastArticleId { return nil }
        let previousSwitchAt = lastSwitchAt
        hasArticle = true
        lastArticleId = articleId
        lastSwitchAt = now
        guard let previousSwitchAt, now - previousSwitchAt < Self.window else { return nil }
        return Self.window
    }
}
