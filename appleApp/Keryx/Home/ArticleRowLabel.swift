import KeryxShared
import SwiftUI

/// What an article row draws: the unread dot and star, the favicon, the title, the feed and date, and
/// the selection or return-flash fill behind them. `ArticleRowView` wraps it in its button, and the
/// iOS row menu shows it as the menu's preview (`ArticleRowContextMenu`), so the two always match.
/// It reads nothing from the SwiftUI environment, so a preview hosted outside the list draws the same.
struct ArticleRowLabel: View {
    let model: ArticleRowModel
    let isSelected: Bool
    /// Whether the row shows the brief gray highlight of a collapsed list returned to from the reader.
    let isReturnFlashing: Bool
    /// Whether the article list holds the pane focus in the key window (see `ArticleRowView`).
    let paneFocused: Bool

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

    // Looked up once rather than on every body evaluation of every row; the app's language cannot
    // change while it runs.
    private static let noTitleLabel = L("article_no_title")

    var body: some View {
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
            // Announced as the row's accessibility value instead (see `ArticleRowView.stateAccessibilityValue`).
            .accessibilityHidden(true)
            FaviconView(url: model.faviconUrl, letter: model.title?.first, blankWithoutUrl: true)
                .faviconFrame(32)
                .padding(.leading, 6)
            VStack(alignment: .leading, spacing: 2) {
                titleText
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

    private var showsStrongSelection: Bool {
        #if os(macOS)
        isSelected && paneFocused
        #else
        isSelected
        #endif
    }

    /// Only a search result's highlighted title needs an `AttributedString`; a plain title is set
    /// verbatim, skipping both the attributed copy and a localization lookup of the title text.
    private var titleText: Text {
        guard let title = model.title else { return Text(verbatim: Self.noTitleLabel) }
        if let highlighted = model.highlightedTitle { return Text(highlighted) }
        return Text(verbatim: title)
    }
}
