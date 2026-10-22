import KeryxShared
import Testing

/// `kotlinValuesEqual` compares Kotlin values that reach Swift typed as a protocol or `AnyObject` —
/// not `Equatable` — by Kotlin `equals`.
@Suite
struct KotlinValuesEqualTests {
    private func row(_ id: String) -> ArticleListRow {
        ArticleListRow(
            id: id, feed_id: "f1", title: "Title", url: "https://example.com/\(id)",
            published_at: nil, created_at: 0, is_read: 0, is_starred: 0
        )
    }

    @Test
    func twoNilsAreEqualAndANilAndAValueAreNot() {
        #expect(kotlinValuesEqual(nil, nil))
        #expect(!kotlinValuesEqual(nil, ArticleFilterAll()))
        #expect(!kotlinValuesEqual(ArticleFilterAll(), nil))
    }

    @Test
    func equalDataClassesAreEqualWhateverTheInstance() {
        #expect(kotlinValuesEqual(ArticleFilterFeed(feedId: "f1"), ArticleFilterFeed(feedId: "f1")))
        #expect(!kotlinValuesEqual(ArticleFilterFeed(feedId: "f1"), ArticleFilterFeed(feedId: "f2")))
        #expect(!kotlinValuesEqual(ArticleFilterFeed(feedId: "f1"), ArticleFilterTag(tagId: "f1")))
    }

    /// The reader content as the bridge hands it over: a protocol-typed sealed value.
    @Test
    func sealedReaderContentIsComparedByValue() {
        let a: ReaderContent = ReaderContentHeader(row: row("a1"))
        let same: ReaderContent = ReaderContentHeader(row: row("a1"))
        let other: ReaderContent = ReaderContentHeader(row: row("a2"))
        let none: ReaderContent = ReaderContentNone.shared

        #expect(kotlinValuesEqual(a as AnyObject, same as AnyObject))
        #expect(!kotlinValuesEqual(a as AnyObject, other as AnyObject))
        #expect(!kotlinValuesEqual(a as AnyObject, none as AnyObject))
        #expect(kotlinValuesEqual(none as AnyObject, ReaderContentNone.shared as AnyObject))
    }
}
