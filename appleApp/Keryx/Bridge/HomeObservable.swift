import KeryxShared
import Observation

/// Mirrors `HomeViewModel`'s `StateFlow`s as plain `@Observable` properties, so SwiftUI views read
/// them like any other observable state instead of collecting a `SkieSwiftStateFlow` themselves.
///
/// `@MainActor`: every property write below must land on the actor SwiftUI observes from. Each
/// `for await` loop resumes wherever `HomeViewModel`'s own coroutine scope dispatches it; pinning
/// both this class and every `observe*` method to `@MainActor` is what makes each resumption hop
/// back here automatically, and what lets `startObserving()` fan every loop out as a `@MainActor`
/// child task (see its own comment).
///
/// One `observe*` loop per `StateFlow` exposed. This class only carries what the app screens have
/// needed so far; add a property + loop pair here as more of `HomeViewModel`'s state is read.
@MainActor
@Observable
final class HomeObservable {
    let viewModel: HomeViewModel
    /// Builds a fresh `AddFeedController` for one presentation of the add-feed sheet — never
    /// cached, since `KeryxSdk.newAddFeedController()` itself returns a new instance every call.
    let makeAddFeedController: () -> AddFeedController

    private(set) var feeds: [Feeds] = []
    private(set) var tags: [Tags] = []
    private(set) var folders: [Folders] = []
    private(set) var feedTagMap: [String: Set<String>] = [:]
    private(set) var unreadByFeed: [String: Int64] = [:]
    private(set) var unreadByTag: [String: Int64] = [:]
    private(set) var unreadByFolder: [String: Int64] = [:]
    private(set) var totalUnread: Int64 = 0
    private(set) var starredUnreadCount: Int64 = 0

    private(set) var filter: ArticleFilter = ArticleFilterAll()
    private(set) var selectedRowInstance: FeedListRowSelection = FeedListRowSelectionAll()
    private(set) var collapsedFolderIds: Set<String> = []
    private(set) var expandedTagIds: Set<String> = []

    private(set) var searchQuery: String = ""
    private(set) var searchBarVisible: Bool = false
    private(set) var searchActive: Bool = false
    private(set) var searching: Bool = false
    private(set) var searchResults: [ArticleSearchResult] = []
    private(set) var pendingSearchFocus: Bool = false

    private(set) var unreadOnly: Bool = false
    private(set) var newestFirst: Bool = true
    private(set) var canHideRead: Bool = false

    private(set) var articles: [ArticleListRow] = []
    private(set) var newArticleCount: Int = 0

    private(set) var selectedArticle: Articles?
    private(set) var selectedFeedName: String?
    private(set) var selectedFeedFaviconUrl: String?
    private(set) var articleContents: [String: ArticleReaderRow] = [:]
    private(set) var cloudConnected: Bool = false
    private(set) var activity = ActivitySnapshot(feedRefreshCount: 0, syncCount: 0, refreshCycleCount: 0)

    /// Bumped by every URL-copy action (the reader's own button, and eventually the menu bar's/
    /// keyboard's Copy URL command — see `HomeCommands.swift`) so any UI observing it can flash a
    /// "copied" confirmation, matching Compose's own `copyPulse` (`HomeScreen.kt`). Not itself a
    /// `HomeViewModel` `StateFlow` — this is UI-only feedback state, kept here alongside it for the
    /// same reason `HomeScreen.kt`'s own `copyPulse` lives in the Compose screen, not the ViewModel.
    private(set) var copyPulse: Int = 0

    /// Whether a text field (the sidebar's search field, currently) holds keyboard focus — plain UI
    /// state written by `HomeView`'s own `focusedPane` tracking, not a `HomeViewModel` `StateFlow`.
    /// Read by `HomeCommands.menuState` so the Feed/Article menu's bare-key accelerators (Return/
    /// Delete) and its `feedActionsEnabled`-gated items agree with `HomeShortcutsKt.homeShortcutFor`'s
    /// own `textInputFocused` guard, matching Compose's `MenuController.textInputFocused`
    /// (`HomeScreen.kt`).
    var textInputFocused = false

    init(viewModel: HomeViewModel, makeAddFeedController: @escaping () -> AddFeedController) {
        self.viewModel = viewModel
        self.makeAddFeedController = makeAddFeedController
    }

    func pulseCopy() {
        copyPulse += 1
    }

    /// Starts every field's observation loop concurrently. Call once from a `.task` on the view
    /// that owns this object; the task's cancellation (the view disappearing) cancels every loop
    /// below with it, since each `async let` is a structured child task of this function.
    ///
    /// Uses `async let` rather than `withTaskGroup` — a `group.addTask { @MainActor in ... }`
    /// closure per flow trips Swift 6's region-based isolation checker here ("pattern that the
    /// region based isolation checker does not understand how to check"), while `async let` on a
    /// call to one of this actor-isolated class's own `observe*` methods type-checks cleanly and
    /// preserves the same actor isolation for each child task.
    func startObserving() async {
        async let t1: () = observeFeeds()
        async let t2: () = observeTags()
        async let t3: () = observeFolders()
        async let t4: () = observeFeedTagMap()
        async let t5: () = observeUnreadByFeed()
        async let t6: () = observeUnreadByTag()
        async let t7: () = observeUnreadByFolder()
        async let t8: () = observeTotalUnread()
        async let t9: () = observeStarredUnreadCount()
        async let t10: () = observeFilter()
        async let t11: () = observeSelectedRowInstance()
        async let t12: () = observeCollapsedFolderIds()
        async let t13: () = observeExpandedTagIds()
        async let t14: () = observeSearchQuery()
        async let t15: () = observeSearchBarVisible()
        async let t16: () = observeSearchActive()
        async let t17: () = observeSearching()
        async let t18: () = observeSearchResults()
        async let t19: () = observePendingSearchFocus()
        async let t20: () = observeUnreadOnly()
        async let t21: () = observeNewestFirst()
        async let t22: () = observeCanHideRead()
        async let t23: () = observeArticles()
        async let t24: () = observeNewArticleCount()
        async let t25: () = observeSelectedArticle()
        async let t26: () = observeSelectedFeedName()
        async let t27: () = observeSelectedFeedFaviconUrl()
        async let t28: () = observeArticleContents()
        async let t29: () = observeCloudConnected()
        async let t30: () = observeActivity()
        _ = await (
            t1, t2, t3, t4, t5, t6, t7, t8, t9, t10,
            t11, t12, t13, t14, t15, t16, t17, t18, t19, t20,
            t21, t22, t23, t24, t25, t26, t27, t28, t29, t30
        )
    }

    private func observeFeeds() async {
        for await v in viewModel.feeds { feeds = v }
    }

    private func observeTags() async {
        for await v in viewModel.tags { tags = v }
    }

    private func observeFolders() async {
        for await v in viewModel.folders { folders = v }
    }

    private func observeFeedTagMap() async {
        for await v in viewModel.feedTagMap { feedTagMap = v }
    }

    private func observeUnreadByFeed() async {
        for await v in viewModel.unreadByFeed { unreadByFeed = v.mapValues(\.int64Value) }
    }

    private func observeUnreadByTag() async {
        for await v in viewModel.unreadByTag { unreadByTag = v.mapValues(\.int64Value) }
    }

    private func observeUnreadByFolder() async {
        for await v in viewModel.unreadByFolder { unreadByFolder = v.mapValues(\.int64Value) }
    }

    private func observeTotalUnread() async {
        for await v in viewModel.totalUnread { totalUnread = v.int64Value }
    }

    private func observeStarredUnreadCount() async {
        for await v in viewModel.starredUnreadCount { starredUnreadCount = v.int64Value }
    }

    private func observeFilter() async {
        for await v in viewModel.filter { filter = v }
    }

    private func observeSelectedRowInstance() async {
        for await v in viewModel.selectedRowInstance { selectedRowInstance = v }
    }

    private func observeCollapsedFolderIds() async {
        for await v in viewModel.collapsedFolderIds { collapsedFolderIds = v }
    }

    private func observeExpandedTagIds() async {
        for await v in viewModel.expandedTagIds { expandedTagIds = v }
    }

    private func observeSearchQuery() async {
        for await v in viewModel.searchQuery { searchQuery = v }
    }

    private func observeSearchBarVisible() async {
        for await v in viewModel.searchBarVisible { searchBarVisible = v.boolValue }
    }

    private func observeSearchActive() async {
        for await v in viewModel.searchActive { searchActive = v.boolValue }
    }

    private func observeSearching() async {
        for await v in viewModel.searching { searching = v.boolValue }
    }

    private func observeSearchResults() async {
        for await v in viewModel.searchResults { searchResults = v }
    }

    private func observePendingSearchFocus() async {
        for await v in viewModel.pendingSearchFocus { pendingSearchFocus = v.boolValue }
    }

    private func observeUnreadOnly() async {
        for await v in viewModel.unreadOnly { unreadOnly = v.boolValue }
    }

    private func observeNewestFirst() async {
        for await v in viewModel.newestFirst { newestFirst = v.boolValue }
    }

    private func observeCanHideRead() async {
        for await v in viewModel.canHideRead { canHideRead = v.boolValue }
    }

    private func observeArticles() async {
        for await v in viewModel.articles { articles = v }
    }

    private func observeNewArticleCount() async {
        for await v in viewModel.newArticleCount { newArticleCount = Int(v.int32Value) }
    }

    private func observeSelectedArticle() async {
        for await v in viewModel.selectedArticle { selectedArticle = v }
    }

    private func observeSelectedFeedName() async {
        for await v in viewModel.selectedFeedName { selectedFeedName = v }
    }

    private func observeSelectedFeedFaviconUrl() async {
        for await v in viewModel.selectedFeedFaviconUrl { selectedFeedFaviconUrl = v }
    }

    private func observeArticleContents() async {
        for await v in viewModel.articleContents { articleContents = v }
    }

    private func observeCloudConnected() async {
        for await v in viewModel.cloudConnected { cloudConnected = v.boolValue }
    }

    private func observeActivity() async {
        for await v in viewModel.activity { activity = v }
    }
}
