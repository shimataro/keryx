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

// --- User input ---

class InvalidFeedUrlException(message: String) : KeryxException(message)

enum class DiscoveredFeedType { Rss, Atom }

/** A feed link discovered on an HTML page during subscription. */
data class DiscoveredFeedLink(val url: String, val title: String? = null, val type: DiscoveredFeedType? = null)
