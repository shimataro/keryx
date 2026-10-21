package works.merc.keryx.app

import works.merc.keryx.app.domain.FaviconMissStore

/**
 * A [FaviconMissStore] that forgets everything with the object, for tests that build a
 * `FeedRepository` by hand. Production keeps the record in the device-local settings
 * (`SettingsFaviconMissStore`), so that it outlives a process; a repository wired with this
 * one would probe a site without a favicon again in every new process.
 *
 * Unlike the settings-backed one it never drops expired entries (`SettingsFaviconMissStore` prunes
 * those older than `FAVICON_RETRY_INTERVAL_MS` when it records a miss), so a test that spans more
 * than a retry interval sees no difference only because the lookup itself checks the age.
 */
class InMemoryFaviconMissStore : FaviconMissStore {
    private val misses = HashMap<String, Long>()

    override fun missedAt(feedId: String): Long? = synchronized(misses) { misses[feedId] }

    override fun recordMiss(feedId: String, at: Long) {
        synchronized(misses) { misses[feedId] = at }
    }

    override fun clearMiss(feedId: String) {
        synchronized(misses) { misses.remove(feedId) }
    }
}
