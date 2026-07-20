package works.merc.keryx.app.ui.i18n

import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.error_cloud_auth
import works.merc.keryx.app.resources.error_cloud_storage
import works.merc.keryx.app.resources.error_generic
import works.merc.keryx.app.resources.error_schema_version
import works.merc.keryx.app.resources.error_sync_conflict
import works.merc.keryx.app.resources.feed_gone_message
import works.merc.keryx.app.resources.feed_new_articles
import works.merc.keryx.app.resources.feed_url_changed

/** [NotificationMessages] backed by Compose string resources (system-locale aware). */
class ComposeNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String =
        getString(Res.string.feed_gone_message, feedTitle)

    override suspend fun feedUrlChanged(feedTitle: String): String =
        getString(Res.string.feed_url_changed, feedTitle)

    override suspend fun newArticles(count: Int): String =
        getString(Res.string.feed_new_articles, count)

    override suspend fun syncFailed(exception: KeryxException): String = getString(
        when (exception) {
            is CloudAuthException -> Res.string.error_cloud_auth
            is SchemaVersionException -> Res.string.error_schema_version
            is SyncConflictException -> Res.string.error_sync_conflict
            is CloudStorageException -> Res.string.error_cloud_storage
            else -> Res.string.error_generic
        },
    )
}
