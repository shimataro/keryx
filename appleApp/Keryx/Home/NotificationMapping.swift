import KeryxShared

/// Localizes a `NotificationText` (a notification center row's own "what it says", as data — see
/// `error-design.md`'s "Shared code never holds localized prose"). The `updateAvailable`/
/// `updateReadyToInstall` cases are effectively unreachable in this app (no `updateModule` is
/// installed — the App Store/Sparkle update it instead, see `docs/app-architecture.md`'s "Apple
/// targets in `:shared`"), but are still handled for exhaustiveness.
func notificationText(_ text: NotificationText) -> String {
    switch onEnum(of: text) {
    case .feedGone(let t): return LF("feed_gone_message", t.feedTitle)
    case .feedUrlChanged(let t): return LF("feed_url_changed", t.feedTitle)
    case .syncFailed(let t): return errorKindMessage(t.reason)
    case .opmlImported(let t):
        return t.failed > 0
            ? LF("apple_opml_import_failed", Int64(t.failed))
            : LF("apple_opml_import_success", Int64(t.added))
    case .tokenStorageFallback: return L("notification_token_storage_fallback")
    case .tokenStorageNotPersisted: return L("notification_token_storage_not_persisted")
    case .appTranslocated: return L("notification_app_translocated")
    case .updateAvailable(let t): return LF("update_available_notification", t.version)
    case .updateReadyToInstall(let t): return LF("update_ready_notification", t.version)
    }
}

/// Localizes an `InfoDialogText` — the explanatory detail behind a `ShowInfoDialog` action. Not a
/// sealed-interface type (no `onEnum(of:)`) — it's a plain, argument-less Kotlin enum, so its cases
/// bridge as static properties rather than Swift `enum` cases.
func infoDialogText(_ text: InfoDialogText) -> String {
    if text == .appTranslocated { return L("notification_app_translocated_detail") }
    if text == .tokenStorageFallback { return L("notification_token_storage_fallback_detail") }
    if text == .tokenStorageNotPersisted { return L("notification_token_storage_not_persisted_detail") }
    return L("error_generic")
}

/// Localizes an `ErrorKind` — shared between the notification center and the Cloud Sync settings
/// tab (`CloudSyncSettingsTab`'s own `lastSyncError` display), so the mapping lives once here.
func errorKindMessage(_ kind: ErrorKind) -> String {
    switch kind {
    case .feedTimeout: return L("error_feed_timeout")
    case .feedFetch: return L("error_feed_fetch")
    case .feedParse: return L("error_feed_parse")
    case .feedGone: return L("error_feed_gone")
    case .feedNotFound: return L("error_feed_not_found")
    case .cloudAuth: return L("error_cloud_auth")
    case .cloudDataIncompatible: return L("error_cloud_data_incompatible")
    case .cloudStorage: return L("error_cloud_storage")
    case .syncConflict: return L("error_sync_conflict")
    case .schemaVersion: return L("error_schema_version")
    case .update: return L("error_update")
    case .generic: return L("error_generic")
    }
}
