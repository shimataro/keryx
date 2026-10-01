#if os(macOS)
import SwiftUI

/// One macOS source-list row's label, highlight and unread badge. A `View` of its own, reading its
/// unread count in its own body, so a count ticking during a refresh or on every article read
/// re-evaluates the rows alone rather than `FeedListView` and the whole outline; and `Equatable`, so
/// the outline's own re-evaluations (a selection or structure change) skip every row whose inputs
/// are unchanged. The list-facing modifiers (tag, context menu, drag and drop) stay on the
/// outline's side, around this view.
struct SidebarSourceListRow<IconPopover: View>: View, Equatable {
    let home: HomeObservable
    let content: SidebarRowStaticContent
    let unread: SidebarUnreadSource
    let highlight: SidebarRowHighlight
    /// Replaces the title while the row is being renamed in place.
    var editor: InlineRenameField?
    /// A tag's color dot — see `SidebarRowLabel.onIconTap`.
    var onIconTap: (() -> Void)?
    var iconPopoverPresented: Binding<Bool>?
    @ViewBuilder var iconPopover: () -> IconPopover

    /// `home` is the same object for every row, and the closures only forward to actions keyed by
    /// what `content` already covers, so neither is compared. A row showing its name editor always
    /// counts as changed: the editor's closures carry the values it commits, which must stay current.
    /// The popover's presentation is compared by value, since a skipped body would never present it.
    nonisolated static func == (lhs: SidebarSourceListRow, rhs: SidebarSourceListRow) -> Bool {
        MainActor.assumeIsolated {
            lhs.content == rhs.content && lhs.unread == rhs.unread && lhs.highlight == rhs.highlight
                && lhs.editor == nil && rhs.editor == nil
                && lhs.iconPopoverPresented?.wrappedValue == rhs.iconPopoverPresented?.wrappedValue
        }
    }

    var body: some View {
        SidebarRowLabel(
            title: content.title,
            icon: content.icon,
            isErroring: content.isErroring,
            isGone: content.isGone,
            editor: editor,
            onIconTap: onIconTap,
            iconPopoverPresented: iconPopoverPresented,
            iconPopover: iconPopover
        )
        .sidebarRowHighlight(highlight)
        .badge(Int(unreadCount))
    }

    private var unreadCount: Int64 {
        switch unread {
        case .total: home.totalUnread
        case .starred: home.starredUnreadCount
        case .feed(let id): home.unreadByFeed[id] ?? 0
        case .folder(let id): home.unreadByFolder[id] ?? 0
        case .tag(let id): home.unreadByTag[id] ?? 0
        }
    }
}

extension SidebarSourceListRow where IconPopover == EmptyView {
    init(
        home: HomeObservable,
        content: SidebarRowStaticContent,
        unread: SidebarUnreadSource,
        highlight: SidebarRowHighlight,
        editor: InlineRenameField? = nil
    ) {
        self.init(
            home: home, content: content, unread: unread, highlight: highlight, editor: editor,
            onIconTap: nil, iconPopoverPresented: nil, iconPopover: { EmptyView() }
        )
    }
}
#endif
