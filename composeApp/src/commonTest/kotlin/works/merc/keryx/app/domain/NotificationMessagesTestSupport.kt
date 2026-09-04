package works.merc.keryx.app.domain

import works.merc.keryx.app.core.KeryxException

/**
 * A [NotificationMessages] fake returning canned, recognizable strings, shared by every test that
 * needs one just to build a repository/ViewModel under test — several of them additionally assert
 * on the exact canned text (e.g. `SyncRepositoryTest`'s `"syncFailed:$type"`, `FeedRepositoryTest`'s
 * `"gone:"`/`"urlChanged:"` prefixes, `NewArticleNotifierTest`'s `"new:$count"`), so this format is
 * a shared contract, not an implementation detail private to any one test file. Kept as a single
 * `open class` (rather than ten private copies) so adding a member to [NotificationMessages] is one
 * edit here instead of the same two lines repeated across every test file that builds one.
 */
internal open class FakeNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: KeryxException): String = "syncFailed:${exception::class.simpleName}"
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
    override suspend fun updateAvailable(version: String): String = "updateAvailable:$version"
    override suspend fun updateReadyToInstall(version: String): String = "updateReadyToInstall:$version"
    override suspend fun tokenStorageFallback(): String = "tokenStorageFallback"
    override suspend fun tokenStorageFallbackDetail(): String = "tokenStorageFallbackDetail"
    override suspend fun tokenStorageNotPersisted(): String = "tokenStorageNotPersisted"
    override suspend fun tokenStorageNotPersistedDetail(): String = "tokenStorageNotPersistedDetail"
}
