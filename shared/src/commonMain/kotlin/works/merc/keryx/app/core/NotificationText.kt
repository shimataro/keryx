package works.merc.keryx.app.core

/**
 * What a notification says, as data: which message plus its arguments. Shared code never holds
 * localized prose — each UI resolves this to its own string resources (the Compose app through
 * Compose Resources, the Apple app through its String Catalog), so a notification raised in
 * `:shared` reads correctly in whichever UI shows it. See `docs/error-design.md`.
 *
 * Equality is structural, which is what [AlertKey] relies on to treat a recurring notification as
 * "the same alert again".
 */
sealed interface NotificationText {
    /** A subscribed feed answered 410 Gone. */
    data class FeedGone(val feedTitle: String) : NotificationText

    /** A subscribed feed permanently redirected (301/308) and its URL was updated. */
    data class FeedUrlChanged(val feedTitle: String) : NotificationText

    /** A cloud sync failed for [reason] (only the sync-related [ErrorKind]s, else [ErrorKind.GENERIC]). */
    data class SyncFailed(val reason: ErrorKind) : NotificationText

    /** A newer release [version] is available. */
    data class UpdateAvailable(val version: String) : NotificationText

    /** Release [version] has been downloaded and verified. */
    data class UpdateReadyToInstall(val version: String) : NotificationText

    /**
     * A cloud sign-in is readable in the plaintext fallback file — the OS secure credential store
     * was unreachable, or a stale fallback copy could not be removed after a secure write.
     */
    data object TokenStorageFallback : NotificationText

    /** A cloud sign-in could not be persisted at all; it lasts only until the app exits. */
    data object TokenStorageNotPersisted : NotificationText

    /** The macOS app is running from a translocated (quarantined, read-only) location. */
    data object AppTranslocated : NotificationText
}

/** The explanatory dialog body (cause + fix) an [AppNotificationAction.ShowInfoDialog] opens. */
enum class InfoDialogText {
    TOKEN_STORAGE_FALLBACK,
    TOKEN_STORAGE_NOT_PERSISTED,
    APP_TRANSLOCATED,
}

/**
 * The user-facing category of a [KeryxException] — what each UI localizes into an error message.
 * [errorKind] is the single mapping from exception type to category.
 */
enum class ErrorKind {
    FEED_TIMEOUT,
    FEED_FETCH,
    FEED_PARSE,
    FEED_GONE,
    FEED_NOT_FOUND,
    CLOUD_AUTH,
    CLOUD_DATA_INCOMPATIBLE,
    CLOUD_STORAGE,
    SYNC_CONFLICT,
    SCHEMA_VERSION,
    UPDATE,
    GENERIC,
}

/** This exception's [ErrorKind]. */
val KeryxException.errorKind: ErrorKind
    get() = when (this) {
        is FeedTimeoutException -> ErrorKind.FEED_TIMEOUT
        is FeedFetchException -> ErrorKind.FEED_FETCH
        is FeedParseException -> ErrorKind.FEED_PARSE
        is FeedNotFoundException -> if (isGone) ErrorKind.FEED_GONE else ErrorKind.FEED_NOT_FOUND
        is CloudAuthException -> ErrorKind.CLOUD_AUTH
        is CloudDataIncompatibleException -> ErrorKind.CLOUD_DATA_INCOMPATIBLE
        is CloudStorageException -> ErrorKind.CLOUD_STORAGE
        is SyncConflictException -> ErrorKind.SYNC_CONFLICT
        is SchemaVersionException -> ErrorKind.SCHEMA_VERSION
        is UpdateException -> ErrorKind.UPDATE
        is FeedDiscoveryException -> ErrorKind.GENERIC
    }

/**
 * The [ErrorKind] a failed sync reports: only the categories a sync can actually produce keep
 * their own message; anything else reads as [ErrorKind.GENERIC].
 */
val KeryxException.syncErrorKind: ErrorKind
    get() = errorKind.takeIf { it in SYNC_ERROR_KINDS } ?: ErrorKind.GENERIC

private val SYNC_ERROR_KINDS = setOf(
    ErrorKind.CLOUD_AUTH,
    ErrorKind.SCHEMA_VERSION,
    ErrorKind.CLOUD_DATA_INCOMPATIBLE,
    ErrorKind.SYNC_CONFLICT,
    ErrorKind.CLOUD_STORAGE,
)
