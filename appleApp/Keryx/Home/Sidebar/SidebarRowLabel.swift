import SwiftUI

/// A sidebar row's content — its title (or in-place name editor), the error indicator and the icon —
/// without the list-specific decoration around it (selection/drop highlight, unread badge, context
/// menu, drag and drop), which each sidebar implementation adds itself.
struct SidebarRowLabel<IconPopover: View>: View {
    let title: String
    let icon: SidebarRowIcon
    var isErroring = false
    var isGone = false
    /// Shows a small spinner in the icon's place, keeping the icon's own size so the title never moves.
    var isRefreshing = false
    #if os(macOS)
    /// Replaces the title while the row is being renamed in place (macOS only: iOS renames in a sheet).
    var editor: InlineRenameField?
    #endif
    /// Tapping a tag's color dot — opens its color picker directly, matching Compose's own dot
    /// (`FeedListTagRow.kt`'s `clickable(onClickLabel = colorLabel)`), without also changing the list
    /// selection. `nil` leaves the dot inert.
    var onIconTap: (() -> Void)?
    /// The color of a `.symbol` icon — `nil` leaves it to the enclosing list's own styling.
    var symbolTint: Color?
    /// A popover anchored on the icon (the tag color picker), when the row presents one itself.
    var iconPopoverPresented: Binding<Bool>?
    @ViewBuilder var iconPopover: () -> IconPopover

    var body: some View {
        Label {
            HStack(spacing: 4) {
                #if os(macOS)
                if let editor {
                    editor
                } else {
                    Text(title).lineLimit(1)
                }
                #else
                Text(title).lineLimit(1)
                #endif
                if isErroring {
                    Spacer(minLength: 0)
                    // The hover tooltip (`.help`) only appears for a gone (410) feed, matching
                    // Compose's own `FeedErrorIndicator` (`FeedListRows.kt`) — an
                    // ordinary fetch error gets no tooltip, only the accessibility label below.
                    Group {
                        if isGone {
                            Image(systemName: "exclamationmark.circle.fill").help(L("home_feed_gone"))
                        } else {
                            Image(systemName: "exclamationmark.circle.fill")
                        }
                    }
                    .foregroundStyle(.orange)
                    .accessibilityLabel(L(isGone ? "home_feed_gone" : "home_feed_error"))
                }
            }
        } icon: {
            iconView
                .opacity(isRefreshing ? 0 : 1)
                .overlay {
                    if isRefreshing { RefreshingSpinner() }
                }
        }
        .accessibilityValue(isRefreshing ? L("home_feed_refreshing") : "")
    }

    @ViewBuilder
    private var iconView: some View {
        switch icon {
        case .symbol(let name):
            if let symbolTint {
                Image(systemName: name).foregroundStyle(symbolTint)
            } else {
                Image(systemName: name)
            }
        case .favicon(let url):
            FaviconView(url: url, letter: title.first)
                .frame(width: 16, height: 16)
                .clipShape(RoundedRectangle(cornerRadius: 4))
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
        isRefreshing: Bool = false,
        onIconTap: (() -> Void)? = nil,
        symbolTint: Color? = nil
    ) {
        self.init(
            title: title, icon: icon, isErroring: isErroring, isGone: isGone, isRefreshing: isRefreshing,
            onIconTap: onIconTap, symbolTint: symbolTint, iconPopoverPresented: nil, iconPopover: { EmptyView() }
        )
    }

    #if os(macOS)
    init(
        title: String,
        icon: SidebarRowIcon,
        isErroring: Bool = false,
        isGone: Bool = false,
        isRefreshing: Bool = false,
        editor: InlineRenameField?,
        onIconTap: (() -> Void)? = nil,
        symbolTint: Color? = nil
    ) {
        self.init(
            title: title, icon: icon, isErroring: isErroring, isGone: isGone, isRefreshing: isRefreshing, editor: editor,
            onIconTap: onIconTap, symbolTint: symbolTint, iconPopoverPresented: nil, iconPopover: { EmptyView() }
        )
    }
    #endif
}

/// The spinner a refreshing row shows in its icon's place — drawn smaller than the icon, since a
/// full-size ring reads heavier than a favicon of the same size. On a selected row's filled
/// background (`backgroundProminence` `.increased`) it turns light like the row's title, since its
/// default gray all but disappears against the fill.
private struct RefreshingSpinner: View {
    @Environment(\.backgroundProminence) private var backgroundProminence
    @Environment(\.colorScheme) private var colorScheme

    var body: some View {
        #if os(macOS)
        // The AppKit spinner ignores the foreground style and tint; only its appearance recolors it.
        ProgressView().controlSize(.mini)
            .environment(\.colorScheme, backgroundProminence == .increased ? .dark : colorScheme)
        #else
        // iOS's ProgressView has one fixed size (about 20pt); scaled to about 12pt.
        ProgressView().scaleEffect(0.6)
            .tint(backgroundProminence == .increased ? .white : nil)
        #endif
    }
}
