import KeryxShared
import SwiftUI

extension CloudStorageType {
    /// The provider's brand name — deliberately not localized, matching Compose's own
    /// `brandLabel()` (`CloudSyncTab.kt`), since a company name isn't translated.
    var brandLabel: String {
        switch self {
        case .dropbox: return "Dropbox"
        case .googleDrive: return "Google Drive"
        case .onedrive: return "OneDrive"
        default: return ""
        }
    }

    /// Asset-catalog SVGs: Dropbox/Google Drive hand-converted from Compose's own VectorDrawables,
    /// OneDrive the official SVG behind Compose's `onedrive.png` (see each SVG's header comment).
    var brandIcon: Image {
        switch self {
        case .dropbox: return Image("dropbox")
        case .googleDrive: return Image("google_drive")
        case .onedrive: return Image("onedrive")
        default: return Image(systemName: "cloud")
        }
    }
}
