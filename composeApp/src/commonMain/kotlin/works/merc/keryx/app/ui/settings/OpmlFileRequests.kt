package works.merc.keryx.app.ui.settings

import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.platform.OpenFileRequest
import works.merc.keryx.app.platform.SaveFileRequest
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.file_filter_opml
import works.merc.keryx.app.resources.file_overwrite_message
import works.merc.keryx.app.resources.file_overwrite_replace
import works.merc.keryx.app.resources.file_overwrite_title
import works.merc.keryx.app.resources.settings_export_opml
import works.merc.keryx.app.resources.settings_import_opml

/**
 * The file-dialog requests OPML import/export open, with their localized titles and labels.
 * Built by the UI layer rather than [SettingsViewModel], so the ViewModel itself holds no string
 * resources.
 */
interface OpmlFileRequests {
    suspend fun export(): SaveFileRequest
    suspend fun import(): OpenFileRequest
}

/** [OpmlFileRequests] over this app's Compose Resources. */
object ComposeOpmlFileRequests : OpmlFileRequests {
    override suspend fun export() = SaveFileRequest(
        title = getString(Res.string.settings_export_opml),
        defaultName = "keryx.opml",
        overwriteTitle = getString(Res.string.file_overwrite_title),
        overwriteMessage = getString(Res.string.file_overwrite_message),
        overwriteReplaceLabel = getString(Res.string.file_overwrite_replace),
        overwriteCancelLabel = getString(Res.string.common_cancel),
    )

    override suspend fun import() = OpenFileRequest(
        title = getString(Res.string.settings_import_opml),
        extensions = listOf("opml", "xml"),
        filterLabel = getString(Res.string.file_filter_opml),
    )
}
