#if os(iOS)
import SwiftUI

/// The heading above the article list on iOS before 26: the selected item's icon and name, as
/// Android's narrow-layout header shows. Before iOS 26 a navigation title cannot do this job — an
/// inline one has no room left once the toolbar buttons fill the bar, and a large one collapses out of
/// sight in a bar too crowded for an inline one, and takes no icon. From iOS 26 the list takes the
/// standard large title instead, since under iOS 26's translucent toolbar this opaque band stands out
/// (`ArticleListView.showsListHeader`). It scrolls away with the rows, and the bar then shows the title
/// in its place (`ArticleListBarTitle`).
struct ArticleListHeader: View {
    let title: String
    let icon: SidebarRowIcon

    var body: some View {
        HStack(spacing: 8) {
            iconView
            Text(title)
                .font(.title3.weight(.semibold))
                .lineLimit(1)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 8)
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder
    private var iconView: some View {
        switch icon {
        case .symbol(let name):
            Image(systemName: name).font(.title3)
        case .favicon(let url):
            FaviconView(url: url, letter: title.first)
                .faviconFrame(24)
        case .tagColor(let hex):
            Circle().fill(colorFromHex(hex)).frame(width: 12, height: 12)
        }
    }
}
#endif
