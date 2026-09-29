import SwiftUI

/// What a sidebar row shows in its icon slot.
enum SidebarRowIcon: Equatable {
    /// All / Starred / a folder.
    case symbol(String)
    /// A feed: its favicon, or a letter avatar while loading or without one.
    case favicon(url: String?)
    /// A tag: its color dot.
    case tagColor(hex: String?)
}

/// A sidebar row's content — its title (or in-place name editor), the error indicator and the icon —
/// without the list-specific decoration around it (selection/drop highlight, unread badge, context
/// menu, drag and drop), which each sidebar implementation adds itself.
struct SidebarRowLabel<IconPopover: View>: View {
    let title: String
    let icon: SidebarRowIcon
    var isErroring = false
    var isGone = false
    /// Replaces the title while the row is being renamed in place.
    var editor: InlineRenameField?
    /// Tapping a tag's color dot — opens its color picker directly, matching Compose's own dot
    /// (`FeedListPane.kt`'s `clickable(onClickLabel = colorLabel)`), without also changing the list
    /// selection. `nil` leaves the dot inert.
    var onIconTap: (() -> Void)?
    /// A popover anchored on the icon (the tag color picker), when the row presents one itself.
    var iconPopoverPresented: Binding<Bool>?
    @ViewBuilder var iconPopover: () -> IconPopover

    var body: some View {
        Label {
            HStack(spacing: 4) {
                if let editor {
                    editor
                } else {
                    Text(title).lineLimit(1)
                }
                if isErroring {
                    Spacer(minLength: 0)
                    // The hover tooltip (`.help`) only appears for a gone (410) feed, matching
                    // Compose's own `FeedErrorIndicator` (`FeedListDragAndDrop.kt:655-676`) — an
                    // ordinary fetch error gets no tooltip, only the accessibility label below.
                    Group {
                        if isGone {
                            Image(systemName: "exclamationmark.triangle.fill").help(L("home_feed_gone"))
                        } else {
                            Image(systemName: "exclamationmark.triangle.fill")
                        }
                    }
                    .foregroundStyle(.orange)
                    .accessibilityLabel(L(isGone ? "home_feed_gone" : "home_feed_error"))
                }
            }
        } icon: {
            iconView
        }
    }

    @ViewBuilder
    private var iconView: some View {
        switch icon {
        case .symbol(let name):
            Image(systemName: name)
        case .favicon(let url):
            FaviconView(url: url, letter: title.first)
                .frame(width: 16, height: 16)
        case .tagColor(let hex):
            tagColorDot(hex)
        }
    }

    private func tagColorDot(_ hex: String?) -> some View {
        Circle()
            .fill(colorFromHex(hex))
            .frame(width: 10, height: 10)
            .contentShape(Circle())
            .onTapGesture { onIconTap?() }
            .popover(isPresented: iconPopoverPresented ?? .constant(false)) {
                iconPopover()
            }
            .accessibilityLabel(L("home_tag_color"))
    }
}

extension SidebarRowLabel where IconPopover == EmptyView {
    init(
        title: String,
        icon: SidebarRowIcon,
        isErroring: Bool = false,
        isGone: Bool = false,
        editor: InlineRenameField? = nil,
        onIconTap: (() -> Void)? = nil
    ) {
        self.init(
            title: title, icon: icon, isErroring: isErroring, isGone: isGone, editor: editor,
            onIconTap: onIconTap, iconPopoverPresented: nil, iconPopover: { EmptyView() }
        )
    }
}
