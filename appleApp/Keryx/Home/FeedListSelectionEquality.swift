import KeryxShared

/// `ArticleFilter` and `FeedListRowSelection` are Kotlin sealed types — compared here via
/// `onEnum(of:)` rather than `==`, since a value typed as the bridged Swift protocol (not the
/// concrete case class) is not guaranteed `Equatable` across the existential.
func articleFiltersEqual(_ a: ArticleFilter, _ b: ArticleFilter) -> Bool {
    switch (onEnum(of: a), onEnum(of: b)) {
    case (.all, .all): return true
    case (.starred, .starred): return true
    case (.feed(let x), .feed(let y)): return x.feedId == y.feedId
    case (.folder(let x), .folder(let y)): return x.folderId == y.folderId
    case (.tag(let x), .tag(let y)): return x.tagId == y.tagId
    default: return false
    }
}

func feedListRowSelectionsEqual(_ a: FeedListRowSelection, _ b: FeedListRowSelection) -> Bool {
    switch (onEnum(of: a), onEnum(of: b)) {
    case (.all, .all): return true
    case (.starred, .starred): return true
    case (.feedInFolderGroup(let x), .feedInFolderGroup(let y)): return x.feedId == y.feedId
    case (.feedInTag(let x), .feedInTag(let y)): return x.feedId == y.feedId && x.tagId == y.tagId
    case (.folder(let x), .folder(let y)): return x.folderId == y.folderId
    case (.tag(let x), .tag(let y)): return x.tagId == y.tagId
    default: return false
    }
}

/// A stable `String` identity for one rendered copy of a `FeedListRowSelection` — the same feed
/// shown under both its folder group and an expanded tag renders as two distinct rows/keys, so
/// `ScrollViewProxy.scrollTo` and appeared-row tracking can tell them apart (`FeedListView`'s own
/// scroll-to-selection effect).
func feedListRowSelectionKey(_ instance: FeedListRowSelection) -> String {
    switch onEnum(of: instance) {
    case .all: return "all"
    case .starred: return "starred"
    case .folder(let f): return "folder:\(f.folderId)"
    case .tag(let t): return "tag:\(t.tagId)"
    case .feedInFolderGroup(let f): return "feed-in-folder:\(f.feedId)"
    case .feedInTag(let f): return "feed-in-tag:\(f.tagId):\(f.feedId)"
    }
}
