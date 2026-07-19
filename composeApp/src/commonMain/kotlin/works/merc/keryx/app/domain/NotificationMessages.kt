package works.merc.keryx.app.domain

/**
 * Supplies localized text for notifications raised outside of Compose (feed
 * refresh, background updates). Implemented over Compose Resources; faked in
 * tests. Keeps user-facing strings out of the repositories.
 */
interface NotificationMessages {
    suspend fun feedGone(feedTitle: String): String
    suspend fun feedUrlChanged(feedTitle: String): String
    suspend fun newArticles(count: Int): String
}
