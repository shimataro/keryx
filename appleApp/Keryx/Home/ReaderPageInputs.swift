import KeryxShared

/// `ReaderPageView`'s inputs for the single (macOS) reader: what `HomeViewModel.readerContent`
/// resolves to.
struct ReaderPageInputs {
    /// The loaded article's body.
    let row: ArticleReaderRow?
    /// A newly selected article's header alone, while its body is held up past the grace period.
    let header: ArticleListRow?
    /// Identifies the instance the content came from, so the document is rebuilt only when a new one
    /// is published (a star or read toggle publishes a new article instance; an unchanged re-read
    /// does not).
    let revision: ObjectIdentifier?

    static var none: ReaderPageInputs { ReaderPageInputs(row: nil, header: nil, revision: nil) }

    static func resolve(_ content: ReaderContent) -> ReaderPageInputs {
        switch onEnum(of: content) {
        case .none: return .none
        case .header(let header): return ReaderPageInputs(row: nil, header: header.row, revision: ObjectIdentifier(header.row))
        case .full(let full): return ReaderPageInputs(row: full.article.toReaderRow(), header: nil, revision: ObjectIdentifier(full.article))
        }
    }
}
