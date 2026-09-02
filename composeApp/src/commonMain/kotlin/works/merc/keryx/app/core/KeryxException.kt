package works.merc.keryx.app.core

/**
 * Base type for all expected errors carried by [Result].
 */
sealed class KeryxException(message: String) : Exception(message) {
    // Guaranteed non-null message (Throwable.message is nullable).
    val messageText: String get() = message ?: this::class.simpleName.orEmpty()
}

// --- Network / feed ---

class FeedFetchException(message: String, val statusCode: Int? = null) : KeryxException(message)

class FeedParseException(message: String) : KeryxException(message)

/** The URL pointed at an HTML page that advertises one or more feeds. */
class FeedDiscoveryException(val candidates: List<DiscoveredFeedLink>) :
    KeryxException("Feed links found on page")

class FeedTimeoutException : KeryxException("Feed request timed out")

/** 404 (not found) or 410 (gone, when [isGone] is true). */
class FeedNotFoundException(message: String, val isGone: Boolean = false) : KeryxException(message)

// --- Sync / cloud ---

class CloudAuthException(message: String) : KeryxException(message)

class CloudStorageException(message: String) : KeryxException(message)

/** Dropbox rev mismatch — another device wrote first. Retried internally. */
class SyncConflictException : KeryxException("Sync conflict detected")

class SchemaVersionException(val localVersion: Long, val cloudVersion: Long) :
    KeryxException("Schema version mismatch")

/**
 * The cloud sync DB cannot be used: it is corrupt (not a valid SQLite file / malformed), its
 * schema is incompatible with this app (foreign/legacy layout), or its data violates this app's
 * schema constraints (a UNIQUE/NOT NULL/FOREIGN KEY violation the cloud DB's own — laxer — schema
 * allowed). Permanent — retrying will not help; the user must reset (archive and recreate) the
 * cloud sync data.
 */
class CloudDataIncompatibleException(message: String) : KeryxException(message)

// --- Update ---

/** Which step of an in-app update ([works.merc.keryx.app.domain.UpdateRepository]) failed. */
enum class UpdateStage { DOWNLOAD, VERIFY, INSTALL }

/** An in-app update failed at [stage]. Never auto-retried and never sent to the notification
 * center — the Updates settings tab shows it inline (a "Retry" button), the same restrained
 * treatment [works.merc.keryx.app.domain.NotificationCenter] already gives [SyncConflictException]. */
class UpdateException(val stage: UpdateStage, message: String) : KeryxException(message)

// --- User input ---

class InvalidFeedUrlException(message: String) : KeryxException(message)

enum class DiscoveredFeedType { Rss, Atom }

/** A feed link discovered on an HTML page during subscription. */
data class DiscoveredFeedLink(val url: String, val title: String? = null, val type: DiscoveredFeedType? = null)
