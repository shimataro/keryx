package works.merc.keryx.app.ui.i18n

import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.resources.Res
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
}
