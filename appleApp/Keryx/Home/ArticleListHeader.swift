#if os(iOS)
import SwiftUI

/// The heading above the article list on iOS: the selected item's icon and name, as Android's
/// narrow-layout header shows. A navigation title cannot do this job — an inline one has no room
/// left once the toolbar buttons fill the bar, and a large one is too big and takes no icon.
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
                .frame(width: 24, height: 24)
        case .tagColor(let hex):
            Circle().fill(colorFromHex(hex)).frame(width: 12, height: 12)
        }
    }
}
#endif
