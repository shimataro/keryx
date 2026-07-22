# Error Design

[日本語](error-design.ja.md)

## Design Philosophy

- **Exceptions** are for "unexpected errors"; the **`Result` type** is for "expected errors".
- Network and sync errors can occur frequently, so user notifications are kept restrained (snackbar + notification center).
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
`SyncConflictException`, `SchemaVersionException(localVersion, cloudVersion)`, `CloudDataIncompatibleException`, `InvalidFeedUrlException`.

Helper extensions: `isOk` / `isErr` / `valueOrNull` / `errorOrNull` / `fold` / `onOk` / `onErr` / `map`.

## Layer-by-Layer Handling

- **DataSource layer**: Converts Ktor / SQLite exceptions into `KeryxException` subclasses. Never leaks raw exceptions upward.
  - `FeedFetcher`: Distinguishes 304 / 301·308 (permanent redirect, URL update) / 302·303·307 (temporary) / 410 / 404 / 4xx / timeout (retried a fixed number of times). If the response is an HTML page, `FeedDiscovery` looks for candidates and returns `FeedDiscoveryException`. Maximum 5 redirect loop guard.
  - `DropboxStorage`: 401/403 → `CloudAuthException`, 409 (upload) → `SyncConflictException`, 409 `path/not_found` (get_metadata) → does not exist.
- **Repository layer**: Receives `Result` and applies business logic (retries, etc.).
- **ViewModel layer**: Converts `Result` into UI state.
- **UI layer**: `ui/i18n/ErrorMessages.kt`'s `userMessage(KeryxException)` converts `KeryxException` into a localized message and feeds it to the snackbar / notification center.

## Notification Center (`domain/NotificationCenter`)

- The notification center (history, manually dismissed) is the primary channel. Previous transient toasts have been replaced with more macOS-native inline expressions (copy shows a ✓ near the action source, OPML shows result text near the button, subscription shows the list appearance + in-dialog display), so in-app snackbars have been abolished.
- History is kept only for the session (not persisted to DB). In addition to errors and warnings, `INFO` (new articles from background updates) is also recorded.
- Bell icon with badge (count). Background-update warnings and new articles are recorded only in the notification center (because there is no UI context). New articles are recorded only for background updates; manual updates are shown via list / unread badge updates.

`AppNotification(id, level: INFO|WARNING|ERROR, message, timestampMillis)`.
When emitting notifications from the Repository, text is localized via `NotificationMessages` (`getString`-based, Fake in tests) (hardcoding is prohibited).

## Error Severity and Notification Destinations (excerpt)

| Error | Auto-retry | Snackbar | Notification Center |
| --- | --- | --- | --- |
| `FeedTimeoutException` / `FeedFetchException` | ✅ | ✅ | ✅ |
| `FeedParseException` | ❌ | ✅ | ✅ |
| `CloudStorageException` | ✅ | ✅ | ✅ |
| `SyncConflictException` | ✅ (internal) | ❌ | ❌ |
| `CloudAuthException` / `SchemaVersionException` | ❌ | ✅ | ✅ |
| `CloudDataIncompatibleException` (corrupt / incompatible cloud DB) | ❌ | ❌ | ✅ |
| `FeedNotFoundException(isGone=true)` | ❌ | ❌ | ✅ |

## Constants (`core/Constants.kt`)

`SYNC_MAX_RETRY=3`, `FEED_TIMEOUT_RETRY_COUNT=1`, `SYNC_DEBOUNCE_MS=5000`,
`CONNECTION_TIMEOUT_MS=10000`, `READ_TIMEOUT_SECONDS_DEFAULT=30`, `MAX_REDIRECTS=5`.
