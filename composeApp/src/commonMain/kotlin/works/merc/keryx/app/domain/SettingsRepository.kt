package works.merc.keryx.app.domain

import works.merc.keryx.app.core.CACHE_RETENTION_DAYS_DEFAULT
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.READ_TIMEOUT_SECONDS_DEFAULT
import works.merc.keryx.app.core.SETTING_ARTICLE_LIST_DEFAULT_UNREAD_ONLY
import works.merc.keryx.app.core.SETTING_CACHE_RETENTION_DAYS
import works.merc.keryx.app.core.SETTING_READ_TIMEOUT_SECONDS
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase

/**
 * Reads/writes both synced global settings (in `global_settings`) and
 * device-local settings (`local_settings.json`). Global changes trigger a sync.
 *
 * Device-local settings are written to disk **off the caller's thread**: [saveLocalSettings]
 * updates the in-memory [localSettings] synchronously (so the UI and [getLocalSettings] see the
 * new value instantly) and signals a background writer to persist it. Writes are coalesced (a
 * burst of rapid saves collapses to a single disk write) and serialized on a single-thread
 * dispatcher, so `local_settings.json` is never written concurrently (`FileIO.writeText` is not
 * atomic). Call [flush] to force a synchronous persist before the process exits or when
 * durability matters immediately (setup completion — see `isSetupComplete`).
 *
 * [writeDispatcher] must be single-threaded (the default is [Dispatchers.Default] limited to
 * parallelism 1) so the background writer and [flush] can't interleave; tests pass
 * [Dispatchers.Unconfined] to run writes inline.
 */
class SettingsRepository(
    private val db: KeryxDatabase,
    private val store: LocalSettingsStore,
    private val syncScheduler: SyncScheduler,
    private val clock: Clock,
    private val writeDispatcher: CoroutineDispatcher = Dispatchers.Default.limitedParallelism(1),
) {
    private val _localSettings = MutableStateFlow(store.load())

    /** Live device-local settings — the theme/font live off this. */
    val localSettings: StateFlow<LocalSettings> = _localSettings.asStateFlow()

    private val writeScope = CoroutineScope(SupervisorJob() + writeDispatcher)

    // A save signal; the writer always persists the *latest* _localSettings.value, so a burst of
    // saves that outpaces the disk collapses to one write (DROP_OLDEST keeps only the newest signal).
    private val saveSignals = Channel<Unit>(capacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        writeScope.launch {
            for (signal in saveSignals) {
                store.save(_localSettings.value)
            }
        }
    }
    // --- Global (synced) settings ---

    fun getReadTimeoutSeconds(): Int =
        getGlobal(SETTING_READ_TIMEOUT_SECONDS)?.toIntOrNull() ?: READ_TIMEOUT_SECONDS_DEFAULT

    fun setReadTimeoutSeconds(seconds: Int) {
        setGlobal(SETTING_READ_TIMEOUT_SECONDS, seconds.toString())
    }

    /** null == unlimited retention. */
    fun getCacheRetentionDays(): Int? {
        val raw = getGlobal(SETTING_CACHE_RETENTION_DAYS) ?: return CACHE_RETENTION_DAYS_DEFAULT
        return if (raw == "null") null else raw.toIntOrNull() ?: CACHE_RETENTION_DAYS_DEFAULT
    }

    fun setCacheRetentionDays(days: Int?) {
        setGlobal(SETTING_CACHE_RETENTION_DAYS, days?.toString() ?: "null")
    }

    fun getArticleListDefaultUnreadOnly(): Boolean =
        getGlobal(SETTING_ARTICLE_LIST_DEFAULT_UNREAD_ONLY)?.toBooleanStrictOrNull() ?: false

    fun setArticleListDefaultUnreadOnly(value: Boolean) {
        setGlobal(SETTING_ARTICLE_LIST_DEFAULT_UNREAD_ONLY, value.toString())
    }

    private fun getGlobal(key: String): String? =
        db.global_settingsQueries.get(key).executeAsOneOrNull()

    private fun setGlobal(key: String, value: String) {
        db.global_settingsQueries.upsert(key, value, clock.nowMillis())
        syncScheduler.scheduleSync()
    }

    /**
 * Gets the current device-local settings.
 *
 * @return The current local settings.
 */

    fun getLocalSettings(): LocalSettings = _localSettings.value

    /**
     * Replaces the in-memory settings wholesale and schedules an off-thread, coalesced disk write.
     * The disk write may lag the return; call [flush] when the value must be on disk before the
     * process can exit (see [flush]'s call sites: the JVM shutdown hook and setup completion).
     *
     * Only for writing a settings object that was not derived from the current one. To change some
     * fields and keep the rest, use [mutateLocalSettings] — see there for why.
     */
    fun saveLocalSettings(settings: LocalSettings) {
        _localSettings.value = settings
        saveSignals.trySend(Unit)
    }

    /**
     * Updates local settings using the current value and schedules persistence.
     *
     * @param transform The function that produces the updated settings.
     */
    fun mutateLocalSettings(transform: (LocalSettings) -> LocalSettings) {
        _localSettings.update(transform)
        saveSignals.trySend(Unit)
    }

    /**
     * Persists the current settings synchronously, serialized with the background writer (both run
     * on [writeDispatcher]). Suspends until the write completes. Called from the JVM shutdown hook
     * (so no pending debounced/coalesced write is lost on quit) and right after setup completes (so
     * `isSetupComplete`'s file-existence check is durable even if the app is closed immediately).
     */
    suspend fun flush() {
        withContext(writeDispatcher) { store.save(_localSettings.value) }
    }

    fun isSetupComplete(): Boolean = store.isSetupComplete()
}
