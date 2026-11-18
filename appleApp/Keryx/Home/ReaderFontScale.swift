#if os(iOS)
import SwiftUI
import UIKit

/// The reader's font scale on iOS: how much the system's text size (Dynamic Type) enlarges body
/// text, relative to the default size. The rest of the iOS UI already follows Dynamic Type through
/// SwiftUI's text styles; the reader is a web view whose CSS sizes text in fixed units, so this
/// carries the same enlargement into its `font-size` (`ArticleHtmlTheme.fontScale`).
///
/// iOS has no font-size setting of its own — the system setting (including Control Center's
/// per-app text size) is the one control. macOS has no Dynamic Type, so its reader keeps
/// `LocalSettings.fontSizeScale` instead (see `ReaderPageView.readerFontScale`).
enum ReaderFontScale {
    /// The reference size scaled to get the ratio. `UIFontMetrics` treats any value it scales as the
    /// value at the default Dynamic Type size (`.large`), so the ratio is 1.0 there whatever this is,
    /// and it need not track the system's body size. It only affects rounding at the other sizes;
    /// the body style's own default (17 pt) makes the reader grow exactly as the app's body text does.
    private static let baseBodySize: CGFloat = 17

    /// The scale for `size`: 1.0 at the default size, below it for smaller sizes and above it for
    /// larger ones (including the accessibility sizes).
    static func scale(for size: DynamicTypeSize) -> Float {
        let traits = UITraitCollection(preferredContentSizeCategory: UIContentSizeCategory(size))
        let scaled = UIFontMetrics(forTextStyle: .body).scaledValue(for: baseBodySize, compatibleWith: traits)
        return Float(scaled / baseBodySize)
    }
}
#endif
