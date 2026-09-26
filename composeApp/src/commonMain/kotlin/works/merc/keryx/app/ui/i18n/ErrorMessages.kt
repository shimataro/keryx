package works.merc.keryx.app.ui.i18n

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.errorKind
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.error_cloud_auth
import works.merc.keryx.app.resources.error_cloud_data_incompatible
import works.merc.keryx.app.resources.error_cloud_storage
import works.merc.keryx.app.resources.error_feed_fetch
import works.merc.keryx.app.resources.error_feed_gone
import works.merc.keryx.app.resources.error_feed_not_found
import works.merc.keryx.app.resources.error_feed_parse
import works.merc.keryx.app.resources.error_feed_timeout
import works.merc.keryx.app.resources.error_generic
import works.merc.keryx.app.resources.error_schema_version
import works.merc.keryx.app.resources.error_sync_conflict
import works.merc.keryx.app.resources.error_update

/** Maps a [KeryxException] to a localized, user-facing message. */
@Composable
fun userMessage(exception: KeryxException): String = errorMessage(exception.errorKind)

/** Maps an [ErrorKind] to a localized, user-facing message. */
@Composable
fun errorMessage(kind: ErrorKind): String = stringResource(errorMessageResource(kind))

/** The string resource each [ErrorKind] is shown as. */
internal fun errorMessageResource(kind: ErrorKind): StringResource = when (kind) {
    ErrorKind.FEED_TIMEOUT -> Res.string.error_feed_timeout
    ErrorKind.FEED_FETCH -> Res.string.error_feed_fetch
    ErrorKind.FEED_PARSE -> Res.string.error_feed_parse
    ErrorKind.FEED_GONE -> Res.string.error_feed_gone
    ErrorKind.FEED_NOT_FOUND -> Res.string.error_feed_not_found
    ErrorKind.CLOUD_AUTH -> Res.string.error_cloud_auth
    ErrorKind.CLOUD_DATA_INCOMPATIBLE -> Res.string.error_cloud_data_incompatible
    ErrorKind.CLOUD_STORAGE -> Res.string.error_cloud_storage
    ErrorKind.SYNC_CONFLICT -> Res.string.error_sync_conflict
    ErrorKind.SCHEMA_VERSION -> Res.string.error_schema_version
    ErrorKind.UPDATE -> Res.string.error_update
    ErrorKind.GENERIC -> Res.string.error_generic
}
