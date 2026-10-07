import Foundation
import KeryxShared
import Observation
import SwiftUI

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
/// needed so far; add a property + loop pair here as more of `HomeViewModel`'s state is read. Not
/// `pagerArticles`: that flow is `WhileSubscribed`, and observing it here would keep it running for
/// the app's whole life — the iOS pager collects it itself, while on screen (`ReaderPagerObservable`).
@MainActor
@Observable
final class HomeObservable: ObservableAssignment {
    let viewModel: HomeViewModel
    /// Builds a fresh `AddFeedController` for one presentation of the add-feed sheet — never
    /// cached, since `KeryxSdk.newAddFeedController()` itself returns a new instance every call.
    let makeAddFeedController: () -> AddFeedController

    /// The latest `feeds` emission, always — the rows action-time lookups (`currentFeed(id:)`)
    /// resolve through. Not observed: a refresh re-emits it once per fetched feed, so views read
    /// `hasFeeds` / `feedsById` / `sidebar` / `subscribedFeedUrls` instead, which only change with
    /// `HomeViewModel.structuralFeeds`.
    @ObservationIgnored private(set) var feeds: [Feeds] = []
    private(set) var hasFeeds = false
    /// The latest `structuralFeeds` emission — what `sidebar`, `feedsById` and the selection target
    /// are built from. Its `Feeds` may lag behind `feeds` in `etag` / `last_modified` /
    /// `updated_at` (`feedsStructurallyEqual`). Not observed: views read what is derived from it.
    @ObservationIgnored private var structuralFeeds: [Feeds] = []
    /// Built from `structuralFeeds`, so its `Feeds` lag behind `feeds` the same way.
    private(set) var feedsById: [String: Feeds] = [:]
    /// Every subscribed feed's `url`, for the add-feed sheet's already-subscribed check
    /// (`addFeedAlreadySubscribed(url:subscribedUrls:)`); assigned only when it changes.
    private(set) var subscribedFeedUrls: Set<String> = []
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
    /// The sidebar's structure derived from the fields above — see `SidebarModel`. Rebuilt at most
    /// once per MainActor turn however many of its inputs changed in it (`sidebarRebuild`).
    private(set) var sidebar = SidebarModel.empty
    /// `sidebar.sortedFolders` / `sortedTags`, assigned only when they change, for the menu bar
    /// (`HomeCommands`): reading them through `sidebar` rebuilt it on every sidebar rebuild.
    private(set) var sortedFolders: [Folders] = []
    private(set) var sortedTags: [Tags] = []

    private(set) var searchQuery: String = ""
    /// Whether `searchQuery` has a usable term (`searchTerms`), assigned only when that changes —
    /// the article list reads this rather than `searchQuery`, which changes with every keystroke.
    private(set) var searchQueryHasTerms = false
    private(set) var searchBarVisible: Bool = false
    private(set) var searchActive: Bool = false
    private(set) var searching: Bool = false
    /// The latest `searchResults` emission, which `searchRows` is built from. Not observed: views
    /// read `searchRows` / `hasSearchResults`.
    @ObservationIgnored private var searchResults: [ArticleSearchResult] = []
    /// Whether `searchRows` has any row — assigned together with it, so the two never disagree.
    private(set) var hasSearchResults = false
    private(set) var pendingSearchFocus: Bool = false

    private(set) var unreadOnly: Bool = false
    private(set) var newestFirst: Bool = true
    private(set) var canHideRead: Bool = false

    /// The latest `articles` emission, which `articleRows` is built from. Not observed: views read
    /// `articleRows`.
    @ObservationIgnored private var articles: [ArticleListRow] = []
    /// `articles` / `searchResults` resolved into display rows — see `ArticleRowModel`. Rebuilt
    /// once per emission of either (or of `feeds`, whose titles and favicons the rows show), never
    /// per body evaluation, off the main actor (`ArticleRowList.buildInBackground`); assigned only
    /// when some row actually changed.
    private(set) var articleRows = ArticleRowList.empty
    private(set) var searchRows = ArticleRowList.empty
    /// The in-flight background build of each list. A newer request cancels the older one, and a
    /// result is applied only if it is still the latest request's (`rowListGeneration`), so an
    /// older emission's rows can never land over a newer one's.
    @ObservationIgnored private var articleRowsBuild: (task: Task<Void, Never>, generation: Int)?
    @ObservationIgnored private var searchRowsBuild: (task: Task<Void, Never>, generation: Int)?
    /// The last `ArticleRowList.generation` handed out — shared by both lists, so a table switched
    /// from one to the other never mistakes them for the same rows.
    @ObservationIgnored private var rowListGeneration = 0
    /// Each feed's title and favicon as the article rows show them; a `feeds` emission that leaves
    /// this unchanged (a refresh updating etags and error counts) does not touch the rows.
    private var feedRowInfo: [String: FeedRowInfo] = [:]
    @ObservationIgnored private var sidebarRebuild: CoalescedAction?
    private(set) var newArticleCount: Int = 0

    private(set) var selectedArticle: Articles?
    // Narrow facts about the selection for the menu bar (`HomeCommands`) and the article list,
    // assigned only when they change: reading `selectedArticle` / `filter` / the feed list there directly
    // made every article selection, star/read toggle and feed refresh re-evaluate them all.
    private(set) var hasSelectedArticle = false
    private(set) var selectedArticleId: String?
    private(set) var selectedArticleHasUsableUrl = false
    /// `canOpenInBrowser` for the selected article's URL — Open in Browser's own (http(s)) rule.
    private(set) var selectedArticleCanOpenInBrowser = false
    /// `HomeViewModel.selectedArticleShownUnread` — whether the reader's read/unread button shows "unread".
    private(set) var selectedArticleShownUnread = false
    /// The sidebar item the selected filter resolves to (`resolveFeedListSelectionTarget`).
    private(set) var feedListSelectionTarget: FeedListSelectionTarget?
    /// The selected filter's display name (`articleListTitle`), shown in the iOS article list's
    /// heading — the same name Android's narrow-layout header shows.
    private(set) var articleListTitle: String = L("home_all_feeds")
    /// The icon beside it (`ArticleListHeaderIcon`) — the one the subscription list gives that item.
    private(set) var articleListIcon: SidebarRowIcon = SidebarRowStaticContent.all.icon
    private(set) var selectedFeedName: String?
    private(set) var selectedFeedFaviconUrl: String?
    private(set) var articleContents: [String: ArticleReaderRow] = [:]
    private(set) var cloudConnected: Bool = false
    /// `HomeViewModel.canSyncNow` — the "Sync now" predicate every route shares.
    private(set) var canSyncNow: Bool = false
    /// `HomeViewModel.syncDisabledByAuth` — the shared decision that the sync button is disabled
    /// because of the sign-in (true implies `canSyncNow` is false).
    private(set) var syncDisabledByAuth: Bool = false
    private(set) var activity = ActivitySnapshot(feedRefreshCount: 0, syncCount: 0, refreshCycleCount: 0)

    /// Bumped by every copy of the displayed article's URL (`copyArticleUrl`, shared by the reader's
    /// own button, the menu bar's / keyboard's Copy URL command and the article row's context menu)
    /// so the reader can flash a "copied" confirmation, matching Compose's own copy pulse
    /// (`ArticleUrlCopier.kt`). Copying a feed or site URL never bumps it. Not itself a
    /// `HomeViewModel` `StateFlow` — this is UI-only feedback state, kept here alongside it for the
    /// same reason Compose's own pulse lives in the screen's `ArticleUrlCopier`, not the ViewModel.
    private(set) var copyPulse: Int = 0

    #if os(iOS)
    /// The iOS toast that confirms an article URL copy (`copyConfirmation`). Whether a copy is
    /// confirmed at all is the shared plan's decision (`ArticleUrlCopy`); on iOS — no OS confirmation,
    /// and the reader's ✓ is often off screen (a long-pressed row is not selected, and at iPhone width
    /// the reader is not shown) — that is every copy, as Android below API 33 does with its snackbar.
    let copyToast: TransientToastState
    #endif

    /// How an in-app copy confirmation is delivered — see `ArticleUrlCopyConfirmation`.
    @ObservationIgnored private let copyConfirmation: ArticleUrlCopyConfirmation

    /// Whether the article list's new-articles pill is actually on screen (debounced against
    /// `newArticleCount` — see `ArticleListView.updatePillShown`). Written only by `ArticleListView`;
    /// kept here so the copy toast, drawn by `HomeView` over every column, can keep clear of it.
    var newArticlesPillVisible = false

    /// Whether the new-articles pill is on screen at the bottom of the list (oldest-first order puts
    /// it there), where the copy toast would otherwise cover it.
    var newArticlesPillAtBottom: Bool { newArticlesPillVisible && !newestFirst }

    /// - Parameter copyConfirmation: how an in-app copy confirmation is delivered; `nil` for the
    ///   platform's own (iOS: `copyToast` plus a VoiceOver announcement; macOS: the announcement
    ///   only).
    init(
        viewModel: HomeViewModel,
        makeAddFeedController: @escaping () -> AddFeedController,
        copyConfirmation: ArticleUrlCopyConfirmation? = nil
    ) {
        self.viewModel = viewModel
        self.makeAddFeedController = makeAddFeedController
        #if os(iOS)
        let toast = TransientToastState()
        copyToast = toast
        let showToast: ((String) -> Void)? = { toast.show($0) }
        #else
        let showToast: ((String) -> Void)? = nil
        #endif
        self.copyConfirmation = copyConfirmation
            ?? ArticleUrlCopyConfirmation(announce: VoiceOverAnnouncement.post, showToast: showToast)
        sidebarRebuild = CoalescedAction { [weak self] in self?.rebuildSidebar() }
    }

    /// Selects a filter and mirrors the result into the observed state at once. The `for await`
    /// observers only deliver the new value a MainActor hop later, and a view that re-renders in
    /// between (a pane-focus change, say) would read the stale selection and flash it back.
    func selectFilter(_ filter: ArticleFilter, instance: FeedListRowSelection) {
        viewModel.selectFilter(filter: filter, instance: instance)
        assignFilter(viewModel.filter.value)
        assignSelectedRowInstance(viewModel.selectedRowInstance.value)
        updateFeedListSelectionTarget()
    }

    /// The current row for `id`: the `Feeds` the sidebar and `feedsById` hold may carry a stale
    /// `etag` / `last_modified` (see `structuralFeeds`), which a refresh sends as its conditional
    /// request.
    func currentFeed(id: String) -> Feeds? {
        feeds.first { $0.id == id }
    }

    /// Refreshes `feed` from its current row — see `currentFeed(id:)`.
    func refreshFeed(_ feed: Feeds) {
        viewModel.refreshFeed(feed: currentFeed(id: feed.id) ?? feed)
    }

    /// The one handler every route that selects an article goes through: it selects, then brings
    /// `selectedArticleShownUnread` up to date at once. That value otherwise arrives from the shared
    /// flow a moment later, and a screen pushed in the same turn (the iOS reader) would first draw the
    /// previous article's state and then change it — see `HomeViewModel.isSelectedArticleShownUnread`.
    func selectArticle(_ article: ArticleListRow) {
        selecting { viewModel.selectArticle(article: article) }
    }

    func selectNextArticle() {
        selecting { viewModel.selectNext() }
    }

    func selectPreviousArticle() {
        selecting { viewModel.selectPrevious() }
    }

    private func selecting(_ select: () -> Void) {
        select()
        syncSelectedArticleShownUnread()
    }

    private func syncSelectedArticleShownUnread() {
        assignIfChanged(\.selectedArticleShownUnread, viewModel.isSelectedArticleShownUnread())
    }

    /// The one handler every "Copy URL" route for an article goes through — see `ArticleUrlCopy`.
    func copyArticleUrl(url: String?, articleId: String) {
        ArticleUrlCopy.perform(
            url: url,
            articleId: articleId,
            selectedId: selectedArticleId,
            copy: copyToPasteboard,
            pulse: { copyPulse += 1 },
            // The one in-app copy confirmation, run whenever the shared plan's `confirmInApp` says
            // so — `copyConfirmation` only decides how. iOS: the toast plus a VoiceOver
            // announcement, since the reader (which announces its own ✓) may not even be on screen.
            // macOS: the announcement only — the plan asks for it only when the reader's ✓ does not
            // flash, e.g. a context menu opened from the keyboard or VoiceOver on a row it did not
            // select, which would otherwise get no feedback at all.
            confirm: { copyConfirmation.confirm() }
        )
    }

    /// Starts a pull-to-refresh of the current selection's feeds and returns once it — including
    /// the sync that follows it, or a refresh/sync already in flight — has finished, so a
    /// `.refreshable` indicator stays up exactly as long as Compose's own `pullRefreshing` does
    /// (`ArticleListPane.kt`): while this filter is in `HomeViewModel.pullRefreshingFilters`.
    func pullToRefresh() async {
        await awaitPull(of: filter) { viewModel.pullToRefresh() }
    }

    /// The iOS sidebar's pull: every feed, whatever the article list shows. Held up like
    /// `pullToRefresh()`, under the `All` filter it is tracked as.
    func pullToRefreshAll() async {
        await awaitPull(of: ArticleFilterAll()) { viewModel.pullToRefreshAll() }
    }

    private func awaitPull(of filter: ArticleFilter, start: () -> Void) async {
        let filter = filter as AnyObject
        start()
        for await pending in viewModel.pullRefreshingFilters {
            if !pending.contains(where: { ($0 as AnyObject).isEqual(filter) }) { return }
        }
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
        async let t1b: () = observeStructuralFeeds()
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
        async let t31: () = observeCanSyncNow()
        async let t32: () = observeSyncDisabledByAuth()
        async let t33: () = observeSelectedArticleShownUnread()
        _ = await (
            t1, t1b, t2, t3, t4, t5, t6, t7, t8, t9, t10,
            t11, t12, t13, t14, t15, t16, t17, t18, t19, t20,
            t21, t22, t23, t24, t25, t26, t27, t28, t29, t30,
            t31, t32, t33
        )
    }

    private func observeFeeds() async {
        for await v in viewModel.feeds {
            feeds = v
            assignIfChanged(\.hasFeeds, !v.isEmpty)
        }
    }

    /// Everything derived from the feed list's structure. `HomeViewModel` already drops the
    /// emissions that change only fields no view reads (a refresh re-emits `feeds` once per fetched
    /// feed), so each one arriving here is worth rebuilding for.
    private func observeStructuralFeeds() async {
        for await v in viewModel.structuralFeeds {
            structuralFeeds = v
            assignIfChanged(\.subscribedFeedUrls, Set(v.map(\.url)))
            feedsById = Dictionary(uniqueKeysWithValues: v.map { ($0.id, $0) })
            let info = feedsById.mapValues { FeedRowInfo(title: $0.displayTitle(), faviconUrl: $0.favicon_url) }
            if info != feedRowInfo {
                feedRowInfo = info
                rebuildArticleRows()
                rebuildSearchRows()
            }
            sidebarRebuild?.markDirty()
        }
    }

    private func observeTags() async {
        for await v in viewModel.tags {
            if assignIfChanged(\.tags, v) { sidebarRebuild?.markDirty() }
        }
    }

    private func observeFolders() async {
        for await v in viewModel.folders {
            if assignIfChanged(\.folders, v) { sidebarRebuild?.markDirty() }
        }
    }

    private func observeFeedTagMap() async {
        for await v in viewModel.feedTagMap {
            if assignIfChanged(\.feedTagMap, v) { sidebarRebuild?.markDirty() }
        }
    }

    private func observeUnreadByFeed() async {
        for await v in viewModel.unreadByFeed { assignIfChanged(\.unreadByFeed, v.mapValues(\.int64Value)) }
    }

    private func observeUnreadByTag() async {
        for await v in viewModel.unreadByTag { assignIfChanged(\.unreadByTag, v.mapValues(\.int64Value)) }
    }

    private func observeUnreadByFolder() async {
        for await v in viewModel.unreadByFolder { assignIfChanged(\.unreadByFolder, v.mapValues(\.int64Value)) }
    }

    private func observeTotalUnread() async {
        for await v in viewModel.totalUnread { assignIfChanged(\.totalUnread, v.int64Value) }
    }

    private func observeStarredUnreadCount() async {
        for await v in viewModel.starredUnreadCount { assignIfChanged(\.starredUnreadCount, v.int64Value) }
    }

    private func observeFilter() async {
        for await v in viewModel.filter {
            if assignFilter(v) { updateFeedListSelectionTarget() }
        }
    }

    // `assignIfChanged` needs `Equatable`, which a Kotlin sealed type's bridged protocol is not —
    // see `FeedListSelectionEquality`.
    @discardableResult
    private func assignFilter(_ v: ArticleFilter) -> Bool {
        guard !articleFiltersEqual(filter, v) else { return false }
        filter = v
        return true
    }

    private func assignSelectedRowInstance(_ v: FeedListRowSelection) {
        if !feedListRowSelectionsEqual(selectedRowInstance, v) { selectedRowInstance = v }
    }

    private func observeSelectedRowInstance() async {
        for await v in viewModel.selectedRowInstance { assignSelectedRowInstance(v) }
    }

    private func observeCollapsedFolderIds() async {
        for await v in viewModel.collapsedFolderIds {
            if assignIfChanged(\.collapsedFolderIds, v) { sidebarRebuild?.markDirty() }
        }
    }

    private func observeExpandedTagIds() async {
        for await v in viewModel.expandedTagIds {
            if assignIfChanged(\.expandedTagIds, v) { sidebarRebuild?.markDirty() }
        }
    }

    /// The search field's write path. Assigns `searchQuery` at once rather than waiting for the
    /// flow's emission to come back: a binding reading only the emitted value is re-rendered with the
    /// previous query when keystrokes outrun that round trip, and the field then drops characters.
    func setSearchQuery(_ query: String) {
        viewModel.setSearchQuery(query: query)
        assignSearchQuery(query)
    }

    private func observeSearchQuery() async {
        // Reads the flow's current value rather than the emitted one, which may already be stale by
        // the time it is delivered (a later keystroke landed in between) and would otherwise revert
        // the field to it.
        for await _ in viewModel.searchQuery {
            assignSearchQuery(viewModel.searchQuery.value)
        }
    }

    private func assignSearchQuery(_ query: String) {
        guard assignIfChanged(\.searchQuery, query) else { return }
        assignIfChanged(\.searchQueryHasTerms, !SearchQueryKt.searchTerms(raw: query).isEmpty)
    }

    /// The search field's presentation write path. Assigns at once, as `setSearchQuery` does: when
    /// the field is dismissed, `.searchable(isPresented:)` re-reads the binding before the flow's
    /// emission comes back, and a stale `true` makes it present the field again.
    func setSearchBarVisible(_ visible: Bool) {
        viewModel.setSearchBarVisible(visible: visible)
        assignIfChanged(\.searchBarVisible, visible)
    }

    private func observeSearchBarVisible() async {
        // The flow's current value, not the emitted one — see `observeSearchQuery`.
        for await _ in viewModel.searchBarVisible {
            assignIfChanged(\.searchBarVisible, viewModel.searchBarVisible.value.boolValue)
        }
    }

    private func observeSearchActive() async {
        for await v in viewModel.searchActive { assignIfChanged(\.searchActive, v.boolValue) }
    }

    private func observeSearching() async {
        for await v in viewModel.searching { assignIfChanged(\.searching, v.boolValue) }
    }

    private func observeSearchResults() async {
        for await v in viewModel.searchResults {
            searchResults = v
            rebuildSearchRows()
        }
    }

    private func observePendingSearchFocus() async {
        for await v in viewModel.pendingSearchFocus { assignIfChanged(\.pendingSearchFocus, v.boolValue) }
    }

    private func observeUnreadOnly() async {
        for await v in viewModel.unreadOnly { assignIfChanged(\.unreadOnly, v.boolValue) }
    }

    private func observeNewestFirst() async {
        for await v in viewModel.newestFirst { assignIfChanged(\.newestFirst, v.boolValue) }
    }

    private func observeCanHideRead() async {
        for await v in viewModel.canHideRead { assignIfChanged(\.canHideRead, v.boolValue) }
    }

    private func observeArticles() async {
        for await v in viewModel.articles {
            articles = v
            rebuildArticleRows()
        }
    }

    private func updateFeedListSelectionTarget() {
        let target = FeedListModelKt.resolveFeedListSelectionTarget(filter: filter, feeds: structuralFeeds, folders: folders, tags: tags)
        let unchanged = switch (feedListSelectionTarget, target) {
        case (nil, nil): true
        case let (old?, new?): (old as? NSObject)?.isEqual(new) ?? false
        default: false
        }
        if !unchanged { feedListSelectionTarget = target }
        assignIfChanged(\.articleListTitle, FeedListModelKt.articleListTitle(
            filter: filter, feeds: structuralFeeds, folders: folders, tags: tags,
            allLabel: L("home_all_feeds"), starredLabel: L("home_starred")
        ))
        assignIfChanged(\.articleListIcon, ArticleListHeaderIcon.resolve(filter: filter, model: sidebar))
    }

    /// Runs through `sidebarRebuild` only, once per MainActor turn in which any of its inputs changed.
    private func rebuildSidebar() {
        let model = SidebarModel(
            feeds: structuralFeeds,
            folders: folders,
            tags: tags,
            feedTagMap: feedTagMap,
            collapsedFolderIds: collapsedFolderIds,
            expandedTagIds: expandedTagIds
        )
        sidebar = model
        assignIfChanged(\.sortedFolders, model.sortedFolders)
        assignIfChanged(\.sortedTags, model.sortedTags)
        updateFeedListSelectionTarget()
    }

    private func rebuildArticleRows() {
        articleRowsBuild?.task.cancel()
        let generation = nextRowListGeneration()
        let (entries, feedInfo, previous) = (articles, feedRowInfo, articleRows)
        let zoneId = TimeZone.current.identifier
        let task = Task { [weak self] in
            let list = await ArticleRowList.buildInBackground(
                entries, feedInfo: feedInfo, reusing: previous, zoneId: zoneId, generation: generation
            )
            guard let self, !Task.isCancelled, articleRowsBuild?.generation == generation else { return }
            articleRowsBuild = nil
            if list.generation != articleRows.generation { articleRows = list }
        }
        articleRowsBuild = (task, generation)
    }

    private func rebuildSearchRows() {
        searchRowsBuild?.task.cancel()
        let generation = nextRowListGeneration()
        let (entries, feedInfo, previous) = (searchResults, feedRowInfo, searchRows)
        let zoneId = TimeZone.current.identifier
        let task = Task { [weak self] in
            let list = await ArticleRowList.buildInBackground(
                entries, feedInfo: feedInfo, reusing: previous, zoneId: zoneId, generation: generation
            )
            guard let self, !Task.isCancelled, searchRowsBuild?.generation == generation else { return }
            searchRowsBuild = nil
            if list.generation != searchRows.generation { searchRows = list }
            assignIfChanged(\.hasSearchResults, !list.rows.isEmpty)
        }
        searchRowsBuild = (task, generation)
    }

    private func nextRowListGeneration() -> Int {
        rowListGeneration += 1
        return rowListGeneration
    }

    private func observeNewArticleCount() async {
        for await v in viewModel.newArticleCount { assignIfChanged(\.newArticleCount, Int(v.int32Value)) }
    }

    private func observeSelectedArticle() async {
        for await v in viewModel.selectedArticle {
            // Always assigned: a star/read toggle emits a new instance of the same article, which
            // the reader keys its revision on.
            selectedArticle = v
            assignIfChanged(\.hasSelectedArticle, v != nil)
            assignIfChanged(\.selectedArticleId, v?.id)
            assignIfChanged(\.selectedArticleHasUsableUrl, ArticleListModelKt.hasUsableUrl(url: v?.url))
            assignIfChanged(\.selectedArticleCanOpenInBrowser, ArticleListModelKt.canOpenInBrowser(url: v?.url))
        }
    }

    private func observeSelectedFeedName() async {
        for await v in viewModel.selectedFeedName { assignIfChanged(\.selectedFeedName, v) }
    }

    private func observeSelectedFeedFaviconUrl() async {
        for await v in viewModel.selectedFeedFaviconUrl { assignIfChanged(\.selectedFeedFaviconUrl, v) }
    }

    private func observeArticleContents() async {
        for await v in viewModel.articleContents { articleContents = v }
    }

    private func observeCloudConnected() async {
        for await v in viewModel.cloudConnected { assignIfChanged(\.cloudConnected, v.boolValue) }
    }

    private func observeActivity() async {
        for await v in viewModel.activity { assignIfChanged(\.activity, v) }
    }

    private func observeSelectedArticleShownUnread() async {
        for await v in viewModel.selectedArticleShownUnread { assignIfChanged(\.selectedArticleShownUnread, v.boolValue) }
    }

    private func observeCanSyncNow() async {
        for await v in viewModel.canSyncNow { assignIfChanged(\.canSyncNow, v.boolValue) }
    }

    private func observeSyncDisabledByAuth() async {
        for await v in viewModel.syncDisabledByAuth { assignIfChanged(\.syncDisabledByAuth, v.boolValue) }
    }
}
