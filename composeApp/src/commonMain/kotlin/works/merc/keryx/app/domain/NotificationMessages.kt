package works.merc.keryx.app.domain

import works.merc.keryx.app.core.KeryxException

/**
 * Supplies localized text for notifications raised outside of Compose (feed
 * refresh, background updates, sync). Implemented over Compose Resources; faked
 * in tests. Keeps user-facing strings out of the repositories.
 */
interface NotificationMessages {
    suspend fun feedGone(feedTitle: String): String
    suspend fun feedUrlChanged(feedTitle: String): String
    suspend fun newArticles(count: Int): String

    /** Localized message for a failed cloud sync, keyed off the concrete [exception]. */
    suspend fun syncFailed(exception: KeryxException): String

    /** Localized message summarizing an OPML import (e.g. opened via a file association). */
    suspend fun opmlImported(added: Int, failed: Int): String

    /** Localized message for the notification-center row [UpdateRepository] posts when [check]
     * finds a newer release. */
    suspend fun updateAvailable(version: String): String

    /** Localized message for the notification-center row [UpdateRepository] replaces its "an
     * update is available" one with once [version] has finished downloading and verifying. */
    suspend fun updateReadyToInstall(version: String): String

    /**
     * Localized bell-row text for a cloud sign-in that could only be persisted in the plaintext
     * fallback file because the OS secure credential store was unreachable. No provider name is
     * threaded through: only one provider is ever connected at a time.
     */
    suspend fun tokenStorageFallback(): String

    /** The explanatory dialog body (cause + fix) behind [tokenStorageFallback]. */
    suspend fun tokenStorageFallbackDetail(): String

    /**
     * Localized bell-row text for a cloud sign-in that could not be persisted at all: neither the
     * OS secure credential store nor the plaintext fallback file accepted it, so it survives only
     * until the app exits and the account has to be connected again afterwards.
     */
    suspend fun tokenStorageNotPersisted(): String

    /** The explanatory dialog body (cause + fix) behind [tokenStorageNotPersisted]. */
    suspend fun tokenStorageNotPersistedDetail(): String
}
