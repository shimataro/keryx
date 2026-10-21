package works.merc.keryx.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.FAVICON_RETRY_INTERVAL_MS

/**
 * Remembers, per feed, when a favicon lookup last found none, so a refresh doesn't probe a site
 * without a favicon again on every run (see [FAVICON_RETRY_INTERVAL_MS]).
 */
interface FaviconMissStore {
    /** When the last lookup for [feedId] found no favicon, or `null` if none did (or it was forgotten). */
    fun missedAt(feedId: String): Long?

    /** Records that the lookup for [feedId] found no favicon at [at]. */
    fun recordMiss(feedId: String, at: Long)

    /** Forgets a miss for [feedId] — a lookup found a favicon after all. */
    fun clearMiss(feedId: String)
}

/** Keeps the misses in memory only, for the life of the process. */
class InMemoryFaviconMissStore : FaviconMissStore {
    private val misses = MutableStateFlow<Map<String, Long>>(emptyMap())

    override fun missedAt(feedId: String): Long? = misses.value[feedId]

    override fun recordMiss(feedId: String, at: Long) {
        misses.update { withFaviconMiss(it, feedId, at) }
    }

    override fun clearMiss(feedId: String) {
        misses.update { it - feedId }
    }
}

/**
 * Keeps the misses in the device-local settings (`LocalSettings.faviconMissedAt`), so they outlive
 * the process: Android starts a new one for most background refreshes (a WorkManager wakeup), and a
 * miss kept only in memory would not stop the next one probing the same sites again. Device-local
 * and not synced, like every other local setting.
 */
class SettingsFaviconMissStore(private val settings: SettingsRepository) : FaviconMissStore {
    override fun missedAt(feedId: String): Long? = settings.getLocalSettings().faviconMissedAt[feedId]

    override fun recordMiss(feedId: String, at: Long) {
        settings.mutateLocalSettings { it.copy(faviconMissedAt = withFaviconMiss(it.faviconMissedAt, feedId, at)) }
    }

    override fun clearMiss(feedId: String) {
        if (feedId !in settings.getLocalSettings().faviconMissedAt) return
        settings.mutateLocalSettings { it.copy(faviconMissedAt = it.faviconMissedAt - feedId) }
    }
}

/**
 * [misses] with [feedId]'s miss set to [at], and every miss at least [FAVICON_RETRY_INTERVAL_MS]
 * older than [at] dropped: those no longer suppress a lookup, and dropping them is what keeps the
 * stored map to the feeds that missed recently rather than growing with every feed ever subscribed.
 */
internal fun withFaviconMiss(misses: Map<String, Long>, feedId: String, at: Long): Map<String, Long> =
    misses.filterValues { at - it < FAVICON_RETRY_INTERVAL_MS } + (feedId to at)
