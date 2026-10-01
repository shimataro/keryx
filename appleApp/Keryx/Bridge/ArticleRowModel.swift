import KeryxShared
import SwiftUI

/// Search-highlight markers `FtsSearch` wraps around each match in `ArticleSearchResult.titleMarked`.
private let markStart: Character = "\u{0002}"
private let markEnd: Character = "\u{0003}"

/// One article-list row, resolved to plain Swift values once per `HomeViewModel` emission rather
/// than read through the Kotlin bridge — and re-formatted — on every body evaluation. Each property
/// access on a Kotlin object crosses the bridge (a `String` is copied every time), and the article
/// list reads several per row, so this is what keeps a large list's rendering off that path.
struct ArticleRowModel: Identifiable, Equatable, Sendable {
    /// The Kotlin row itself, for the actions that hand it back to `HomeViewModel`. Not part of
    /// equality: two emissions of the same article compare by what the row shows.
    let row: ArticleListRow
    let id: String
    /// `nil` for a blank title (the row shows the localized "no title" text instead).
    let title: String?
    /// `ArticleSearchResult.titleMarked` as given, kept to tell whether a rebuild can reuse this row.
    let markedTitle: String?
    /// The search-highlighted title, when this row is a search result with a non-blank marked title.
    let highlightedTitle: AttributedString?
    let feedTitle: String?
    let faviconUrl: String?
    let timestamp: String
    let isRead: Bool
    let isStarred: Bool
    let url: String
    /// `hasUsableUrl(url)` (Copy URL's rule), resolved once here rather than through the bridge on
    /// every body evaluation. Derived from `url`, so not compared.
    let hasUsableUrl: Bool
    /// `canOpenInBrowser(url)` (Open in Browser's stricter http(s) rule), resolved once for the same
    /// reason. Derived from `url`, so not compared.
    let canOpenInBrowser: Bool

    static func == (lhs: ArticleRowModel, rhs: ArticleRowModel) -> Bool {
        lhs.id == rhs.id
            && lhs.title == rhs.title
            && lhs.markedTitle == rhs.markedTitle
            && lhs.feedTitle == rhs.feedTitle
            && lhs.faviconUrl == rhs.faviconUrl
            && lhs.timestamp == rhs.timestamp
            && lhs.isRead == rhs.isRead
            && lhs.isStarred == rhs.isStarred
            && lhs.url == rhs.url
    }

    /// - Parameters:
    ///   - markedTitle: `ArticleSearchResult.titleMarked` for a search result, else `nil`.
    ///   - zone: resolved once by the caller for the whole list — `formatTimestamp(epochMillis:)`
    ///     would resolve the system zone again for every row (Compose's article row avoids the same
    ///     cost the same way, `ArticleRowComponents.kt`).
    init(row: ArticleListRow, markedTitle: String?, feedTitle: String?, faviconUrl: String?, zone: Kotlinx_datetimeTimeZone) {
        self.row = row
        id = row.id
        let title = row.title
        self.title = title.isEmpty ? nil : title
        self.markedTitle = markedTitle
        // Falls back to the plain title when a search-marked title is blank — matches Compose's own
        // `markedToAnnotatedString(it.ifBlank { article.title })` (`ArticleListPane.kt`).
        highlightedTitle = markedTitle.flatMap { $0.isEmpty ? nil : ArticleRowModel.highlighted($0) }
        self.feedTitle = feedTitle
        self.faviconUrl = faviconUrl
        timestamp = FormattingKt.formatTimestamp(epochMillis: row.published_at, zone: zone)
        isRead = row.is_read == 1
        isStarred = row.is_starred == 1
        let url = row.url
        self.url = url
        hasUsableUrl = ArticleListModelKt.hasUsableUrl(url: url)
        canOpenInBrowser = ArticleListModelKt.canOpenInBrowser(url: url)
    }

    /// Splits `marked` at its `\u{0002}`/`\u{0003}` markers into an `AttributedString` whose matched
    /// runs carry `highlightColor` as their background.
    static func highlighted(_ marked: String) -> AttributedString {
        var result = AttributedString()
        var remaining = marked[...]
        var highlighting = false
        func append(_ text: Substring) {
            guard !text.isEmpty else { return }
            var piece = AttributedString(String(text))
            if highlighting { piece.backgroundColor = highlightColor }
            result += piece
        }
        while let markerIndex = remaining.firstIndex(where: { $0 == markStart || $0 == markEnd }) {
            append(remaining[..<markerIndex])
            highlighting = (remaining[markerIndex] == markStart)
            remaining = remaining[remaining.index(after: markerIndex)...]
        }
        append(remaining)
        return result
    }

    static let highlightColor = Color.yellow.opacity(0.4)
}

/// What an article row shows of its feed.
struct FeedRowInfo: Equatable, Sendable {
    let title: String
    let faviconUrl: String?
}

/// Immutable Kotlin data classes (every property a `val` of an immutable type), so building rows
/// from them off the main actor (`ArticleRowList.buildInBackground`) cannot race.
extension ArticleListRow: @retroactive @unchecked Sendable {}
extension ArticleSearchResult: @retroactive @unchecked Sendable {}

/// One source entry of an article list: the Kotlin row, plus its search-marked title if any. Lets
/// `ArticleRowList.build` read `HomeViewModel`'s emissions directly instead of through an
/// intermediate array.
protocol ArticleRowSource {
    var sourceRow: ArticleListRow { get }
    /// `ArticleSearchResult.titleMarked` for a search result, else `nil`.
    var sourceMarkedTitle: String? { get }
}

extension ArticleListRow: ArticleRowSource {
    var sourceRow: ArticleListRow { self }
    var sourceMarkedTitle: String? { nil }
}

extension ArticleSearchResult: ArticleRowSource {
    var sourceRow: ArticleListRow { article }
    var sourceMarkedTitle: String? { titleMarked }
}

/// A displayed article list: its rows, plus each row's position for order-sensitive lookups
/// (the visible-row report) without a scan.
struct ArticleRowList: Sendable {
    let rows: [ArticleRowModel]
    let indexById: [String: Int]
    /// The time zone the rows' timestamps were formatted in — a row is never reused across a zone
    /// change.
    let zoneId: String
    /// Identifies this list's rows and their order: a `build` that reuses every row in place
    /// returns the previous list itself, generation included, so a consumer holding the previous
    /// list (`ArticleTableView`) can tell nothing moved without comparing ids. Unique across every
    /// list a `HomeObservable` builds (article and search rows alike); `0` only for `empty`.
    let generation: Int

    static var empty: ArticleRowList { ArticleRowList(rows: [], zoneId: "", generation: 0) }

    init(rows: [ArticleRowModel], zoneId: String, generation: Int) {
        self.rows = rows
        self.zoneId = zoneId
        self.generation = generation
        var index = [String: Int](minimumCapacity: rows.count)
        for (i, row) in rows.enumerated() { index[row.id] = i }
        indexById = index
    }

    /// Builds the list for `entries`, reusing each row of `previous` whose Kotlin row is equal
    /// (`isEqual`), whose marked title and feed info are unchanged, and whose zone is the same.
    /// A refresh re-emits the whole list once per feed it fetches, with nearly every row unchanged;
    /// only the rows that did change are resolved and formatted again. When every row is reused at
    /// its previous position, `previous` itself is returned (and `generation` is left unused).
    ///
    /// - Parameters:
    ///   - generation: the new list's `generation`, if one has to be made.
    ///   - makeZone: resolves the Kotlin zone, called at most once and only if some row has to be
    ///     built.
    static func build<Entries: Collection>(
        _ entries: Entries,
        feedInfo: [String: FeedRowInfo],
        reusing previous: ArticleRowList,
        zoneId: String,
        generation: Int,
        makeZone: () -> Kotlinx_datetimeTimeZone
    ) -> ArticleRowList where Entries.Element: ArticleRowSource {
        let canReuse = previous.zoneId == zoneId
        var unchanged = canReuse && previous.rows.count == entries.count
        var zone: Kotlinx_datetimeTimeZone?
        var rows: [ArticleRowModel] = []
        rows.reserveCapacity(entries.count)
        for entry in entries {
            let row = entry.sourceRow
            let markedTitle = entry.sourceMarkedTitle
            let info = feedInfo[row.feed_id]
            if canReuse,
               let index = previous.indexById[row.id],
               case let old = previous.rows[index],
               old.markedTitle == markedTitle,
               old.feedTitle == info?.title,
               old.faviconUrl == info?.faviconUrl,
               old.row.isEqual(row) {
                if index != rows.count { unchanged = false }
                rows.append(old)
                continue
            }
            unchanged = false
            let resolvedZone = zone ?? makeZone()
            zone = resolvedZone
            rows.append(ArticleRowModel(
                row: row,
                markedTitle: markedTitle,
                feedTitle: info?.title,
                faviconUrl: info?.faviconUrl,
                zone: resolvedZone
            ))
        }
        return unchanged ? previous : ArticleRowList(rows: rows, zoneId: zoneId, generation: generation)
    }

    /// `build`, off the main actor: resolving thousands of rows through the Kotlin bridge would
    /// otherwise hold the main thread for every emission.
    @concurrent
    static func buildInBackground<Entries: Collection & Sendable>(
        _ entries: Entries,
        feedInfo: [String: FeedRowInfo],
        reusing previous: ArticleRowList,
        zoneId: String,
        generation: Int
    ) async -> ArticleRowList where Entries.Element: ArticleRowSource {
        build(entries, feedInfo: feedInfo, reusing: previous, zoneId: zoneId, generation: generation) {
            Kotlinx_datetimeTimeZone.Companion.shared.currentSystemDefault()
        }
    }
}
