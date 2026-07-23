package works.merc.keryx.app.domain

import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertGlobalSetting
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.platform.DatabaseMerger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Verifies the ATTACH-DATABASE merge (timestamp-last-wins) via [DatabaseMerger]. */
class SyncMergerTest {

    private fun insertArticle(
        db: KeryxDatabase,
        id: String, feed: String, guid: String,
        isRead: Long, readAt: Long?, updatedAt: Long,
        title: String = "T", content: String? = "c", summary: String? = null,
        isStarred: Long = 0, starredAt: Long? = null,
        searchText: String = content ?: summary ?: "",
    ) {
        db.articlesQueries.insert(
            id = id, feed_id = feed, guid = guid, url = "u", title = title,
            summary = summary, content = content, author = null, published_at = null, thumbnail_url = null,
            is_read = isRead, read_at = readAt, is_starred = isStarred, starred_at = starredAt, cached_at = updatedAt,
            search_text = searchText, updated_at = updatedAt, created_at = 0,
        )
    }

    @Test
    fun readStateLastWinsFromCloud() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a1", "f1", "g1", isRead = 1, readAt = 200, updatedAt = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(localDb, "a1", "f1", "g1", isRead = 0, readAt = 100, updatedAt = 100)
        localDriver.close() // release the SQLDelight connection before merging on a raw one

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val merged = verifyDb.articlesQueries.getById("a1").executeAsOne()
        assertEquals(1L, merged.is_read)
        assertEquals(200L, merged.read_at)
        verifyDriver.close()
    }

    @Test
    fun localReadStateKeptWhenNewer() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a1", "f1", "g1", isRead = 0, readAt = 100, updatedAt = 100)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(localDb, "a1", "f1", "g1", isRead = 1, readAt = 300, updatedAt = 300)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(1L, verifyDb.articlesQueries.getById("a1").executeAsOne().is_read)
        verifyDriver.close()
    }

    @Test
    fun cloudOnlyFeedAndArticleAreImported() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f2", now = 100)
        insertArticle(cloudDb, "a9", "f2", "g9", isRead = 0, readAt = null, updatedAt = 100)
        cloudDriver.close()

        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNotNull(verifyDb.feedsQueries.getById("f2").executeAsOneOrNull())
        assertNotNull(verifyDb.articlesQueries.getById("a9").executeAsOneOrNull())
        verifyDriver.close()
    }

    @Test
    fun feedUrlCollisionGuardSkipsCloudFeed() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f-cloud", now = 200, url = "https://shared/feed")
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f-local", now = 100, url = "https://shared/feed")
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        // The cloud feed (different id, same URL) must not be imported.
        assertNull(verifyDb.feedsQueries.getById("f-cloud").executeAsOneOrNull())
        assertNotNull(verifyDb.feedsQueries.getById("f-local").executeAsOneOrNull())
        verifyDriver.close()
    }

    @Test
    fun feedAndTagDeletedAtPropagatesFromNewerCloud() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 200, deletedAt = 200)
        cloudDb.insertTag("t1", "tag", now = 200, deletedAt = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 100, deletedAt = null)
        localDb.insertTag("t1", "tag", now = 100, deletedAt = null)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(200L, verifyDb.feedsQueries.getById("f1").executeAsOne().deleted_at)
        assertEquals(200L, verifyDb.tagsQueries.getById("t1").executeAsOne().deleted_at)
        verifyDriver.close()
    }

    @Test
    fun tagNameCollisionGuardSkipsCloudTag() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertTag("t-cloud", "shared-name", now = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertTag("t-local", "shared-name", now = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.tagsQueries.getById("t-cloud").executeAsOneOrNull())
        assertNotNull(verifyDb.tagsQueries.getById("t-local").executeAsOneOrNull())
        verifyDriver.close()
    }

    @Test
    fun readAndStarredStateResolveIndependently() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        // Cloud has the newer read_at but the older starred_at.
        insertArticle(
            cloudDb, "a1", "f1", "g1",
            isRead = 1, readAt = 300, updatedAt = 300,
            isStarred = 0, starredAt = 100,
        )
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(
            localDb, "a1", "f1", "g1",
            isRead = 0, readAt = 100, updatedAt = 100,
            isStarred = 1, starredAt = 300,
        )
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val merged = verifyDb.articlesQueries.getById("a1").executeAsOne()
        // read state: cloud wins (newer read_at)
        assertEquals(1L, merged.is_read)
        assertEquals(300L, merged.read_at)
        // starred state: local wins (newer starred_at)
        assertEquals(1L, merged.is_starred)
        assertEquals(300L, merged.starred_at)
        verifyDriver.close()
    }

    @Test
    fun localReadNotRevertedByOlderCloudEvenWhenCloudBringsContent() {
        // Regression guard: a read made locally (newer read_at) must survive a merge with a cloud row
        // that is unread (older/NULL read_at) but carries body content. The ON CONFLICT branch relies
        // on `excluded` being the SELECT's already-merged value (per-field CASE against the local row),
        // so the content OR-merge must never drag read/star state backwards.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100, content = "cloud body")
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(localDb, "a1", "f1", "g1", isRead = 1, readAt = 300, updatedAt = 50, content = null, summary = "local summary")
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val merged = verifyDb.articlesQueries.getById("a1").executeAsOne()
        // Read state is preserved (local newer)...
        assertEquals(1L, merged.is_read)
        assertEquals(300L, merged.read_at)
        // ...while the body still OR-merges in the cloud content.
        assertEquals("cloud body", merged.content)
        verifyDriver.close()
    }

    @Test
    fun articleContentOrMergesOnConflictAndRecomputesSearchText() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100, content = "cloud body")
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(localDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 50, content = null, summary = "local summary")
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val merged = verifyDb.articlesQueries.getById("a1").executeAsOne()
        assertEquals("cloud body", merged.content)
        assertEquals("cloud body", merged.search_text)
        verifyDriver.close()
    }

    @Test
    fun articleGuidCollisionGuardSkipsCloudArticle() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a-cloud", "f1", "g1", isRead = 0, readAt = null, updatedAt = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 100)
        insertArticle(localDb, "a-local", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.articlesQueries.getById("a-cloud").executeAsOneOrNull())
        assertNotNull(verifyDb.articlesQueries.getById("a-local").executeAsOneOrNull())
        verifyDriver.close()
    }

    @Test
    fun articleWithMissingFeedIsSkipped() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        // No feed "f-missing" in either DB: dangling FK reference.
        insertArticle(cloudDb, "a1", "f-missing", "g1", isRead = 0, readAt = null, updatedAt = 100)
        cloudDriver.close()

        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.articlesQueries.getById("a1").executeAsOneOrNull())
        verifyDriver.close()
    }

    @Test
    fun feedTagsBasicAttachMerge() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        cloudDb.insertTag("t1", "tag", now = 100)
        cloudDb.insertFeedTag("f1", "t1", now = 100)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 100)
        localDb.insertTag("t1", "tag", now = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNotNull(verifyDb.feed_tagsQueries.watchTagIdsForFeed("f1").executeAsList().find { it == "t1" })
        verifyDriver.close()
    }

    @Test
    fun feedTagWithMissingTagIsSkipped() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        // "t-missing" does not exist in cloud or local.
        cloudDb.insertFeedTag("f1", "t-missing", now = 100)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(emptyList(), verifyDb.feed_tagsQueries.watchAllActive().executeAsList())
        verifyDriver.close()
    }

    @Test
    fun globalSettingsTimestampLastWins() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertGlobalSetting("k1", "cloud-value", now = 200)
        cloudDb.insertGlobalSetting("k2", "old-cloud-value", now = 50)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertGlobalSetting("k1", "local-value", now = 100)
        localDb.insertGlobalSetting("k2", "local-value", now = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals("cloud-value", verifyDb.global_settingsQueries.get("k1").executeAsOne())
        // Cloud value for k2 is older, so local value must be kept.
        assertEquals("local-value", verifyDb.global_settingsQueries.get("k2").executeAsOne())
        verifyDriver.close()
    }

    @Test
    fun folderDeletedAtPropagatesFromNewerCloud() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("d1", "folder", now = 200, deletedAt = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFolder("d1", "folder", now = 100, deletedAt = null)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(200L, verifyDb.foldersQueries.getById("d1").executeAsOne().deleted_at)
        verifyDriver.close()
    }

    @Test
    fun folderNameCollisionGuardSkipsCloudFolder() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("d-cloud", "shared-name", now = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFolder("d-local", "shared-name", now = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.foldersQueries.getById("d-cloud").executeAsOneOrNull())
        assertNotNull(verifyDb.foldersQueries.getById("d-local").executeAsOneOrNull())
        verifyDriver.close()
    }

    @Test
    fun feedFolderIdMergeWhenFolderExists() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("d1", "folder", now = 100)
        cloudDb.insertFeed("f1", now = 100, folderId = "d1")
        cloudDriver.close()

        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNotNull(verifyDb.foldersQueries.getById("d1").executeAsOneOrNull())
        assertEquals("d1", verifyDb.feedsQueries.getById("f1").executeAsOne().folder_id)
        verifyDriver.close()
    }

    @Test
    fun feedFolderIdNulledWhenFolderMissing() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        // "d-missing" is referenced by the feed but never created in either DB.
        cloudDb.insertFeed("f1", now = 100, folderId = "d-missing")
        cloudDriver.close()

        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.foldersQueries.getById("d-missing").executeAsOneOrNull())
        assertNull(verifyDb.feedsQueries.getById("f1").executeAsOne().folder_id)
        verifyDriver.close()
    }

    @Test
    fun newFolderAndFeedAssignedToItMergeTogetherInSameCycle() {
        // A brand-new folder plus a feed assigned to it both arrive from cloud in one sync cycle;
        // folders must merge before feeds so the feed's folder_id resolves (not NULL).
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("d1", "New Folder", now = 100)
        cloudDb.insertFeed("f1", now = 100, folderId = "d1")
        cloudDriver.close()

        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val folder = verifyDb.foldersQueries.getById("d1").executeAsOneOrNull()
        assertNotNull(folder)
        assertEquals("New Folder", folder.name)
        assertEquals("d1", verifyDb.feedsQueries.getById("f1").executeAsOne().folder_id)
        verifyDriver.close()
    }

    @Test
    fun feedSortOrderPropagatesEvenWhenLocalRowIsNewer() {
        // A reorders f1 (sort_order_updated_at newer); B has the same feed with a newer content
        // updated_at (an independent refresh). sort_order rides its own timestamp, so B must adopt
        // A's order even though the feeds row is skipped by the content-gated merge.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100, sortOrder = 1L)
        cloudDb.feedsQueries.updateSortOrder(5L, 200L, 200L, "f1") // reordered at sort_order_updated_at = 200
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 500, sortOrder = 1L) // newer content, sort_order_updated_at = NULL
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(5L, verifyDb.feedsQueries.getById("f1").executeAsOne().sort_order)
        verifyDriver.close()
    }

    @Test
    fun feedSortOrderKeptWhenLocalReorderIsNewer() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100, sortOrder = 1L)
        cloudDb.feedsQueries.updateSortOrder(5L, 100L, 100L, "f1")
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 100, sortOrder = 1L)
        localDb.feedsQueries.updateSortOrder(9L, 300L, 300L, "f1") // local reorder is newer
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(9L, verifyDb.feedsQueries.getById("f1").executeAsOne().sort_order)
        verifyDriver.close()
    }

    @Test
    fun feedSortOrderUntouchedWhenNoReorderEvent() {
        // Cloud's sort_order differs but has no reorder event (sort_order_updated_at NULL, e.g. only
        // ever refreshed): the local order must not be disturbed.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 300, sortOrder = 5L) // no updateSortOrder -> sort_order_updated_at NULL
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 100, sortOrder = 1L)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(1L, verifyDb.feedsQueries.getById("f1").executeAsOne().sort_order)
        verifyDriver.close()
    }

    @Test
    fun newFeedInitialSortOrderPropagatesViaInsert() {
        // A brand-new cloud feed carries its initial sort_order (set at subscribe, no reorder event)
        // through the feeds INSERT even though sort_order_updated_at is NULL.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f2", now = 100, sortOrder = 7L)
        cloudDriver.close()

        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(7L, verifyDb.feedsQueries.getById("f2").executeAsOne().sort_order)
        verifyDriver.close()
    }

    @Test
    fun customTitlePropagatesEvenWhenLocalRowIsNewer() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        cloudDb.feedsQueries.updateCustomTitle("My Name", 200L, 200L, "f1")
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 500) // newer content, custom_title = null, custom_title_updated_at = NULL
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals("My Name", verifyDb.feedsQueries.getById("f1").executeAsOne().custom_title)
        verifyDriver.close()
    }

    @Test
    fun localCustomTitleKeptWhenNewer() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        cloudDb.feedsQueries.updateCustomTitle("Cloud Name", 100L, 100L, "f1")
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 100)
        localDb.feedsQueries.updateCustomTitle("Local Name", 300L, 300L, "f1")
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals("Local Name", verifyDb.feedsQueries.getById("f1").executeAsOne().custom_title)
        verifyDriver.close()
    }

    @Test
    fun unsubscribePropagatesEvenWhenLocalKeptRefreshing() {
        // A unsubscribes f1 (deleted_updated_at newer); B still has it live with a newer content
        // updated_at (kept refreshing). The unsubscribe must propagate.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 200, deletedAt = 200) // insertFeed stamps deleted_updated_at = 200
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 500) // live, newer content, deleted_updated_at = NULL
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(200L, verifyDb.feedsQueries.getById("f1").executeAsOne().deleted_at)
        verifyDriver.close()
    }

    @Test
    fun resubscribeOverridesOlderUnsubscribe() {
        // Local unsubscribed f1 (older); cloud re-subscribed it (deleted_updated_at newer) -> live.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        cloudDb.feedsQueries.stampResubscribed(300L, "f1") // re-subscribed, deleted_at NULL, ts = 300
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 100, deletedAt = 200) // unsubscribed at deleted_updated_at = 200
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.feedsQueries.getById("f1").executeAsOne().deleted_at)
        verifyDriver.close()
    }

    @Test
    fun feedAssignedToLocalFolderWhenCloudHasSameNamedFolder() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("d-cloud", "IT", now = 100)
        cloudDb.insertFeed("f1", now = 100, folderId = "d-cloud")
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFolder("d-local", "IT", now = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.foldersQueries.getById("d-cloud").executeAsOneOrNull())
        assertEquals("d-local", verifyDb.feedsQueries.getById("f1").executeAsOne().folder_id)
        verifyDriver.close()
    }

    @Test
    fun feedLosesFolderWhenCloudFolderDeletedAndNameMatchesLocal() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("d-cloud", "IT", now = 100, deletedAt = 200)
        cloudDb.insertFeed("f1", now = 100, folderId = "d-cloud")
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFolder("d-local", "IT", now = 50)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(200L, verifyDb.foldersQueries.getById("d-local").executeAsOne().deleted_at)
        assertNull(verifyDb.feedsQueries.getById("f1").executeAsOne().folder_id)
        verifyDriver.close()
    }

    @Test
    fun localFolderUpdatedWhenCloudHasSameNamedNewerFolder() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("d-cloud", "IT", now = 200, sortOrder = 5L)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFolder("d-local", "IT", now = 100, sortOrder = 1L)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val folder = verifyDb.foldersQueries.getById("d-local").executeAsOneOrNull()
        assertNotNull(folder)
        assertEquals(5L, folder.sort_order)
        assertEquals(200L, folder.updated_at)
        verifyDriver.close()
    }

    @Test
    fun cloudFolderSkippedWhenLocalHasSameNamedNewerFolder() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("d-cloud", "IT", now = 100)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFolder("d-local", "IT", now = 200)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.foldersQueries.getById("d-cloud").executeAsOneOrNull())
        val folder = verifyDb.foldersQueries.getById("d-local").executeAsOne()
        assertEquals(200L, folder.updated_at)
        verifyDriver.close()
    }

    @Test
    fun feedTagAssignedToLocalTagWhenCloudHasSameNamedTag() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        cloudDb.insertTag("t-cloud", "News", now = 100)
        cloudDb.insertFeedTag("f1", "t-cloud", now = 100)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 100)
        localDb.insertTag("t-local", "News", now = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.tagsQueries.getById("t-cloud").executeAsOneOrNull())
        val activeTags = verifyDb.feed_tagsQueries.watchAllActive().executeAsList()
        assertEquals(1, activeTags.size)
        assertEquals("t-local", activeTags[0].tag_id)
        verifyDriver.close()
    }

    @Test
    fun feedTagLosesLinkWhenCloudTagDeletedAndNameMatchesLocal() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 50)
        cloudDb.insertTag("t-cloud", "News", now = 100, deletedAt = 200)
        cloudDb.insertFeedTag("f1", "t-cloud", now = 200, deletedAt = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        localDb.insertTag("t-local", "News", now = 50)
        localDb.insertFeedTag("f1", "t-local", now = 50)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(200L, verifyDb.tagsQueries.getById("t-local").executeAsOne().deleted_at)
        assertEquals(emptyList(), verifyDb.feed_tagsQueries.watchAllActive().executeAsList())
        verifyDriver.close()
    }

    @Test
    fun localTagUpdatedWhenCloudHasSameNamedNewerTag() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertTag("t-cloud", "News", now = 200, sortOrder = 5L)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertTag("t-local", "News", now = 100, sortOrder = 1L)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val tag = verifyDb.tagsQueries.getById("t-local").executeAsOneOrNull()
        assertNotNull(tag)
        assertEquals(5L, tag.sort_order)
        assertEquals(200L, tag.updated_at)
        verifyDriver.close()
    }

    @Test
    fun cloudTagSkippedWhenLocalHasSameNamedNewerTag() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertTag("t-cloud", "News", now = 100)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertTag("t-local", "News", now = 200)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.tagsQueries.getById("t-cloud").executeAsOneOrNull())
        val tag = verifyDb.tagsQueries.getById("t-local").executeAsOne()
        assertEquals(200L, tag.updated_at)
        verifyDriver.close()
    }

    @Test
    fun feedFolderAssignmentPropagatesEvenWhenLocalRowIsNewer() {
        // Regression for the reported bug: A puts a feed in a folder; B has the same feed in "no
        // folder" but with a newer updated_at (an independent content refresh). folder_id rides its
        // own folder_updated_at, so B must still adopt A's assignment even though the feeds row is
        // skipped by the content-gated merge.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("d1", "folder", now = 100)
        cloudDb.insertFeed("f1", now = 100, folderId = "d1") // folder_updated_at = 100
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 500) // folder_id = NULL, folder_updated_at = NULL, updated_at newer
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNotNull(verifyDb.foldersQueries.getById("d1").executeAsOneOrNull())
        assertEquals("d1", verifyDb.feedsQueries.getById("f1").executeAsOne().folder_id)
        verifyDriver.close()
    }

    @Test
    fun feedFolderRemovalPropagatesWhenCloudFolderUpdatedAtIsNewer() {
        // A removes a feed from its folder (folder_id -> NULL) with a newer folder_updated_at; B
        // still has it assigned. The removal must propagate.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 50)
        cloudDb.feedsQueries.updateFolder(null, 200L, 200L, "f1") // removed at folder_updated_at = 200
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFolder("d1", "folder", now = 50)
        localDb.insertFeed("f1", now = 50, folderId = "d1") // folder_updated_at = 50 (older)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNull(verifyDb.feedsQueries.getById("f1").executeAsOne().folder_id)
        verifyDriver.close()
    }

    @Test
    fun localFolderAssignmentKeptWhenNewerThanCloud() {
        // Both devices assigned the same feed to different folders; the newer folder_updated_at wins
        // (here: local). The cloud assignment must be ignored.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("d1", "A", now = 50)
        cloudDb.insertFeed("f1", now = 50, folderId = "d1") // folder_updated_at = 50
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFolder("d2", "B", now = 300)
        localDb.insertFeed("f1", now = 300, folderId = "d2") // folder_updated_at = 300 (newer)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals("d2", verifyDb.feedsQueries.getById("f1").executeAsOne().folder_id)
        verifyDriver.close()
    }

    @Test
    fun cloudOnlyTagAndItsLinkAreImportedWhenLocalFeedIsNewer() {
        // Symptom-1 guard: a tag absent from local (no name clash) and its feed_tag link must both
        // be imported, even when the local feed row is same-or-newer for content reasons.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        cloudDb.insertTag("t1", "Tech", now = 100)
        cloudDb.insertFeedTag("f1", "t1", now = 100)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 500) // same feed id, newer content
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNotNull(verifyDb.tagsQueries.getById("t1").executeAsOneOrNull())
        val active = verifyDb.feed_tagsQueries.watchAllActive().executeAsList()
        assertEquals(1, active.size)
        assertEquals("t1", active[0].tag_id)
        verifyDriver.close()
    }

    @Test
    fun validateSchemaReturnsTrueForValidKeryxDb() {
        val (file, driver, _) = fileDb()
        driver.close()
        assertTrue(DatabaseMerger.validateSchema(file.absolutePath, 1L))
    }

    @Test
    fun validateSchemaReturnsFalseForForeignSchemaDb() {
        val file = java.io.File.createTempFile("foreign", ".db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA user_version = 1")
                st.execute("CREATE TABLE unrelated (x INTEGER)")
            }
        }
        assertFalse(DatabaseMerger.validateSchema(file.absolutePath, 1L))
        file.delete()
    }

    @Test
    fun validateSchemaReturnsFalseForCorruptFile() {
        val file = java.io.File.createTempFile("corrupt", ".db")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        assertFalse(DatabaseMerger.validateSchema(file.absolutePath, 1L))
        file.delete()
    }

    @Test
    fun searchTextPropagatesPlainTextForImportedCloudArticle() {
        // Production stores search_text as HTML-stripped plain text while content keeps raw HTML.
        // The merge must propagate that plain-text search_text, NOT re-derive it from raw content
        // (which would reintroduce searchable tag names/attributes).
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(
            cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100,
            content = "<div class=\"post\">Kotlin rocks</div>", searchText = "Kotlin rocks",
        )
        cloudDriver.close()

        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val merged = verifyDb.articlesQueries.getById("a1").executeAsOne()
        assertEquals("Kotlin rocks", merged.search_text)
        assertFalse(merged.search_text.contains("div"))
        verifyDriver.close()
    }

    @Test
    fun searchTextStaysPlainTextOnConflictUpdateFromCloud() {
        // Cloud wins the OR-merge for content; merged search_text must be cloud's plain text,
        // not a re-derivation from the raw HTML content.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(
            cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 300,
            content = "<p>new body</p>", searchText = "new body",
        )
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(
            localDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100,
            content = "<p>old body</p>", searchText = "old body",
        )
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val merged = verifyDb.articlesQueries.getById("a1").executeAsOne()
        assertEquals("new body", merged.search_text)
        assertFalse(merged.search_text.contains("<p>"))
        verifyDriver.close()
    }

    private fun reopen(file: java.io.File): Triple<java.io.File, app.cash.sqldelight.db.SqlDriver, KeryxDatabase> {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        return Triple(file, driver, KeryxDatabase(driver))
    }
}
