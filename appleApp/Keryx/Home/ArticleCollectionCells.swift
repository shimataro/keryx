#if os(iOS)
import SwiftUI
import UIKit

// The UIKit views `ArticleCollectionViewController` builds the iOS article list from.

/// Tells its controller when it is put into a window or taken out of one.
final class ArticleScrollingCollectionView: UICollectionView {
    var onWindowChange: (() -> Void)?

    override func didMoveToWindow() {
        super.didMoveToWindow()
        onWindowChange?()
    }
}

/// One reusable cell, hosting the row's SwiftUI view.
final class ArticleCell: UICollectionViewCell {
    private var shown: ArticleRowView?

    /// Shows `row`, re-rendering only when it differs from what the cell already shows.
    func show(_ row: ArticleRowView) {
        if let shown, shown == row { return }
        shown = row
        contentConfiguration = UIHostingConfiguration { ArticleCollectionCellContent(row: row) }.margins(.all, 0)
    }
}

/// The pinned header: `ArticleListHeader` on the list's own opaque background, which the rows scroll
/// under.
final class ArticleHeaderView: UICollectionReusableView {
    private var content: (UIView & UIContentView)?

    func show(_ header: ArticleListHeader) {
        let configuration = UIHostingConfiguration {
            header
                .frame(maxWidth: .infinity, alignment: .leading)
                .background(Color(uiColor: .systemBackground))
                .foregroundStyle(.primary)
        }
        .margins(.all, 0)
        if let content {
            content.configuration = configuration
            return
        }
        let view = configuration.makeContentView()
        view.translatesAutoresizingMaskIntoConstraints = false
        addSubview(view)
        NSLayoutConstraint.activate([
            view.topAnchor.constraint(equalTo: topAnchor),
            view.bottomAnchor.constraint(equalTo: bottomAnchor),
            view.leadingAnchor.constraint(equalTo: leadingAnchor),
            view.trailingAnchor.constraint(equalTo: trailingAnchor),
        ])
        content = view
    }
}
#endif
