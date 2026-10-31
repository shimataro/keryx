import KeryxShared

extension CloudStorageType {
    /// The provider's brand name — deliberately not localized, matching Compose's own
    /// `brandLabel()` (`CloudSyncTab.kt`), since a company name isn't translated. Providers are
    /// shown by this name alone, with no logo or icon: Microsoft licenses the OneDrive icon
    /// separately, and naming all three in text keeps them alike (see `docs/app-architecture.md`).
    var brandLabel: String {
        switch self {
        case .dropbox: return "Dropbox"
        case .googleDrive: return "Google Drive"
        case .onedrive: return "OneDrive"
        }
    }
}
