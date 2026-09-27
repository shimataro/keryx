import KeryxShared
import SwiftUI

/// The sidebar pane: All / Starred, folders (collapsible, with unread badges), unfoldered feeds,
/// and tags (expandable, with color + attached feeds) — see `external-spec.md` §9's "3-pane width"
/// and `docs/app-architecture.md`. Desktop/macOS is unconditionally the 3-pane steady state, so
/// this pane (and its permanent search field) is always on screen — there is no narrower-width
/// drawer variant to reproduce here (that only applies to Android; see M2's research notes).
struct FeedListView: View {
    let home: HomeObservable
    var focusedPane: FocusState<HomeFocusedPane?>.Binding

    var body: some View {
        List {
            Section {
                allRow
                starredRow
            }
            ForEach(sortedFolders, id: \.id) { folder in
                folderSection(folder)
            }
            if !unassignedFeeds.isEmpty {
                Section {
                    ForEach(unassignedFeeds, id: \.id) { feed in
                        feedRow(feed, instance: FeedListRowSelectionFeedInFolderGroup(feedId: feed.id))
                    }
                }
            }
            ForEach(sortedTags, id: \.id) { tag in
                tagSection(tag)
            }
        }
        .listStyle(.sidebar)
        .searchable(text: searchQueryBinding, placement: .sidebar, prompt: Text("Search"))
        .focused(focusedPane, equals: .feedList)
        .navigationTitle("Keryx")
    }

    private var searchQueryBinding: Binding<String> {
        Binding(
            get: { home.searchQuery },
            set: { newValue in
                home.viewModel.setSearchQuery(query: newValue)
                home.viewModel.setSearchBarVisible(visible: true)
            }
        )
    }

    // MARK: - All / Starred

    private var allRow: some View {
        row(
            title: "All Feeds",
            systemImage: "tray.full",
            unreadCount: home.totalUnread,
            instance: FeedListRowSelectionAll(),
        )
    }

    private var starredRow: some View {
        row(
            title: "Starred",
            systemImage: "star.fill",
            unreadCount: home.starredUnreadCount,
            instance: FeedListRowSelectionStarred(),
        )
    }

    // MARK: - Folders

    private var sortedFolders: [Folders] {
        home.folders.sorted { $0.sort_order < $1.sort_order }
    }

    private var unassignedFeeds: [Feeds] {
        home.feeds.filter { $0.folder_id == nil }.sorted { $0.sort_order < $1.sort_order }
    }

    private func feedsIn(folder: Folders) -> [Feeds] {
        home.feeds.filter { $0.folder_id == folder.id }.sorted { $0.sort_order < $1.sort_order }
    }

    @ViewBuilder
    private func folderSection(_ folder: Folders) -> some View {
        let isCollapsed = home.collapsedFolderIds.contains(folder.id)
        Section {
            Button {
                home.viewModel.toggleFolderCollapsed(folderId: folder.id)
            } label: {
                HStack {
                    Image(systemName: isCollapsed ? "chevron.right" : "chevron.down")
                        .foregroundStyle(.secondary)
                        .font(.caption)
                    Text(folder.name)
                    Spacer()
                    unreadBadge(home.unreadByFolder[folder.id] ?? 0)
                }
            }
            .buttonStyle(.plain)

            if !isCollapsed {
                ForEach(feedsIn(folder: folder), id: \.id) { feed in
                    feedRow(feed, instance: FeedListRowSelectionFeedInFolderGroup(feedId: feed.id))
                }
            }
        }
    }

    // MARK: - Tags

    private var sortedTags: [Tags] {
        home.tags.sorted { $0.sort_order < $1.sort_order }
    }

    private func feeds(taggedWith tag: Tags) -> [Feeds] {
        home.feeds
            .filter { home.feedTagMap[$0.id]?.contains(tag.id) ?? false }
            .sorted { $0.sort_order < $1.sort_order }
    }

    @ViewBuilder
    private func tagSection(_ tag: Tags) -> some View {
        let isExpanded = home.expandedTagIds.contains(tag.id)
        Section {
            Button {
                home.viewModel.toggleTagExpanded(tagId: tag.id)
            } label: {
                HStack {
                    Circle()
                        .fill(tagColor(tag.color))
                        .frame(width: 10, height: 10)
                    Text(tag.name)
                    Spacer()
                    unreadBadge(home.unreadByTag[tag.id] ?? 0)
                    Image(systemName: isExpanded ? "chevron.down" : "chevron.right")
                        .foregroundStyle(.secondary)
                        .font(.caption)
                }
            }
            .buttonStyle(.plain)

            if isExpanded {
                ForEach(feeds(taggedWith: tag), id: \.id) { feed in
                    feedRow(feed, instance: FeedListRowSelectionFeedInTag(feedId: feed.id, tagId: tag.id))
                }
            }
        }
    }

    private func tagColor(_ hex: String?) -> Color {
        guard let hex else { return .secondary }
        var value: UInt64 = 0
        Scanner(string: hex.trimmingCharacters(in: CharacterSet(charactersIn: "#"))).scanHexInt64(&value)
        let r = Double((value & 0xFF0000) >> 16) / 255
        let g = Double((value & 0x00FF00) >> 8) / 255
        let b = Double(value & 0x0000FF) / 255
        return Color(red: r, green: g, blue: b)
    }

    // MARK: - Rows

    @ViewBuilder
    private func feedRow(_ feed: Feeds, instance: FeedListRowSelection) -> some View {
        row(
            title: feed.custom_title ?? feed.title,
            faviconUrl: feed.favicon_url,
            unreadCount: home.unreadByFeed[feed.id] ?? 0,
            isErroring: feed.error_count > 0 || feed.last_error == ConstantsKt.FEED_ERROR_REASON_GONE,
            isGone: feed.last_error == ConstantsKt.FEED_ERROR_REASON_GONE,
            instance: instance,
        )
    }

    @ViewBuilder
    private func row(
        title: String,
        systemImage: String? = nil,
        faviconUrl: String? = nil,
        unreadCount: Int64,
        isErroring: Bool = false,
        isGone: Bool = false,
        instance: FeedListRowSelection,
    ) -> some View {
        let isPrimary = feedListRowSelectionsEqual(instance, home.selectedRowInstance)
        let isSecondary = !isPrimary && articleFiltersEqual(instance.filter, home.filter)

        Button {
            home.viewModel.selectFilter(filter: instance.filter, instance: instance)
        } label: {
            HStack {
                if let systemImage {
                    Image(systemName: systemImage).frame(width: 18)
                } else {
                    FaviconView(url: faviconUrl, letter: title.first)
                        .frame(width: 18, height: 18)
                }
                Text(title).lineLimit(1)
                Spacer()
                if isErroring {
                    Image(systemName: "exclamationmark.triangle.fill")
                        .foregroundStyle(.orange)
                        .help(isGone ? "This feed has been permanently removed by its publisher (410 Gone)" : "This feed failed to update")
                }
                unreadBadge(unreadCount)
            }
            .padding(.vertical, 2)
            .listRowBackground(
                isPrimary ? Color.accentColor.opacity(0.25)
                    : isSecondary ? Color.accentColor.opacity(0.10)
                    : Color.clear
            )
        }
        .buttonStyle(.plain)
    }

    @ViewBuilder
    private func unreadBadge(_ count: Int64) -> some View {
        if count > 0 {
            Text("\(count)")
                .font(.caption2)
                .foregroundStyle(.secondary)
        }
    }
}

/// Favicon with a letter-avatar fallback while loading or on failure — the SwiftUI equivalent of
/// the Compose app's Coil3 `AsyncImage` usage (`external-spec.md`'s "Both the feed list and article
/// list display favicons").
struct FaviconView: View {
    let url: String?
    let letter: Character?

    var body: some View {
        if let url, let parsed = URL(string: url) {
            AsyncImage(url: parsed) { phase in
                switch phase {
                case .success(let image):
                    image.resizable().scaledToFit()
                default:
                    fallback
                }
            }
        } else {
            fallback
        }
    }

    private var fallback: some View {
        Circle()
            .fill(Color.secondary.opacity(0.3))
            .overlay(
                Text(letter.map(String.init)?.uppercased() ?? "?")
                    .font(.caption2)
                    .foregroundStyle(.secondary)
            )
    }
}
