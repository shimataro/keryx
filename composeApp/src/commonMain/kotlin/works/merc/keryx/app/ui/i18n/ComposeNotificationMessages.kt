package works.merc.keryx.app.ui.i18n

import org.jetbrains.compose.resources.getPluralString
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.feed_new_articles

/** [NotificationMessages] backed by Compose string resources (system-locale aware). */
class ComposeNotificationMessages : NotificationMessages {
    override suspend fun newArticles(count: Int): String =
        getPluralString(Res.plurals.feed_new_articles, count, count)
}
