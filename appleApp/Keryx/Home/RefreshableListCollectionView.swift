#if os(iOS)
import UIKit

/// A list `UICollectionView` that keeps its refresh control visible. The list layout's section
/// background (a decoration view stretched far above the content to cover the overscroll) is drawn
/// over the control, so the pull revealed only that background while the control spun behind it
/// (iOS 26). The collection view orders its own subviews, so the control is lifted by layer depth
/// instead; it sits above the content, so it never covers a cell.
class RefreshableListCollectionView: UICollectionView {
    override var refreshControl: UIRefreshControl? {
        didSet { refreshControl?.layer.zPosition = 1 }
    }
}
#endif
