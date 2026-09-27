package works.merc.keryx.app.domain

/**
 * A [NotificationMessages] fake returning a canned, recognizable string (`"new:$count"`, which
 * `NewArticleNotifierTest` asserts on), shared by every test that needs one just to build a
 * repository/ViewModel under test. Kept as a single `open class` so adding a member to
 * [NotificationMessages] is one edit here instead of one per test file.
 */
open class FakeNotificationMessages : NotificationMessages {
    override suspend fun newArticles(count: Int): String = "new:$count"
}
