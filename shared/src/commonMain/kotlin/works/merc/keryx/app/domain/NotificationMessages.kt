package works.merc.keryx.app.domain

/**
 * Supplies the localized text of the one message shared code hands to the *operating system*
 * rather than to a UI: the new-articles OS notification ([NewArticleNotifier]), which is posted
 * from background work with no UI to resolve it. Implemented per UI over its own string resources;
 * faked in tests.
 *
 * Everything a UI shows itself — notification-center rows, sync errors — travels as data instead
 * ([works.merc.keryx.app.core.NotificationText], [works.merc.keryx.app.core.ErrorKind]) and is
 * localized by that UI, so shared code never holds prose. See `docs/error-design.md`.
 */
interface NotificationMessages {
    suspend fun newArticles(count: Int): String
}
