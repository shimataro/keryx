import KeryxShared
import SwiftUI
#if os(iOS)
import UIKit
#else
import AppKit
#endif

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

    /// The mark beside a provider's name. Dropbox and Google Drive show the provider's own, unmodified
    /// logo — the PNGs the Compose build bundles, referenced in place by `project.yml` and loaded by
    /// file name, drawn untinted because both providers forbid recoloring. OneDrive shows a generic
    /// cloud: Microsoft's trademark guidelines allow naming it in text but license its logo and icon
    /// separately, so the name beside the mark is the only OneDrive branding. The same split as
    /// Compose's `CloudBrandMark`.
    var brandIcon: Image {
        switch self {
        case .dropbox: return Image.bundledLogo("dropbox")
        case .googleDrive: return Image.bundledLogo("google_drive")
        // OneDrive, and any provider added later without its own approved logo.
        default: return Image(systemName: "cloud")
        }
    }
}

private extension Image {
    /// A logo PNG that `project.yml` copies into the bundle as a plain resource. `Image(_:)` only
    /// looks in asset catalogs, so the file is loaded by name through UIKit / AppKit instead.
    static func bundledLogo(_ name: String) -> Image {
        #if os(iOS)
        let logo = UIImage(named: name)
        #else
        let logo = Bundle.main.image(forResource: name)
        #endif
        guard let logo else {
            assertionFailure("\(name).png is missing from the app bundle (see appleApp/project.yml)")
            return Image(systemName: "cloud")
        }
        #if os(iOS)
        return Image(uiImage: logo)
        #else
        return Image(nsImage: logo)
        #endif
    }
}
