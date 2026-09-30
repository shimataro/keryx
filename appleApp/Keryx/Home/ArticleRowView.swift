import KeryxShared
import SwiftUI

/// One article-list row. A `View` of its own — rather than a method of `ArticleListView` — and
/// `Equatable` over exactly what it draws, so a selection or focus change re-renders only the rows
/// whose `isSelected`/`paneFocused` actually changed instead of every visible row. It deliberately
/// takes no `HomeObservable`: reading one of its properties here would tie every row to it again.
struct ArticleRowView: View, Equatable {
    let model: ArticleRowModel
    let isSelected: Bool
    /// Whether the row shows the brief gray highlight of a collapsed list returned to from the reader.
    let isReturnFlashing: Bool
    /// Whether the article list holds the pane focus in the key window. Only macOS dims the
    /// selection without it; a touch-first iOS keeps one selection color, as Android does.
    let paneFocused: Bool
    /// Not observed, only called — actions reach `HomeViewModel` directly.
    let viewModel: HomeViewModel
    let onSelect: () -> Void
    let onContextMenuSelect: () -> Void

    #if os(macOS)
    private static let strongSelectionFill = Color(nsColor: .selectedContentBackgroundColor)
    private static let dimmedSelectionFill = Color(nsColor: .unemphasizedSelectedContentBackgroundColor)
    #else
    /// UIKit has no content-selection colors, so the selection is Keryx's own teal — darker than
    /// `AccentColor` in dark mode, so white text on it stays readable.
    private static let strongSelectionFill = Color("SelectionColor")
    #endif
    /// The neutral gray a UIKit list cell highlights in, for the return flash.
    #if os(iOS)
    private static let returnFlashFill = Color(uiColor: .systemGray4)
    #else
    private static let returnFlashFill = Color(nsColor: .unemphasizedSelectedContentBackgroundColor)
    #endif

    /// The closures are rebuilt by every parent evaluation and never compared: they only forward to
    /// actions keyed by this row's own article, which `model` already covers.
    nonisolated static func == (lhs: ArticleRowView, rhs: ArticleRowView) -> Bool {
        MainActor.assumeIsolated {
            lhs.model == rhs.model && lhs.isSelected == rhs.isSelected
                && lhs.isReturnFlashing == rhs.isReturnFlashing && lhs.paneFocused == rhs.paneFocused
        }
    }

    var body: some View {
        Button(action: onSelect) {
            // Same treatment as Compose's `onPrimary`: on the strong (focused) selection fill the
            // text turns light; on the dimmed (unfocused) one it keeps its ordinary colors.
            let onStrongSelection = showsStrongSelection
            HStack(alignment: .center, spacing: 0) {
                // Fixed slot, always reserved, so the title never shifts when the dot or star appears.
                ZStack {
                    if !model.isRead {
                        Circle().fill(onStrongSelection ? Color.white : Color.accentColor).frame(width: 8, height: 8)
                    }
                    if model.isStarred {
                        Image(systemName: "star.fill")
                            .font(.system(size: 12))
                            .foregroundStyle(.yellow)
                            .frame(maxHeight: .infinity, alignment: .top)
                    }
                }
                .frame(width: 14)
                FaviconView(url: model.faviconUrl, letter: model.title?.first, blankWithoutUrl: true)
                    .frame(width: 32, height: 32)
                    .clipShape(RoundedRectangle(cornerRadius: 4))
                    .padding(.leading, 6)
                VStack(alignment: .leading, spacing: 2) {
                    Text(titleText)
                        .font(model.isRead ? .body : .body.bold())
                        .foregroundStyle(onStrongSelection ? AnyShapeStyle(Color.white) : AnyShapeStyle(model.isRead ? HierarchicalShapeStyle.secondary : HierarchicalShapeStyle.primary))
                        .lineLimit(2, reservesSpace: true)
                    HStack(spacing: 6) {
                        if let feedTitle = model.feedTitle {
                            Text(feedTitle).lineLimit(1)
                        }
                        Spacer(minLength: 8)
                        Text(model.timestamp).lineLimit(1)
                    }
                    .font(.caption)
                    .foregroundStyle(onStrongSelection ? AnyShapeStyle(Color.white.opacity(0.8)) : AnyShapeStyle(HierarchicalShapeStyle.secondary))
                }
                .padding(.leading, 10)
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 10)
            // A plain-style button only hit-tests what it draws; without this the Spacer and the
            // padding are dead zones.
            .contentShape(Rectangle())
            .background {
                if showsStrongSelection {
                    RoundedRectangle(cornerRadius: 6).fill(Self.strongSelectionFill)
                } else if isSelected {
                    #if os(macOS)
                    RoundedRectangle(cornerRadius: 6).fill(Self.dimmedSelectionFill)
                    #endif
                } else if isReturnFlashing {
                    RoundedRectangle(cornerRadius: 6).fill(Self.returnFlashFill)
                }
            }
        }
        .buttonStyle(.plain)
        .selectsOnContextMenu(id: model.id, perform: onContextMenuSelect)
        .contextMenu {
            // Opening the menu selects the row first, matching Compose's own `onOpen = onClick`
            // (`ArticleRowComponents.kt`) — the actual selection runs on a right-click/Control-
            // click via `.selectsOnContextMenu` above, not as a side effect of this builder (see
            // `ContextMenuSelectionTracker`'s own doc for why).
            Button(L(model.isStarred ? "article_unstar" : "article_star")) {
                viewModel.toggleStar(article: model.row)
            }
            Button(L(model.isRead ? "article_mark_as_unread" : "article_mark_as_read")) {
                viewModel.toggleRead(article: model.row)
            }
            Button(L("article_copy_url")) {
                copyToPasteboard(model.url)
            }
            .disabled(!ArticleListModelKt.hasUsableUrl(url: model.url))
            Button(L("article_open_in_browser")) {
                openInBrowser(model.url)
            }
            .disabled(!ArticleListModelKt.hasUsableUrl(url: model.url))
        }
    }

    private var showsStrongSelection: Bool {
        #if os(macOS)
        isSelected && paneFocused
        #else
        isSelected
        #endif
    }

    private var titleText: AttributedString {
        guard let title = model.title else { return AttributedString(L("article_no_title")) }
        return model.highlightedTitle ?? AttributedString(title)
    }
}
