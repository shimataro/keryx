package works.merc.keryx.app.ui.i18n

import androidx.compose.runtime.Composable
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.FeedFetchException
import works.merc.keryx.app.core.FeedNotFoundException
import works.merc.keryx.app.core.FeedParseException
import works.merc.keryx.app.core.FeedTimeoutException
import works.merc.keryx.app.core.InvalidFeedUrlException
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.error_cloud_auth
import works.merc.keryx.app.resources.error_cloud_storage
import works.merc.keryx.app.resources.error_feed_fetch
import works.merc.keryx.app.resources.error_feed_gone
import works.merc.keryx.app.resources.error_feed_not_found
import works.merc.keryx.app.resources.error_feed_parse
import works.merc.keryx.app.resources.error_feed_timeout
import works.merc.keryx.app.resources.error_generic
import works.merc.keryx.app.resources.error_invalid_url
import works.merc.keryx.app.resources.error_schema_version
import works.merc.keryx.app.resources.error_sync_conflict

/** Maps a [KeryxException] to a localized, user-facing message. */
@Composable
fun userMessage(exception: KeryxException): String = stringResource(
    when (exception) {
        is FeedTimeoutException -> Res.string.error_feed_timeout
        is FeedFetchException -> Res.string.error_feed_fetch
        is FeedParseException -> Res.string.error_feed_parse
        is CloudAuthException -> Res.string.error_cloud_auth
        is CloudStorageException -> Res.string.error_cloud_storage
        is SyncConflictException -> Res.string.error_sync_conflict
        is SchemaVersionException -> Res.string.error_schema_version
        is InvalidFeedUrlException -> Res.string.error_invalid_url
        is FeedNotFoundException -> if (exception.isGone) Res.string.error_feed_gone else Res.string.error_feed_not_found
        else -> Res.string.error_generic
    },
)
