package works.merc.keryx.app.data.local

import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.LOCAL_SETTINGS_FILE_NAME
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO

/**
 * Reads/writes [LocalSettings] to `local_settings.json`. Setup completion is
 * defined as "the settings file exists".
 *
 * [dirOverride] lets tests point at a temp directory.
 */
class LocalSettingsStore(
    private val dirOverride: String? = null,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    },
) {
    private val path: String
        get() = FileIO.join(dirOverride ?: AppDirs.appDataDir(), LOCAL_SETTINGS_FILE_NAME)

    fun load(): LocalSettings {
        val text = FileIO.readText(path) ?: return LocalSettings()
        return runCatching { json.decodeFromString<LocalSettings>(text) }.getOrDefault(LocalSettings())
    }

    fun save(settings: LocalSettings) {
        FileIO.writeText(path, json.encodeToString(settings))
    }

    fun isSetupComplete(): Boolean = FileIO.exists(path)
}
