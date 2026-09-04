# Error Design

[日本語](error-design.ja.md)

## Design Philosophy

- **Exceptions** are for "unexpected errors"; the **`Result` type** is for "expected errors".
- Network and sync errors can occur frequently, so user notifications are kept restrained (notification center + inline expressions).
- Errors propagate to the UI layer via ViewModel `StateFlow` / `mutableStateOf`.

## Exception vs Result Type

| Case | Handling |
| --- | --- |
| Network error / timeout | Result type |
| Sync conflict / retry failure | Result type |
| Invalid feed URL | Result type |
| DB access failure / program bug | Exception |

## Error Types (`core/KeryxException.kt`, `core/Result.kt`)

```kotlin
sealed interface Result<out T> {
    data class Ok<out T>(val value: T) : Result<T>
    data class Err(val exception: KeryxException) : Result<Nothing>
}

sealed class KeryxException(message: String) : Exception(message)
```

Main subclasses: `FeedFetchException(statusCode)`, `FeedParseException`, `FeedDiscoveryException(candidates)`,
`FeedTimeoutException`, `FeedNotFoundException(isGone)`, `CloudAuthException`, `CloudStorageException`,
`SyncConflictException`, `SchemaVersionException(localVersion, cloudVersion)`, `CloudDataIncompatibleException`, `InvalidFeedUrlException`, `UpdateException(stage)`.

Helper extensions: `isOk` / `isErr` / `valueOrNull` / `errorOrNull` / `fold` / `onOk` / `onErr` / `map`.

## Layer-by-Layer Handling

- **DataSource layer**: Converts Ktor / SQLite exceptions into `KeryxException` subclasses. Never leaks raw exceptions upward.
  - `FeedFetcher`: Distinguishes 304 / 301·308 (permanent redirect, URL update) / 302·303·307 (temporary) / 410 / 404 / 4xx / timeout (retried a fixed number of times). If the response is an HTML page, `FeedDiscovery` looks for candidates and returns `FeedDiscoveryException`. Maximum 5 redirect loop guard.
  - `DropboxStorage`: 401/403 → `CloudAuthException`, 409 (upload) → `SyncConflictException`, 409 `path/not_found` (get_metadata) → does not exist.
  - `DatabaseMerger.merge`: classifies a merge failure from SQLite's **error code** (`SQLiteException.resultCode`, not message text) into `CloudDataIncompatibleException` (corrupt file, a constraint violation the cloud DB's own — laxer — schema allowed, or — only once `validateSchema` confirms the downloaded file doesn't match the app's schema — a foreign/legacy schema) or otherwise leaves it unchanged (transient / an app bug, or a schema error `validateSchema` couldn't confirm). See "Merge Failure Classification" in [sync-architecture.md](sync-architecture.md).
- **Repository layer**: Receives `Result` and applies business logic (retries, etc.).
- **ViewModel layer**: Converts `Result` into UI state.
- **UI layer**: `ui/i18n/ErrorMessages.kt`'s `userMessage(KeryxException)` only localizes a `KeryxException` into a message `String` for inline display (e.g. the add-feed error text); it does not dispatch to the notification center. Notification-center entries are populated separately, from the Repository layer via `NotificationMessages` (see below).

## Notification Center (`domain/NotificationCenter`)

- The notification center (history, manually dismissed) is the primary channel. Previous transient toasts have been replaced with more macOS-native inline expressions (copy shows a ✓ near the action source, OPML shows result text near the button, subscription shows the list appearance + in-dialog display), so desktop has no in-app snackbar. Android is the one platform-specific exception: it shows an M3 `Snackbar` for the URL-copy confirmation, but only below API 33 — from API 33 onward the OS already shows its own clipboard-copy confirmation, and a Snackbar there would just duplicate it (see `platform/PlatformOs.kt`'s `platformShowsOwnCopyConfirmation` and `ui/home/HomeCommon.kt`'s `LocalSnackbarHostState`). Android's second Snackbar use is
  `ui/home/HomeScreen.kt`'s `ForegroundAlertSnackbar`, described below.
- History is kept only for the session (not persisted to DB). Only things worth looking back at are recorded: errors and warnings, plus `INFO` for a new app version. **New articles are NOT recorded in the notification center** — `NewArticleNotifier` only feeds the OS notification (tray), because their arrival is already durably visible in the article list and the unread badges. This OS notification fires for both the background/startup refresh and a manual "Refresh All", via the shared `NewArticleNotifier.notifyIfEnabled` gate (new-article count > 0 and the `notificationEnabled` setting).
- Bell icon with badge (count). The bell lives in `ArticleListPane`'s header row, and — at a single-pane width, where the three panes become three separate screens and that header is not on the screen the app can launch into — also in `FeedListPane`'s (see the `ui-guidelines` skill for the exact rule; the two can never both draw one). `ArticleDetailPane` deliberately has none.
- Background-update warnings are recorded only in the notification center (because there is no UI context), and produce **no OS notification** — the OS notification channel is reserved for new articles (see above). On Android, `ForegroundAlertSnackbar` (`ui/home/HomeScreen.kt`) therefore also announces every `WARNING`/`ERROR` in a Snackbar the moment it is raised: a badge alone only reaches a user already looking at the pane hosting the bell, and these alerts are raised asynchronously by `runAndroidStartupTasks` and `FeedRefreshWorker`. `INFO` is excluded (a new-version notice is not an alert). Details:
  - Already-announced bookkeeping keys on `core/AppNotification.kt`'s `AlertKey` (level + message + action), not the notification id, which `NotificationCenter.addCoalescing` mints afresh on every recurrence — so a permanently failing sync announces itself once, not once per background attempt. Both go through the same helper so they cannot drift.
  - The collector is gated on the window actually having OS focus (`LocalWindowInfo`). While the app is backgrounded, the notification shade is down, or the settings dialog (a window of its own) is open, the alert simply waits — announcing it into a window nobody is looking at would time the Snackbar out unseen and consume it for good. It is surfaced once focus returns.
  - The Snackbar's action runs the notification's own next action (see the table below), through the same `notificationRowAction` the bell's rows use. `ResetCloudData` is announced without an action, since it must go through its own confirmation.
  - Only the newest of a simultaneous batch is announced (Material 3 shows one Snackbar at a time); the badge carries the count.
- **Every notification kept in the bell carries a next action** (`AppNotificationAction`). Clicking the row runs it; only `ResetCloudData` is excluded from row-clicking (destructive) and keeps its own inline confirm button. A clickable row signals itself the same way the settings screen's `LinkRow` does (primary color + underline on hover).

| Next action | Source | Behavior |
| --- | --- | --- |
| `OpenUrl(url)` | New-version notification, when no in-app update path applies here (`UpdatePlan.OpenReleasePage`/`NotOffered` — see "In-App Update" in [background-update.md](background-update.md)) | Opens the release page in the external browser |
| `ShowFeedDetail(feedId)` | Feed gone (410) / URL changed (301/308) | Selects that feed in the feed list (same as clicking it there). At a single-pane width the feed list is a screen of its own, so this advances to that feed's article list instead of navigating backwards onto a list whose selection isn't even painted — see `ui/home/HomePaneLayout.kt`'s `paneForFeedDetail` |
| `ShowSettingsTab(tabId)` | Sync errors (`SchemaVersionException` → `updates`, everything else → `cloud_sync`); a new-version notification when an in-app update path applies here (`updates`) | Opens the settings dialog on that tab. The `cloud_sync` tab shows `SyncRepository.lastSyncError` as the failure reason; the `updates` tab auto-checks for an update when opened |
| `ShowInfoDialog(detail)` | macOS translocated warning | Shows an explanatory dialog (cause + fix) without navigating |
| `ShowInfoDialog(detail)` | Token save fell back to the plaintext file (`CloudSession`) — the OS secure credential store was unreachable, so `TokenStorage.save()` returned `TokenSaveOutcome.PLAINTEXT_FILE` | Shows an explanatory dialog (cause + fix) without navigating |
| `ShowInfoDialog(detail)` | Token save persisted nothing (`CloudSession`) — neither the secure store nor the plaintext fallback file accepted the write (`TokenSaveOutcome.NOT_PERSISTED`), so the account has to be connected again after a restart | Shows an explanatory dialog (cause + fix) without navigating |
| `ResetCloudData` | `CloudDataIncompatibleException` | Dedicated inline button → confirmation dialog → archives the cloud DB under a timestamped name, then recreates it (see "Resetting (Archiving) Cloud Data" in [sync-architecture.md](sync-architecture.md)) |

`AppNotification(id, level: INFO|WARNING|ERROR, message, timestampMillis, action)`.
When emitting notifications from the Repository, text is localized via `NotificationMessages` (`getString`-based, Fake in tests) (hardcoding is prohibited).

## Error Severity and Notification Destinations (excerpt)

| Error | Auto-retry | Notification Center |
| --- | --- | --- |
| `FeedTimeoutException` / `FeedFetchException` | ✅ | ✅ |
| `FeedParseException` | ❌ | ✅ |
| `CloudStorageException` | ✅ | ✅ |
| `SyncConflictException` | ✅ (internal) | ❌ |
| `CloudAuthException` / `SchemaVersionException` | ❌ | ✅ |
| `CloudDataIncompatibleException` (corrupt / incompatible cloud DB / constraint-violating data) | ❌ (further **automatic** syncs are suspended entirely — `SyncTrigger.AUTOMATIC` gate, see "Automatic-Sync Suspension" in [sync-architecture.md](sync-architecture.md) — until a reset or a successful manual sync) | ✅ |
| `FeedNotFoundException(isGone=true)` | ❌ | ✅ |
| `UpdateException` (check/download/verify/install failure) | ❌ (retried only via the user clicking Retry — the Updates settings tab or the tray's own item) | ❌ (surfaced there instead — see "In-App Update" in [background-update.md](background-update.md); only the informational "update available"/"ready to install" notices reach the bell, via `ShowSettingsTab`/`OpenUrl` above) |

## Constants (`core/Constants.kt`)

`SYNC_MAX_RETRY=3`, `FEED_TIMEOUT_RETRY_COUNT=1`, `SYNC_DEBOUNCE_MS=5000`,
`CONNECTION_TIMEOUT_MS=10000`, `READ_TIMEOUT_SECONDS_DEFAULT=30`, `MAX_REDIRECTS=5`,
`UPDATE_DOWNLOAD_SOCKET_TIMEOUT_MS=60000`.
