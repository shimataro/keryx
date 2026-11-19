package works.merc.keryx.app.presentation.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MAX_WIDTH
import works.merc.keryx.app.core.ARTICLE_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_MAX_WIDTH
import works.merc.keryx.app.core.FEED_LIST_PANE_MIN_WIDTH
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.runCatchingUnlessCancelled
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.presentation.derived

/**
 * Typed setters over [LocalSettings] and `global_settings`, shared with the SwiftUI app (via
 * `KeryxSdk.preferences`) so both UIs read and write the same settings the same way. Split out of
 * what was `SettingsViewModel`; the update-check-related fields
 * ([LocalSettings.updateCheckIntervalHours] included) stay here since the *setting* is shared,
 * even though the in-app updater itself (checking, downloading, installing) is Compose/desktop-only.
 *
 * A plain class, not a `ViewModel`: every write below is a synchronous call into
 * [SettingsRepository] (which owns its own background writer), so there is no coroutine scope of
 * its own to manage.
 *
 * The two global settings it shows ([readTimeoutSeconds], [cacheRetentionDays]) show a new value at
 * once, ahead of its write. Should that write fail (it is reported by [SettingsRepository]), the
 * value goes back to what the DB holds — unless the user has changed the same setting again since,
 * in which case that newer change decides it. Each setting's shown value carries the sequence number
 * of the change that set it ([ShownSetting]), and the revert, which runs on the writer's thread,
 * replaces it only while that number is still the failed change's own. Should reading the stored
 * value fail as well, the read is tried again on the writer ([SettingsRepository.retryOnWriter])
 * until it succeeds or a newer change of the same setting makes it moot, so the shown value never
 * stays on one that was not saved.
 */
class PreferencesController(
    private val settingsRepository: SettingsRepository,
) {
    val localSettings = settingsRepository.localSettings

    private val _readTimeoutSeconds = MutableStateFlow(ShownSetting(0L, settingsRepository.getReadTimeoutSeconds()))
    val readTimeoutSeconds: StateFlow<Int> = _readTimeoutSeconds.derived { it.value }

    /** null == unlimited. */
    private val _cacheRetentionDays = MutableStateFlow(ShownSetting(0L, settingsRepository.getCacheRetentionDays()))
    val cacheRetentionDays: StateFlow<Int?> = _cacheRetentionDays.derived { it.value }

    private fun update(transform: (LocalSettings) -> LocalSettings) {
        settingsRepository.mutateLocalSettings(transform)
    }

    fun setThemeMode(mode: String) = update { it.copy(themeMode = mode) }
    fun setFontScale(scale: Double) = update { it.copy(fontSizeScale = scale) }
    fun setRefreshIntervalMinutes(minutes: Int) = update { it.copy(refreshIntervalMinutes = minutes) }
    fun setNotificationEnabled(enabled: Boolean) = update { it.copy(notificationEnabled = enabled) }
    fun setStartMinimized(enabled: Boolean) = update { it.copy(startMinimized = enabled) }
    fun setUpdateCheckIntervalHours(hours: Int) = update { it.copy(updateCheckIntervalHours = hours) }
    fun setSyncOnMeteredNetwork(enabled: Boolean) = update { it.copy(syncOnMeteredNetwork = enabled) }

    fun updateReadTimeout(seconds: Int) {
        // Shown before the write is queued, so a write that fails at once (an inline writer) finds
        // its own change to take back.
        val seq = _readTimeoutSeconds.show(seconds)
        settingsRepository.setReadTimeoutSeconds(seconds) {
            revert(_readTimeoutSeconds, seq) { settingsRepository.getReadTimeoutSeconds() }
        }
    }

    fun updateCacheRetention(days: Int?) {
        val seq = _cacheRetentionDays.show(days)
        settingsRepository.setCacheRetentionDays(days) {
            revert(_cacheRetentionDays, seq) { settingsRepository.getCacheRetentionDays() }
        }
    }

    /**
     * Persists the sidebar's own pane width, clamped the same way Compose's own
     * `HomeLayoutViewModel` clamps it before ever storing it — a UI-owned width (not read back
     * reactively from here; each UI keeps its own live `@State`/`StateFlow` for that) written on a
     * debounce by whichever UI is dragging its own divider.
     */
    fun setFeedListPaneWidth(width: Double) =
        update { it.copy(feedListPaneWidth = width.coerceIn(FEED_LIST_PANE_MIN_WIDTH.toDouble(), FEED_LIST_PANE_MAX_WIDTH.toDouble())) }

    /** See [setFeedListPaneWidth]. */
    fun setArticleListPaneWidth(width: Double) =
        update { it.copy(articleListPaneWidth = width.coerceIn(ARTICLE_LIST_PANE_MIN_WIDTH.toDouble(), ARTICLE_LIST_PANE_MAX_WIDTH.toDouble())) }

    /** [pane] is the raw name of whichever pane enum the calling UI defines (Compose's `HomePane`,
     * the Apple app's `HomeFocusedPane`) — stored as plain text so neither UI needs the other's type. */
    fun setLastFocusedPane(pane: String) = update { it.copy(lastFocusedPane = pane) }

    /**
     * Puts the stored value back after the change [seq] of [shown] failed to save: at once if it can
     * be read, else again on the writer until it can (see the class KDoc).
     */
    private fun <T> revert(shown: MutableStateFlow<ShownSetting<T>>, seq: Long, read: () -> T) {
        if (!shown.tryRevert(seq, read)) settingsRepository.retryOnWriter { shown.tryRevert(seq, read) }
    }
}

/**
 * A global setting's shown value and the sequence number of the change that set it (0 = loaded from
 * the DB) — compared and replaced as one, so a failed change's revert can never overwrite a newer one.
 */
private data class ShownSetting<T>(val seq: Long, val value: T)

/** Shows [value] as a new change and returns that change's sequence number. */
private fun <T> MutableStateFlow<ShownSetting<T>>.show(value: T): Long =
    updateAndGet { ShownSetting(it.seq + 1, value) }.seq

/**
 * Puts the DB's value ([read]) back while the change [seq] is still the one shown.
 *
 * @return `true` when nothing is left to do — the value was put back, or a newer change of the same
 *   setting is shown and decides it; `false` when the read failed (logged), so it should be tried again.
 */
private fun <T> MutableStateFlow<ShownSetting<T>>.tryRevert(seq: Long, read: () -> T): Boolean {
    if (value.seq != seq) return true
    val stored = runCatchingUnlessCancelled(read).getOrElse { e ->
        Log.warn("PreferencesController", "Re-reading a setting after its write failed failed", e)
        return false
    }
    update { if (it.seq == seq) it.copy(value = stored) else it }
    return true
}
