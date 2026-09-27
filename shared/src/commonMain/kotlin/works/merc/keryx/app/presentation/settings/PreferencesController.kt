package works.merc.keryx.app.presentation.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.domain.SettingsRepository

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
 */
class PreferencesController(
    private val settingsRepository: SettingsRepository,
) {
    val localSettings = settingsRepository.localSettings

    private val _readTimeoutSeconds = MutableStateFlow(settingsRepository.getReadTimeoutSeconds())
    val readTimeoutSeconds = _readTimeoutSeconds.asStateFlow()

    /** null == unlimited. */
    private val _cacheRetentionDays = MutableStateFlow(settingsRepository.getCacheRetentionDays())
    val cacheRetentionDays = _cacheRetentionDays.asStateFlow()

    private fun update(transform: (LocalSettings) -> LocalSettings) {
        settingsRepository.mutateLocalSettings(transform)
    }

    fun setThemeMode(mode: String) = update { it.copy(themeMode = mode) }
    fun setFontScale(scale: Double) = update { it.copy(fontSizeScale = scale) }
    fun setRefreshIntervalMinutes(minutes: Int) = update { it.copy(refreshIntervalMinutes = minutes) }
    fun setNotificationEnabled(enabled: Boolean) = update { it.copy(notificationEnabled = enabled) }
    fun setStartMinimized(enabled: Boolean) = update { it.copy(startMinimized = enabled) }
    fun setUpdateCheckIntervalHours(hours: Int) = update { it.copy(updateCheckIntervalHours = hours) }

    fun updateReadTimeout(seconds: Int) {
        settingsRepository.setReadTimeoutSeconds(seconds)
        _readTimeoutSeconds.value = seconds
    }

    fun updateCacheRetention(days: Int?) {
        settingsRepository.setCacheRetentionDays(days)
        _cacheRetentionDays.value = days
    }
}
