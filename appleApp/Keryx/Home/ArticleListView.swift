import KeryxShared
import SwiftUI

/// The center pane: toolbar (the list mode menu, which also holds the sort order; mark-all-read —
/// the notification center lives at the bottom of the sidebar instead, `NotificationCenterRow`),
/// the article rows themselves (search-highlighted when a query is active), the "new articles"
/// pill, and the four empty states — see `external-spec.md` §7's article-list bullets and the M2
/// research notes.
struct ArticleListView: View {
    let home: HomeObservable
    let settingsNavigation: SettingsNavigation
    @Bindable var dialogs: SidebarDialogState
    var focusedPane: FocusState<HomeFocusedPane?>.Binding
    /// Whether the split view is collapsed with this list as its topmost column — it then keeps no
    /// selection on screen (`CompactArticleSelection`). Always false on macOS.
    let articleListIsTopmost: Bool
    /// The row flashing gray after the reader was popped back to it — see `HomeView.returnFlashId`.
    let returnFlashId: String?
    /// Pushes the reader when the split view is collapsed (iPhone); a no-op otherwise. A row is a
    /// plain button rather than a `List` selection, so nothing navigates to the reader by itself.
    let onOpenArticle: () -> Void
    /// Bumped by `HomeView` each time a phone-width split view brings this list forward from the
    /// sidebar; every change rebuilds `content` (see its `.id` in `body`). Unused on macOS.
    let remountGeneration: Int

    /// The selection is drawn strongly only while this window is key (AppKit's own rule for a
    /// source list's selection), on top of the article list holding the pane focus.
    #if os(macOS)
    @Environment(\.controlActiveState) private var controlActiveState
    private var windowIsKey: Bool { controlActiveState == .key }
    #else
    /// iOS has no key-window distinction for this, so the pane focus alone decides.
    private var windowIsKey: Bool { true }
    #endif

    #if os(macOS)
    /// Handed to every hosted row explicitly — see `ArticleTableView.contextMenuSelectionTracker`.
    @Environment(\.contextMenuSelectionTracker) private var contextMenuSelectionTracker
    /// Bumped on every filter switch — see `ArticleTableView.resetGeneration`.
    @State private var tableResetGeneration = 0
    /// The new-articles pill's pending jump — see `ArticleTableScrollRequest`.
    @State private var tableScrollRequest = ArticleTableScrollRequest()
    #else
    /// Which rows are on screen, and the pending report of them — see `VisibleRowTracker`.
    @State private var visibleRows = VisibleRowTracker()
    /// Whether the first real rows have been shown — see the restored-selection scroll in `body`.
    @State private var didShowFirstRows = false
    #if os(iOS)
    @State private var topReset = ArticleListTopReset()
    /// Whether `articleList`'s heading row is on screen, before iOS 26 only. While it has scrolled
    /// away, the bar's principal item names the list in its place (`barShowsTitle`).
    @State private var barTitle = ArticleListBarTitle<ListIdentity>()
    @State private var scrollGate = ArticleListScrollGate()
    /// Whether this list is on screen rather than under the reader.
    @State private var listVisible = false
    #endif
    /// `noArticlesView`'s header height, so the empty state below it fills exactly the rest.
    @State private var emptyListHeaderHeight: CGFloat = 0
    /// Whether this list has been put into a window yet, which rebuilds `content` once more (its
    /// `.id`). The list a phone-width split view pushes for the first time has its refresh control
    /// hung under the navigation bar, and after its first pull that control leaves the list resting
    /// tens of points short of the top (the top inset shrinks back but the offset does not follow).
    /// A list built once its column is in the navigation stack gets the control inside it and rests
    /// at the top. Rebuilding as the push starts is too early (the control still goes under the
    /// bar); rebuilding on window attachment happens before the list can be touched, so nothing
    /// the reader did is lost.
    @State private var attachedToWindow = false
    #endif

    /// The rows on screen: the search results while a search is active, else the filter's own list.
    /// Both are resolved once per emission by `HomeObservable` (see `ArticleRowModel`).
    private var displayed: ArticleRowList { home.searchActive ? home.searchRows : home.articleRows }
    private var displayedRows: [ArticleRowModel] { displayed.rows }

    /// A plain `String` key for `home.filter` (a Kotlin sealed-type protocol value), so
    /// `.onChange(of:)` — which requires a genuinely `Equatable` value type, not a bridged
    /// existential — has something safe to compare.
    private var filterKey: String {
        switch onEnum(of: home.filter) {
        case .all: return "all"
        case .starred: return "starred"
        case .feed(let f): return "feed:\(f.feedId)"
        case .folder(let f): return "folder:\(f.folderId)"
        case .tag(let t): return "tag:\(t.tagId)"
        }
    }

    #if os(iOS)
    private var topResetInput: ArticleListTopReset.Input {
        ArticleListTopReset.Input(
            filterKey: filterKey,
            rowsMatchFilter: home.searchActive ? home.searchRowsMatchFilter : home.articleRowsMatchFilter,
            firstRowId: displayedRows.first?.id
        )
    }

    /// `attachedToWindow` too: its first change rebuilds the list, and a scroll carried out before
    /// that would be lost with the list it was aimed at.
    private var scrollGateInput: ArticleListScrollGate.Input {
        ArticleListScrollGate.Input(canScroll: listVisible && attachedToWindow, remountGeneration: remountGeneration)
    }
    #endif

    var body: some View {
        VStack(spacing: 0) {
            #if os(macOS)
            // `ArticleTableView` does its own scrolling: back to the top on a filter switch, to an
            // off-screen selection, and to the fresh end for the pill.
            withNewArticlesPill(content) {
                tableScrollRequest = ArticleTableScrollRequest(
                    generation: tableScrollRequest.generation + 1,
                    newestFirst: home.newestFirst
                )
            }
            .onChange(of: filterKey, initial: false) { _, _ in
                tableResetGeneration += 1
            }
            #else
            ScrollViewReader { proxy in
                // Rebuilt whenever a phone-width split view brings this list forward from the
                // sidebar. SwiftUI hangs a `.refreshable` list's refresh control from the navigation
                // bar and drops it once the column is popped, but never re-attaches it to the list it
                // keeps: the pull still refreshes, with no spinner (seen on iOS 27). A rebuilt list
                // gets a fresh control. `EnvironmentValues.refresh` is read-only, so there is no
                // lighter way to detach and re-attach the action. The rebuilt list starts at the top,
                // as NetNewsWire's does on the same route: scrolling it back right after the rebuild
                // made a row touched next jump for a moment.
                withNewArticlesPill(content.id(listIdentity)) {
                    scrollToFreshEnd(proxy)
                }
                    // Resets to the top on every filter switch, whatever the sort order — unlike
                    // the new-articles pill's own jump, which follows it (`ArticleListPane.kt:205-213`
                    // vs. `NewArticlesPill`'s own "fresh end") — once the new filter's rows are on
                    // screen, never at the previous filter's (`ArticleListTopReset`). The first
                    // composition — including a restored `lastArticleId` selection — is not a switch
                    // and is not scrolled out from under.
                    // Before iOS 26 the reset goes to the heading row above the first article, so the
                    // new filter's name is on screen again (`carryOut`).
                    .onChange(of: topResetInput, initial: true) { _, input in
                        if let first = topReset.update(input) {
                            requestScroll(ArticleListScrollGate.Target(rowId: first), proxy)
                        }
                    }
                    // Only when the user selected an article (`selectionRevealRequests`) and it is off-screen
                    // (keyboard navigation) — a row already visible (e.g. just clicked) never jumps.
                    // Not on `selectedArticleId` itself: it also moves back without the user acting
                    // (a selection found tombstoned), which must not scroll the list.
                    .onChange(of: home.selectionRevealRequests) { _, _ in
                        guard let id = home.selectedArticleId else { return }
                        requestScroll(ArticleListScrollGate.Target(rowId: id, onlyIfOffScreen: true), proxy)
                    }
                    .onChange(of: showsRows) { _, shows in
                        // The article restored from the previous session is already selected before
                        // any row exists, so the selection-change scroll above never sees it; bring it
                        // into view once, when the list first shows rows (after they are laid out).
                        // Not on the rows alone: they and `listBody` arrive separately, and rows
                        // behind a pending body have no list to take the scroll.
                        guard !didShowFirstRows, shows else { return }
                        didShowFirstRows = true
                        guard let id = home.selectedArticleId else { return }
                        requestScrollAfterUpdate(ArticleListScrollGate.Target(rowId: id), proxy)
                    }
                    // After the current update, so the rows coming back with the list have reported
                    // themselves (`onlyIfOffScreen`).
                    .onChange(of: scrollGateInput, initial: true) { _, input in
                        if let target = scrollGate.update(input) { requestScrollAfterUpdate(target, proxy) }
                    }
            }
            #endif
        }
        #if os(iOS)
        // Outside the rebuilt `content`, so a rebuild does not attach a fresh probe and fire again.
        .background {
            WindowAttachmentProbe {
                guard !attachedToWindow else { return }
                // After the current update: the probe reports from inside UIKit's own layout pass.
                Task { @MainActor in attachedToWindow = true }
            }
        }
        // Outside the rebuilt `content` too, so a rebuild does not read as the list leaving the screen.
        .onAppear { listVisible = true }
        .onDisappear { listVisible = false }
        .focused(focusedPane, equals: .articleList)
        #endif
        .toolbar { toolbarContent }
        #if os(iOS)
        .navigationTitle(home.articleListTitle)
        .navigationBarTitleDisplayMode(Self.titleDisplayMode)
        // The system search field. iOS keeps it here, on the column whose contents it narrows (the
        // HIG's placement for searching the current view, as Mail does), rather than on the sidebar
        // macOS uses — at a compact width the sidebar's results would land in a column not on screen.
        // `.automatic` gives a navigation-bar drawer on iOS 17/18 and the toolbar on iOS 26 (the
        // bottom bar on iPhone). Its focus is reported into `focusedPane` only from iOS 18 — see
        // `SearchFocusModifier`.
        .searchable(
            text: searchQueryBinding,
            isPresented: searchPresentedBinding,
            placement: .automatic,
            prompt: L("home_search_placeholder")
        )
        .modifier(SearchFocusModifier(focusedPane: focusedPane))
        // Also runs on mount: at a compact width `HomeView` pushes this list in response to the same
        // request, so it may only be mounted after the request was made.
        .task(id: home.pendingSearchFocus) {
            guard home.pendingSearchFocus else { return }
            await focusSearchField()
        }
        #endif
    }

    #if os(iOS)
    /// Gives the search field keyboard focus, then consumes the request. A list pushed in response to
    /// the request does not have its system field in place on the first pass, and an assignment made
    /// before that is silently dropped — so this re-assigns until it sticks, as
    /// `HomeView.applyInitialFocus` does for the panes. Bounded, since before iOS 18 the field cannot
    /// report its focus at all (`SearchFocusModifier`). A cancelled attempt (e.g. this list unmounted
    /// mid-loop) leaves the request for the next list to mount; one never consumed is cleared when
    /// the search closes (`setSearchBarVisible(false)`).
    private func focusSearchField() async {
        for _ in 0..<ArticleListView.searchFocusAttempts {
            focusedPane.wrappedValue = .search
            try? await Task.sleep(for: .milliseconds(50))
            if Task.isCancelled { return }
            if focusedPane.wrappedValue == .search { break }
        }
        home.viewModel.consumeSearchFocusRequest()
    }

    /// 50 ms apart: long enough to outlast a navigation push.
    private static let searchFocusAttempts = 12
    #endif

    #if os(iOS)
    private var searchQueryBinding: Binding<String> {
        Binding(
            get: { home.searchQuery },
            set: { home.setSearchQuery($0) }
        )
    }

    /// Whether the field is presented, two-way bound to `searchBarVisible` — the same flag Android's
    /// narrower layouts open and close their expanded search bar with. Cancelling the field closes it
    /// (`searchActive` turns false and the selection's own list comes back), as does any dismissal
    /// the system makes on its own; ⌘F opens it through `setSearchBarVisible(true)`.
    private var searchPresentedBinding: Binding<Bool> {
        Binding(
            get: { home.searchBarVisible },
            set: { home.setSearchBarVisible($0) }
        )
    }
    #endif

    /// Overlays the new-articles pill on `list`; tapping it clears the count and runs `jump`.
    private func withNewArticlesPill(_ list: some View, jump: @escaping () -> Void) -> some View {
        list
            .overlay(alignment: home.newestFirst ? .top : .bottom) {
                if home.newArticlesPillVisible {
                    newArticlesPill(jump: jump)
                }
            }
            .task(id: home.newArticleCount) {
                await updatePillShown()
            }
            // Not on screen (e.g. a phone-width reader pushed over the list): nothing for the copy
            // toast to keep clear of. `.task(id:)` runs again when the list reappears.
            .onDisappear { home.newArticlesPillVisible = false }
    }

    /// The pill's count going from `0` to positive is deliberately not shown immediately — the
    /// article list's own visible-id report lands a frame after a new query result does, so an
    /// article landing *inside* the current viewport would otherwise flash the pill for a single
    /// frame before the visibility report catches up and drops it back out of the count. Going
    /// back to `0` is always immediate. Mirrors Compose's own `NewArticlesPill` (`NewArticlesPill.kt`).
    /// The result lives in `HomeObservable.newArticlesPillVisible` so the copy toast, drawn by
    /// `HomeView` over every column, can keep clear of the pill.
    private func updatePillShown() async {
        guard home.newArticleCount > 0 else {
            home.newArticlesPillVisible = false
            return
        }
        try? await Task.sleep(for: .milliseconds(200))
        guard !Task.isCancelled else { return }
        home.newArticlesPillVisible = true
    }

    // MARK: - Toolbar

    // Lives in the window toolbar (the strip above this column), not in the pane: the native
    // toolbar draws its buttons at the platform's standard size and groups them, replacing the
    // hand-rolled capsule Compose uses as a stand-in (see `ui-guidelines`' "Icon grouping").
    @ToolbarContentBuilder
    private var toolbarContent: some ToolbarContent {
        #if os(iOS)
        // Before iOS 26 the title feeds the next column's back button and its long-press menu; the
        // heading is `ArticleListHeader`, so the bar's own centered title is replaced by nothing —
        // until the heading scrolls away with the rows, when the bar names the list in its place.
        // From iOS 26 the large title names the list itself (`titleDisplayMode`). Keyed on
        // `showsListHeader`, the one place that decides which of the two this iOS gets.
        if Self.showsListHeader {
            ToolbarItem(placement: .principal) {
                if barShowsTitle {
                    Text(home.articleListTitle).font(.headline).lineLimit(1)
                } else {
                    // Zero-sized: once the title has been shown, a flexible `Color.clear` keeps the
                    // room it took and leaves Mark All as Read in the overflow menu.
                    Color.clear.frame(width: 0, height: 0)
                }
            }
        }
        // The same status as the sidebar's, for the column an iPhone mostly shows.
        RefreshStatusToolbarItem(home: home)
        iOSActionItems
        #else
        macToolbarItems
        #endif
    }

    #if os(iOS)
    /// iOS: the mode menu leads the trailing cluster on every version, ahead of Mark All as Read.
    /// It is a view option, not navigation (the HIG puts those at the trailing edge, and an
    /// iPhone's leading edge holds the back button). Not a `.navigation` item: that placement
    /// leaves the side to the system, which put it leading on iOS 26 and trailing on iOS 27.
    @ToolbarContentBuilder
    private var iOSActionItems: some ToolbarContent {
        #if compiler(>=6.4)
        // `#available` is a runtime check, so `visibilityPriority` also needs the iOS 27 SDK at
        // compile time; Swift 6.4 ships with it (Xcode 27), while an older Xcode keeps the group.
        if #available(iOS 27.0, *) {
            // A vertical bar (iPhone Duo's outer display, and landscape on its inner one) overflows
            // from the bottom up, which hid Mark All as Read first and squeezed the search button
            // out of shape. The HIG asks to keep frequent actions visible longest, so Mark All as
            // Read outranks the list mode menu. A horizontal bar ignores these.
            ToolbarItem(placement: .primaryAction) { listModeMenu }
                .visibilityPriority(.low)
            ToolbarItem(placement: .primaryAction) { markAllReadButton }
                .visibilityPriority(.high)
        } else {
            groupedActionItems
        }
        #else
        groupedActionItems
        #endif
    }

    /// Both items in one group — wherever per-item visibility priorities are unavailable.
    @ToolbarContentBuilder
    private var groupedActionItems: some ToolbarContent {
        // Every item's label is a `Label`, not a bare icon: the toolbar still renders icon-only, but
        // the overflow menu it collapses into at a narrow width takes each row's title from it.
        // Separate items of one group (not an `HStack` in one item) so each gets its own row there.
        ToolbarItemGroup(placement: .primaryAction) {
            listModeMenu
            markAllReadButton
        }
    }
    #endif

    #if os(macOS)
    /// macOS: the whole toolbar — the mode menu, with its name, at the column's leading edge, and
    /// the actions pushed to the trailing edge.
    @ToolbarContentBuilder
    private var macToolbarItems: some ToolbarContent {
        ToolbarItem(placement: .navigation) { listModeMenu }

        // `.primaryAction` still flows from the column's leading edge; a flexible spacer is what
        // pushes the trailing cluster to the column's right edge.
        FlexibleToolbarSpacer()

        ToolbarItemGroup(placement: .primaryAction) {
            markAllReadButton
        }
    }
    #endif

    private var listModeMenu: some View {
        // The view options menu: the list mode, then the sort order in a section of its own (as
        // Photos' filter button holds both). The button wears the filter symbol, filled whenever the
        // mode narrows the list (`listModeButtonSymbol`); each mode keeps its own icon beside its item
        // (`listModeSymbol`). Toggles rather than an inline Picker: a Picker option's own `.disabled`
        // is not honored inside a menu, so Starred stayed choosable under the Starred filter. The mode
        // items match the View menu (`HomeCommands`), which also keeps its own sort toggle.
        Menu {
            ForEach(Self.listModes, id: \.self) { mode in
                Toggle(L(Self.listModeLabelKey(mode)), systemImage: Self.listModeSymbol(mode), isOn: Binding(
                    get: { ArticleListModeKt.listModeChecked(item: mode, mode: home.listMode, starredModeAvailable: home.starredModeEnabled) },
                    set: { if $0 { home.viewModel.setMode(target: mode) } }
                ))
                .disabled(mode == .starred && !home.starredModeEnabled)
            }
            Section(L("home_sort_section")) {
                ForEach([true, false], id: \.self) { newestFirst in
                    Toggle(L(newestFirst ? "home_sort_newest" : "home_sort_oldest"), isOn: Binding(
                        get: { home.newestFirst == newestFirst },
                        set: { if $0 { home.viewModel.setNewestFirst(newestFirst: newestFirst) } }
                    ))
                }
            }
            // Search results keep their own fixed order (FTS rank), as the View menu's toggle does.
            .disabled(home.searchActive)
        } label: {
            Label(
                L(Self.listModeLabelKey(home.listMode)),
                systemImage: Self.listModeButtonSymbol(home.listMode)
            )
            #if os(iOS)
            // A filter symbol reads as a menu by itself, so no chevrons; still a `Label` so the
            // overflow menu at a narrow width gets the row's title.
            .labelStyle(.iconOnly)
            #else
            .labelStyle(.titleAndIcon)
            #endif
        }
        .help(L("home_list_mode"))
        .accessibilityLabel(L("home_list_mode"))
        .accessibilityValue(L(Self.listModeLabelKey(home.listMode)))
    }

    private var markAllReadButton: some View {
        Button {
            home.viewModel.markAllRead()
        } label: {
            Label(L("home_mark_all_read"), systemImage: "checkmark.circle")
        }
        .help(L("home_mark_all_read"))
    }

    // MARK: - Content / empty states

    #if os(iOS)
    /// Names the selection being shown (and, during a search, the one being narrowed). Not a
    /// navigation title: see `ArticleListHeader`. Each state of `content` places it itself: a
    /// scrolling list (`articleList`, `noArticlesView`) carries it inside itself, since SwiftUI hangs
    /// a `.refreshable` list's refresh control from the navigation bar, and a header between the two
    /// leaves the control overlapping the header and flickering in and out as the list is pulled
    /// (iOS 26); every other state goes through `headed`.
    private var listHeader: some View {
        ArticleListHeader(title: home.articleListTitle, icon: home.articleListIcon)
    }

    /// Large from iOS 26, inline before it. iOS 26's toolbar is translucent, with a scroll-edge
    /// effect, and `listHeader`'s opaque band stands out under it, so the standard large title names
    /// the list there instead — collapsing into the bar, or dropped by it when the column is too
    /// narrow. Before iOS 26 the bar is opaque on scroll and `listHeader` sits under it unremarked,
    /// while a large title collapses out of sight in a bar too crowded for an inline one.
    private static var titleDisplayMode: NavigationBarItem.TitleDisplayMode {
        showsListHeader ? .inline : .large
    }

    /// The identity `body` gives `content`: a change rebuilds the list (see `body`).
    private var listIdentity: ListIdentity {
        ListIdentity(remountGeneration: remountGeneration, attachedToWindow: attachedToWindow)
    }

    /// Whether the bar's principal item names the list: the list is what `content` shows and its
    /// heading row has scrolled away (`ArticleListBarTitle`). Every other state of `content` shows
    /// its heading through `headed`, so the bar names nothing there.
    private var barShowsTitle: Bool {
        barTitle.showsTitle(listShown: contentState.showsArticleList, current: listIdentity)
    }

    /// The id of `articleList`'s heading row, for the reset to the top on a filter switch.
    private static let listHeaderRowId = "articleListHeader"

    /// Whether `listHeader` heads the list — before iOS 26 only. The one place that decides between
    /// the heading and the standard large title: `titleDisplayMode`, the bar's principal item and
    /// the scroll reset all follow it.
    private static var showsListHeader: Bool {
        if #available(iOS 26, *) { false } else { true }
    }
    #endif

    /// A state of `content` that does not scroll, with `listHeader` above it on iOS.
    @ViewBuilder
    private func headed(_ state: some View) -> some View {
        #if os(iOS)
        if Self.showsListHeader {
            VStack(spacing: 0) {
                listHeader
                state
            }
        } else {
            state
        }
        #else
        state
        #endif
    }

    /// What `content` shows. One value, so `content` and the bar's title (`barShowsTitle`) cannot
    /// disagree about whether `articleList` is the one on screen.
    private enum ContentState {
        /// Nothing yet: the lists, the rows or a search are still on their way.
        case blank
        case noFeeds
        case searchTooShort
        case noSearchResults
        case noArticles
        case searchResults
        case articles

        /// Whether `articleList` is the state shown.
        var showsArticleList: Bool { self == .searchResults || self == .articles }
    }

    private var contentState: ContentState {
        // Nothing until the lists have loaded, rather than flashing "no feeds" / "no articles"
        // before the first emission (`ArticleListBody.pending`).
        if home.listBody == .pending { return .blank }
        if home.listBody == .noFeeds { return .noFeeds }
        if home.searchActive {
            // A query with no 2+-character word (`searchTerms`, shared with the trigram/`LIKE` split
            // `FtsSearch` makes — resolved once per query change as `searchQueryHasTerms`) is "too
            // short"; an in-flight search with nothing yet shows nothing at all, rather than flashing
            // "no results" between keystrokes; only a *settled* empty result set is "no results".
            // Mirrors Compose's own `emptyContent` `when` in `ArticleListPane.kt`.
            if !home.searchQueryHasTerms { return .searchTooShort }
            if home.searching && !home.hasSearchResults { return .blank }
            return home.hasSearchResults ? .searchResults : .noSearchResults
        }
        if displayedRows.isEmpty {
            // Nothing while the rows are still on their way: being built, or held by the view model
            // but not yet received here (`articleListCatchingUp`; `listHasRows` is observed, so the
            // view is woken whichever way it settles).
            let catchingUp = home.articleRowsBuilding
                || ArticleListModelKt.articleListCatchingUp(
                    displayedRows: Int32(displayedRows.count), viewModelHasRows: home.listHasRows
                )
            return catchingUp ? .blank : .noArticles
        }
        return .articles
    }

    @ViewBuilder
    private var content: some View {
        switch contentState {
        case .blank:
            headed(Color.clear)
        case .noFeeds:
            headed(ContentUnavailableView {
                Label(L("home_no_feeds"), systemImage: "tray")
            } actions: {
                Button(L("home_add_feed")) { dialogs.isAddingFeed = true }
            })
        case .searchTooShort:
            headed(ContentUnavailableView(
                L("home_search_too_short"),
                systemImage: "magnifyingglass"
            ))
        case .noSearchResults:
            headed(noSearchResultsView)
        case .noArticles:
            noArticlesView
        case .searchResults:
            articleList
        case .articles:
            articleList
                .modifier(PullToRefreshModifier(home: home))
        }
    }

    private static let listModes: [ArticleListMode] = [.all, .unread, .starred]

    /// The label naming `mode` on the toolbar's mode menu (Compose: `listModeLabel`).
    private static func listModeLabelKey(_ mode: ArticleListMode) -> String {
        switch mode {
        case .all: "home_list_mode_all"
        case .unread: "home_list_mode_unread"
        case .starred: "home_list_mode_starred"
        }
    }

    /// The list mode menu button's icon: the filter symbol, filled while `mode` narrows the list
    /// (as Mail's filter button). Unfiltered, iOS drops the circle, since its toolbar already draws
    /// the button inside a glass circle; macOS's toolbar draws no such shape, so it keeps it.
    static func listModeButtonSymbol(_ mode: ArticleListMode) -> String {
        guard mode == .all else { return "line.3.horizontal.decrease.circle.fill" }
        #if os(iOS)
        return "line.3.horizontal.decrease"
        #else
        return "line.3.horizontal.decrease.circle"
        #endif
    }

    /// The icon for `mode`'s menu item: the app's unread dot and star, in the same circle as All's
    /// filter icon.
    private static func listModeSymbol(_ mode: ArticleListMode) -> String {
        switch mode {
        case .all: "line.3.horizontal.decrease.circle"
        case .unread: "circle.inset.filled"
        case .starred: "star.circle.fill"
        }
    }

    /// Scrollable on iOS so an empty list can still be pulled to refresh (`external-spec.md` §9:
    /// "The gesture works on an empty list too", e.g. unread-only with nothing unread).
    @ViewBuilder
    private var noArticlesView: some View {
        // Starred mode can narrow a list to nothing; say so and offer the way out (as Compose does).
        let emptyState = home.listMode == .starred
            ? AnyView(ContentUnavailableView {
                Label(L("home_no_starred_articles"), systemImage: "star")
            } actions: {
                Button(L("home_show_all_articles")) { home.viewModel.setMode(target: .all) }
            })
            : AnyView(ContentUnavailableView(L("home_no_articles"), systemImage: "doc.text"))
        #if os(iOS)
        GeometryReader { geometry in
            ScrollView {
                if Self.showsListHeader {
                    VStack(spacing: 0) {
                        listHeader
                            .onSizeChanged { emptyListHeaderHeight = $0.height }
                        emptyState.frame(
                            width: geometry.size.width,
                            height: max(0, geometry.size.height - emptyListHeaderHeight)
                        )
                    }
                } else {
                    emptyState.frame(width: geometry.size.width, height: geometry.size.height)
                }
            }
            .modifier(PullToRefreshModifier(home: home))
        }
        #else
        emptyState
        #endif
    }

    /// A secondary line pointing at "All Feeds" only when the search is actually narrowed to
    /// something less than that — switching to All wouldn't change anything otherwise. Mirrors
    /// Compose's own `NoSearchResultsHint` (`ArticleListPane.kt`).
    private var noSearchResultsView: some View {
        VStack(spacing: 4) {
            Text(L("home_search_no_results")).font(.caption).foregroundStyle(.secondary)
            if !isAllFeedsFilter {
                Text(L("home_search_try_all_feeds")).font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    private var isAllFeedsFilter: Bool {
        if case .all = onEnum(of: home.filter) { return true }
        return false
    }

    /// The selected row as the list draws it — see `CompactArticleSelection`.
    private var displayedSelectedId: String? {
        CompactArticleSelection.displayedId(selectedId: home.selectedArticleId, articleListIsTopmost: articleListIsTopmost)
    }

    private func rowView(_ article: ArticleRowModel, selectedId: String?, paneFocused: Bool) -> ArticleRowView {
        ArticleRowView(
            model: article,
            isSelected: article.id == selectedId,
            isReturnFlashing: article.id == returnFlashId,
            paneFocused: paneFocused,
            viewModel: home.viewModel,
            onSelect: {
                focusedPane.wrappedValue = .articleList
                home.selectArticle(article.row)
                onOpenArticle()
            },
            onContextMenuSelect: { selectForContextMenu(article.row) },
            onCopyUrl: { home.copyArticleUrl(url: article.url, articleId: article.id) }
        )
    }

    #if os(macOS)
    private var articleList: some View {
        let selectedId = displayedSelectedId
        let paneFocused = focusedPane.wrappedValue == .articleList && windowIsKey
        return ArticleTableView(
            rows: displayed,
            selectedId: selectedId,
            revealRequest: home.selectionRevealRequests,
            resetGeneration: tableResetGeneration,
            scrollRequest: tableScrollRequest,
            contextMenuSelectionTracker: contextMenuSelectionTracker,
            makeRow: { rowView($0, selectedId: selectedId, paneFocused: paneFocused) },
            onBackgroundClick: { focusedPane.wrappedValue = .articleList },
            onVisibleIdsChanged: { reportVisible($0) }
        )
        // Rows scroll under the toolbar, as they did in the `List`; the table insets its content by
        // the toolbar's height itself.
        .ignoresSafeArea(edges: .top)
        .modifier(SoftTopScrollEdge())
        // SwiftUI hands a representable's focus to its AppKit views — the table takes the first
        // responder and passes every key on to `HomeView`'s handler (`ArticleNSTableView`). The
        // focus binding sits here rather than on the container in `body`, which is only focusable
        // through a focusable child such as iOS's `List`. `.edit`, because the default (`.activate`)
        // follows the system's keyboard-navigation setting, off by default.
        .focusable(interactions: .edit)
        .focusEffectDisabled()
        .focused(focusedPane, equals: .articleList)
    }
    #else
    private var articleList: some View {
        let selectedId = displayedSelectedId
        let paneFocused = focusedPane.wrappedValue == .articleList && windowIsKey
        // The `List` these reports belong to: a rebuilt list's (see `body`) is a different one.
        let owner = listIdentity
        return List {
            if Self.showsListHeader {
                // The first row, scrolling away with the rows; `barTitle` tracks it.
                listHeader
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .listRowInsets(EdgeInsets())
                    .listRowSeparator(.hidden)
                    .id(Self.listHeaderRowId)
                    .onAppear { barTitle.headerAppeared(in: owner, current: listIdentity) }
                    .onDisappear { barTitle.headerDisappeared(from: owner, current: listIdentity) }
            }
            ForEach(displayedRows) { article in
                articleRow(article, selectedId: selectedId, paneFocused: paneFocused)
            }
        }
        .listStyle(.plain)
        // No extra gap above the header, so it sits where it does over an empty list.
        .contentMargins(.top, Self.showsListHeader ? 0 : nil, for: .scrollContent)
    }

    private func articleRow(_ article: ArticleRowModel, selectedId: String?, paneFocused: Bool) -> some View {
        rowView(article, selectedId: selectedId, paneFocused: paneFocused)
            .equatable()
            // No horizontal inset of our own: the List's own row padding already supplies the
            // card's gap from the pane edge (on macOS, where this list used to run too, 8pt —
            // Compose's `listRowHorizontalMargin()`); adding 8 on top of it doubled that gap.
            .listRowInsets(EdgeInsets(top: 2, leading: 0, bottom: 2, trailing: 0))
            .listRowSeparator(.hidden)
            .onAppear {
                visibleRows.appearedIds.insert(article.id)
                scheduleReportVisible()
            }
            .onDisappear {
                visibleRows.appearedIds.remove(article.id)
                scheduleReportVisible()
            }
    }

    /// Schedules a debounced report of visible article ids. Rapid `onAppear`/`onDisappear` pairs
    /// from a single scroll gesture are coalesced into one `markArticlesSeen` call after a short
    /// delay, mirroring Compose's own `distinctUntilChanged` behaviour.
    private func scheduleReportVisible() {
        visibleRows.reportTask?.cancel()
        visibleRows.reportTask = Task {
            try? await Task.sleep(for: .milliseconds(50))
            guard !Task.isCancelled else { return }
            let indexById = displayed.indexById
            let ordered = visibleRows.appearedIds
                .compactMap { id in indexById[id].map { (index: $0, id: id) } }
                .sorted { $0.index < $1.index }
                .map(\.id)
            reportVisible(ordered)
        }
    }
    #endif

    /// Reports the rows on screen, in display order. Suppressed during search: the visible rows are
    /// search results, not the underlying filter's own list, and reporting them as "seen" would
    /// corrupt `newArticleCount`'s bookkeeping once the search closes (`ArticleListPane.kt`'s own
    /// guard).
    private func reportVisible(_ ids: [String]) {
        guard !home.searchActive else { return }
        home.viewModel.markArticlesSeen(ids: ids)
    }

    // MARK: - Row

    private func selectForContextMenu(_ article: ArticleListRow) {
        if home.selectedArticleId != article.id {
            home.selectArticle(article)
        }
    }

    // MARK: - New articles pill

    @ViewBuilder
    private func newArticlesPill(jump: @escaping () -> Void) -> some View {
        Button {
            home.viewModel.markAllArticlesSeen()
            jump()
        } label: {
            PillLabel(
                title: LF("home_new_articles", Int64(home.newArticleCount)),
                systemImage: home.newestFirst ? "arrow.up" : "arrow.down"
            )
        }
        .buttonStyle(.plain)
        .padding(.top, home.newestFirst ? PillLabel.edgeInset : 0)
        .padding(.bottom, home.newestFirst ? 0 : PillLabel.edgeInset)
    }

    #if os(iOS)
    /// Whether the list is showing rows — see `ArticleListScrollGate.listShowsRows`.
    private var showsRows: Bool {
        ArticleListScrollGate.listShowsRows(
            bodyIsContent: home.listBody == .content,
            rowCount: displayedRows.count,
            searchActive: home.searchActive,
            searchShowsList: home.searchQueryHasTerms && home.hasSearchResults
        )
    }

    /// Only a row the list shows: a scroll sent while it does not exist would be lost.
    private func rowExists(_ id: String) -> Bool { showsRows && displayed.indexById[id] != nil }

    /// Every scroll but the pill's goes through here: the pill can only be tapped on screen.
    private func requestScroll(_ target: ArticleListScrollGate.Target, _ proxy: ScrollViewProxy) {
        guard let target = scrollGate.request(target, rowExists: rowExists) else { return }
        carryOut(target, proxy)
    }

    private func requestScrollAfterUpdate(_ target: ArticleListScrollGate.Target, _ proxy: ScrollViewProxy) {
        Task {
            await Task.yield()
            requestScroll(target, proxy)
        }
    }

    private func carryOut(_ target: ArticleListScrollGate.Target, _ proxy: ScrollViewProxy) {
        guard !target.onlyIfOffScreen || !visibleRows.appearedIds.contains(target.rowId) else { return }
        // Before iOS 26 the heading row sits above the first article, so a scroll to the first
        // article goes to the heading instead — a filter switch's reset then shows the new filter's name.
        if Self.showsListHeader, target.rowId == displayedRows.first?.id {
            proxy.scrollTo(Self.listHeaderRowId, anchor: .top)
        } else {
            proxy.scrollTo(target.rowId)
        }
    }

    private func scrollToFreshEnd(_ proxy: ScrollViewProxy) {
        guard let target = home.newestFirst ? displayedRows.first?.id : displayedRows.last?.id else { return }
        proxy.scrollTo(target, anchor: home.newestFirst ? .top : .bottom)
    }
    #endif
}

/// Pull-to-refresh on iOS (`external-spec.md` §9), scoped to the current selection's feeds and held
/// up until the refresh itself finishes (not the sync that follows) — see `HomeObservable.pullToRefresh`. macOS has no pull
/// gesture, as Compose's desktop list has none.
private struct PullToRefreshModifier: ViewModifier {
    let home: HomeObservable

    func body(content: Content) -> some View {
        #if os(iOS)
        if PullToRefreshAvailability.isAvailable(searchActive: home.searchActive, hasFeeds: home.hasFeeds) {
            content.refreshable { await home.pullToRefresh() }
        } else {
            content
        }
        #else
        content
        #endif
    }
}

#if os(iOS)
/// What `ArticleListView` rebuilds its list on — see `remountGeneration` and `attachedToWindow`.
private struct ListIdentity: Hashable {
    let remountGeneration: Int
    let attachedToWindow: Bool
}

/// The article list's on-screen rows and its pending visible-row report. A plain class held in
/// `@State` rather than `@State` values of their own: every row scrolling in or out writes to it,
/// and nothing here is drawn, so those writes must not invalidate the list's body.
@MainActor
private final class VisibleRowTracker {
    var appearedIds: Set<String> = []
    /// Rapid `onAppear`/`onDisappear` pairs from scrolling are coalesced into a single report after
    /// a short delay — mirroring Compose's `snapshotFlow { ... }.distinctUntilChanged()` behaviour.
    var reportTask: Task<Void, Never>?
}
#endif
