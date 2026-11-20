package works.merc.keryx.app.domain

import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertGlobalSetting
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.stampArticleDeleted
import works.merc.keryx.app.platform.DatabaseMerger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

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
    fun mergedArticleWithContentNeverResurrectsAStaleSummary() {
        // Regression guard: a peer that still carries a stale summary alongside content (a
        // pre-fix row, or a device that hasn't re-fetched) must never resurrect that summary
        // into an already-clean local row once the merged article has content.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(
            cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100,
            content = "cloud body", summary = "stale cloud summary",
        )
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(
            localDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 50,
            content = null, summary = null,
        )
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val merged = verifyDb.articlesQueries.getById("a1").executeAsOne()
        assertEquals("cloud body", merged.content)
        assertNull(merged.summary)
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
    fun mergeThrowsCloudDataIncompatibleForForeignSchema() {
        val cloudFile = java.io.File.createTempFile("foreign", ".db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${cloudFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA user_version = ${KeryxDatabase.Schema.version}")
                st.execute("CREATE TABLE unrelated (x INTEGER)")
            }
        }
        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, KeryxDatabase.Schema.version, MergeSql.all)
        }
        cloudFile.delete()
    }

    @Test
    fun mergeThrowsCloudDataIncompatibleForDuplicateUniqueKeys() {
        // Two cloud feeds rows share a url — only possible because this fixture's feeds table
        // (unlike main's real schema) has no UNIQUE(url). MergeSql's NOT EXISTS guards only rule
        // out collisions against *main*'s rows, so this reaches SQLite's UNIQUE check unguarded.
        val cloudFile = java.io.File.createTempFile("dup-url", ".db")
        java.sql.DriverManager.getConnection("jdbc:sqlite:${cloudFile.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA user_version = ${KeryxDatabase.Schema.version}")
                st.execute(
                    "CREATE TABLE feeds (id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL, site_url TEXT, " +
                        "title TEXT NOT NULL, description TEXT, favicon_url TEXT, etag TEXT, last_modified TEXT, " +
                        "error_count INTEGER NOT NULL DEFAULT 0, last_error TEXT, custom_title TEXT, folder_id TEXT, " +
                        "deleted_at INTEGER, updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                        "sort_order INTEGER NOT NULL DEFAULT 0, folder_updated_at INTEGER, " +
                        "sort_order_updated_at INTEGER, custom_title_updated_at INTEGER, deleted_updated_at INTEGER)",
                )
                st.execute(
                    "CREATE TABLE folders (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL UNIQUE, " +
                        "sort_order INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER, updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL)",
                )
                st.execute(
                    "CREATE TABLE tags (id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL UNIQUE, color TEXT, " +
                        "sort_order INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER, updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL)",
                )
                st.execute(
                    "CREATE TABLE articles (id TEXT NOT NULL PRIMARY KEY, feed_id TEXT NOT NULL, guid TEXT NOT NULL, " +
                        "url TEXT NOT NULL, title TEXT NOT NULL, summary TEXT, content TEXT, author TEXT, " +
                        "published_at INTEGER, thumbnail_url TEXT, is_read INTEGER NOT NULL DEFAULT 0, read_at INTEGER, " +
                        "is_starred INTEGER NOT NULL DEFAULT 0, starred_at INTEGER, cached_at INTEGER NOT NULL, " +
                        "search_text TEXT NOT NULL DEFAULT '', updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL, " +
                        "deleted_at INTEGER, deleted_updated_at INTEGER, UNIQUE (feed_id, guid))",
                )
                st.execute(
                    "CREATE TABLE feed_tags (feed_id TEXT NOT NULL, tag_id TEXT NOT NULL, deleted_at INTEGER, " +
                        "updated_at INTEGER NOT NULL, PRIMARY KEY (feed_id, tag_id))",
                )
                st.execute("CREATE TABLE global_settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)")
                st.execute("INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f-dup-1', 'https://dup.example/feed', 'Dup 1', 0, 0)")
                st.execute("INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f-dup-2', 'https://dup.example/feed', 'Dup 2', 0, 0)")
            }
        }
        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, KeryxDatabase.Schema.version, MergeSql.all)
        }
        cloudFile.delete()
    }

    @Test
    fun mergeThrowsCloudDataIncompatibleForACorruptFile() {
        // A file that passes the 16-byte SQLite header check (SyncRepository's gate, not exercised
        // here) but is corrupt past it: flipping bytes well after the header of an otherwise-valid
        // DB produces a genuine SQLITE_CORRUPT once SQLite reads the damaged page.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 0)
        cloudDriver.close()
        val bytes = cloudFile.readBytes().copyOf()
        for (i in 4096 until minOf(bytes.size, 8192)) {
            bytes[i] = bytes[i].inc()
        }
        cloudFile.writeBytes(bytes)

        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        assertFailsWith<CloudDataIncompatibleException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, KeryxDatabase.Schema.version, MergeSql.all)
        }
    }

    @Test
    fun mergeDoesNotClassifyABrokenLocalSchemaAsCloudDataIncompatible() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 0)
        cloudDriver.close()

        val (localFile, localDriver, _) = fileDb()
        localDriver.execute(null, "DROP TABLE global_settings", 0)
        localDriver.close()

        val thrown = assertFailsWith<Exception> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, KeryxDatabase.Schema.version, MergeSql.all)
        }
        assertFalse(thrown is CloudDataIncompatibleException)
    }

    @Test
    fun mergeRethrowsSchemaVersionExceptionForANewerCloud() {
        val (cloudFile, cloudDriver, _) = fileDb()
        cloudDriver.execute(null, "PRAGMA user_version = 999;", 0)
        cloudDriver.close()

        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        assertFailsWith<SchemaVersionException> {
            DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, KeryxDatabase.Schema.version, MergeSql.all)
        }
    }

    @Test
    fun validateSchemaReturnsTrueForValidKeryxDb() {
        val (file, driver, _) = fileDb()
        driver.close()
        // Also the guard against a schema-version bump silently going unregistered in
        // EXPECTED_SCHEMAS: if it did, this would degrade from `true` to `null` here, not fail
        // silently — see validateSchemaIsUndeterminedForAnUnknownVersion for what `null` means.
        assertEquals(true, DatabaseMerger.validateSchema(file.absolutePath, KeryxDatabase.Schema.version))
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
        assertEquals(false, DatabaseMerger.validateSchema(file.absolutePath, KeryxDatabase.Schema.version))
        file.delete()
    }

    @Test
    fun validateSchemaIsUndeterminedForCorruptFile() {
        val file = java.io.File.createTempFile("corrupt", ".db")
        file.writeBytes(byteArrayOf(1, 2, 3, 4))
        // Not a valid SQLite header, so opening it throws before any table can be inspected —
        // undetermined (null), never false: a failed inspection says nothing about the schema.
        assertNull(DatabaseMerger.validateSchema(file.absolutePath, KeryxDatabase.Schema.version))
        file.delete()
    }

    @Test
    fun validateSchemaIsUndeterminedForAnUnknownVersion() {
        val (file, driver, _) = fileDb()
        driver.close()
        // No EXPECTED_SCHEMAS entry is registered for version 999 — undetermined (null), never
        // false: an unregistered version must not be treated as "definitely incompatible".
        assertNull(DatabaseMerger.validateSchema(file.absolutePath, 999L))
    }

    @Test
    fun articleDeletionPropagatesFromNewerCloudTombstone() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 200)
        cloudDriver.stampArticleDeleted("a1", deletedAt = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(localDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(200L, verifyDb.articlesQueries.getById("a1").executeAsOne().deleted_at)
        verifyDriver.close()
    }

    @Test
    fun localDeletionNotRevertedByOlderCloudLiveArticle() {
        // Core regression: the resurrection bug. Local has soft-deleted the article; the cloud still
        // holds it "alive" (no deletion event) and even with newer content, the merge must not revive it.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 300, content = "newer")
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(localDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100)
        localDriver.stampArticleDeleted("a1", deletedAt = 200)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(200L, verifyDb.articlesQueries.getById("a1").executeAsOne().deleted_at)
        verifyDriver.close()
    }

    @Test
    fun starNewerThanDeletionRevivesArticle() {
        // Device A deleted the article (t=100); device B starred it later (t=200). A later star means
        // the user wants to keep it, so the merge revives (deleted_at -> NULL).
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 200, isStarred = 1, starredAt = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(localDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100)
        localDriver.stampArticleDeleted("a1", deletedAt = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val merged = verifyDb.articlesQueries.getById("a1").executeAsOne()
        assertNull(merged.deleted_at)
        assertEquals(1L, merged.is_starred)
        verifyDriver.close()
    }

    @Test
    fun deletionNewerThanStarKeepsArticleDeleted() {
        // Local starred (t=100); cloud deleted later (t=200). Deletion wins.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 200)
        cloudDriver.stampArticleDeleted("a1", deletedAt = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(localDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100, isStarred = 1, starredAt = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(200L, verifyDb.articlesQueries.getById("a1").executeAsOne().deleted_at)
        verifyDriver.close()
    }

    @Test
    fun readNewerThanDeletionDoesNotRevive() {
        // Only a newer star revives; a newer read/unread change does not.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a1", "f1", "g1", isRead = 1, readAt = 200, updatedAt = 200)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(localDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100)
        localDriver.stampArticleDeleted("a1", deletedAt = 100)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val merged = verifyDb.articlesQueries.getById("a1").executeAsOne()
        assertEquals(100L, merged.deleted_at)
        assertEquals(1L, merged.is_read)
        verifyDriver.close()
    }

    @Test
    fun cloudOnlyDeletedArticleImportedAsTombstone() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f2", now = 100)
        insertArticle(cloudDb, "a9", "f2", "g9", isRead = 0, readAt = null, updatedAt = 100)
        cloudDriver.stampArticleDeleted("a9", deletedAt = 100)
        cloudDriver.close()

        val (localFile, localDriver, _) = fileDb()
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(100L, verifyDb.articlesQueries.getById("a9").executeAsOne().deleted_at)
        verifyDriver.close()
    }

    @Test
    fun localDeletionAtEpochZeroNotClobberedByCloudNoEvent() {
        // Synthetic edge case: Clock.now() never produces timestamp 0 in real usage, but this
        // exercises the NULL-aware fix directly — a real deletion event at t=0 must not be treated
        // as equivalent to "no deletion event" (NULL) and lost to the cloud's live copy.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(localDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 50)
        localDriver.stampArticleDeleted("a1", deletedAt = 0)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertEquals(0L, verifyDb.articlesQueries.getById("a1").executeAsOne().deleted_at)
        verifyDriver.close()
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

    @Test
    fun searchTextTracksWinningBodySourceOnMixedSources() {
        // Mixed sources: cloud has only a summary, local has full content. The OR-merge keeps the
        // local content as the displayed body, so search_text must follow that same source (local),
        // NOT the cloud summary — otherwise FTS would index text the user never sees.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f1", now = 100)
        insertArticle(
            cloudDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 300,
            content = null, summary = "<b>cloud summary</b>", searchText = "cloud summary",
        )
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f1", now = 50)
        insertArticle(
            localDb, "a1", "f1", "g1", isRead = 0, readAt = null, updatedAt = 100,
            content = "<p>local body</p>", summary = null, searchText = "local body",
        )
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        val merged = verifyDb.articlesQueries.getById("a1").executeAsOne()
        assertEquals("<p>local body</p>", merged.content)
        assertEquals("local body", merged.search_text)
        verifyDriver.close()
    }

    // ---- Ties: equal timestamps, different values. The cloud side wins every tie in every
    // last-write-wins statement, so two devices that stamped different values in the same
    // millisecond converge instead of each keeping (and re-uploading) its own. ----

    /** Builds a local and a cloud file via the given setups, merges cloud into local, then runs [verify]. */
    private fun mergeThenVerify(
        local: (KeryxDatabase) -> Unit,
        cloud: (KeryxDatabase) -> Unit,
        verify: (KeryxDatabase) -> Unit,
    ) {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloud(cloudDb)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        local(localDb)
        localDriver.close()

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, verifyDriver, verifyDb) = reopen(localFile)
        try {
            verify(verifyDb)
        } finally {
            verifyDriver.close()
        }
    }

    @Test
    fun tieOnFolderByIdGoesToCloud() = mergeThenVerify(
        local = { it.insertFolder("d1", "Local name", now = 100, sortOrder = 1L) },
        cloud = { it.insertFolder("d1", "Cloud name", now = 100, sortOrder = 5L) },
        verify = {
            val folder = it.foldersQueries.getById("d1").executeAsOne()
            assertEquals("Cloud name", folder.name)
            assertEquals(5L, folder.sort_order)
        },
    )

    @Test
    fun tieOnFolderByNameGoesToCloud() = mergeThenVerify(
        local = { it.insertFolder("d-local", "IT", now = 100, sortOrder = 1L) },
        cloud = { it.insertFolder("d-cloud", "IT", now = 100, sortOrder = 5L) },
        verify = {
            assertEquals(5L, it.foldersQueries.getById("d-local").executeAsOne().sort_order)
        },
    )

    @Test
    fun tieOnFeedContentRowGoesToCloud() = mergeThenVerify(
        local = { it.insertFeed("f1", now = 100, url = "https://local/feed") },
        cloud = { it.insertFeed("f1", now = 100, url = "https://cloud/feed") },
        verify = {
            assertEquals("https://cloud/feed", it.feedsQueries.getById("f1").executeAsOne().url)
        },
    )

    @Test
    fun tieOnTagByIdGoesToCloud() = mergeThenVerify(
        local = { it.insertTag("t1", "Local name", now = 100, sortOrder = 1L) },
        cloud = { it.insertTag("t1", "Cloud name", now = 100, sortOrder = 5L) },
        verify = {
            val tag = it.tagsQueries.getById("t1").executeAsOne()
            assertEquals("Cloud name", tag.name)
            assertEquals(5L, tag.sort_order)
        },
    )

    @Test
    fun tieOnTagByNameGoesToCloud() = mergeThenVerify(
        local = { it.insertTag("t-local", "News", now = 100, sortOrder = 1L) },
        cloud = { it.insertTag("t-cloud", "News", now = 100, sortOrder = 5L) },
        verify = {
            assertEquals(5L, it.tagsQueries.getById("t-local").executeAsOne().sort_order)
        },
    )

    @Test
    fun tieOnFeedTagGoesToCloud() = mergeThenVerify(
        local = {
            it.insertFeed("f1", now = 100)
            it.insertTag("t1", "tag", now = 100)
            it.insertFeedTag("f1", "t1", now = 100, deletedAt = null)
        },
        cloud = {
            it.insertFeed("f1", now = 100)
            it.insertTag("t1", "tag", now = 100)
            it.insertFeedTag("f1", "t1", now = 100, deletedAt = 100) // detached at the same stamp
        },
        verify = {
            // The detached link is soft-deleted, so it is no longer listed for the feed.
            assertEquals(emptyList(), it.feed_tagsQueries.watchTagIdsForFeed("f1").executeAsList())
        },
    )

    @Test
    fun tieOnGlobalSettingGoesToCloud() = mergeThenVerify(
        local = { it.insertGlobalSetting("k1", "local-value", now = 100) },
        cloud = { it.insertGlobalSetting("k1", "cloud-value", now = 100) },
        verify = { assertEquals("cloud-value", it.global_settingsQueries.get("k1").executeAsOne()) },
    )

    // Per-field ties. The local row's content stamp is newer (500) than the cloud's (400) so only
    // the per-field statement can bring the cloud value in.

    @Test
    fun tieOnFeedFolderIdGoesToCloud() = mergeThenVerify(
        local = {
            it.insertFolder("d1", "One", now = 100)
            it.insertFolder("d2", "Two", now = 100)
            it.insertFeed("f1", now = 500)
            it.feedsQueries.updateFolder("d1", 500L, 100L, "f1")
        },
        cloud = {
            it.insertFolder("d1", "One", now = 100)
            it.insertFolder("d2", "Two", now = 100)
            it.insertFeed("f1", now = 400)
            it.feedsQueries.updateFolder("d2", 400L, 100L, "f1")
        },
        verify = { assertEquals("d2", it.feedsQueries.getById("f1").executeAsOne().folder_id) },
    )

    @Test
    fun tieOnFeedSortOrderGoesToCloud() = mergeThenVerify(
        local = {
            it.insertFeed("f1", now = 500)
            it.feedsQueries.updateSortOrder(1L, 500L, 100L, "f1")
        },
        cloud = {
            it.insertFeed("f1", now = 400)
            it.feedsQueries.updateSortOrder(9L, 400L, 100L, "f1")
        },
        verify = { assertEquals(9L, it.feedsQueries.getById("f1").executeAsOne().sort_order) },
    )

    @Test
    fun tieOnFeedCustomTitleGoesToCloud() = mergeThenVerify(
        local = {
            it.insertFeed("f1", now = 500)
            it.feedsQueries.updateCustomTitle("Local name", 500L, 100L, "f1")
        },
        cloud = {
            it.insertFeed("f1", now = 400)
            it.feedsQueries.updateCustomTitle("Cloud name", 400L, 100L, "f1")
        },
        verify = { assertEquals("Cloud name", it.feedsQueries.getById("f1").executeAsOne().custom_title) },
    )

    @Test
    fun tieOnFeedDeletedAtGoesToCloud() = mergeThenVerify(
        local = {
            it.insertFeed("f1", now = 500)
            it.feedsQueries.stampResubscribed(100L, "f1") // alive, subscription state stamped at 100
        },
        cloud = {
            it.insertFeed("f1", now = 400)
            it.feedsQueries.softDelete(77L, 400L, 100L, "f1") // unsubscribed at the same stamp
        },
        verify = { assertEquals(77L, it.feedsQueries.getById("f1").executeAsOne().deleted_at) },
    )

    @Test
    fun strictlyNewerLocalValuesAreStillKeptOverCloud() = mergeThenVerify(
        local = {
            it.insertFolder("d1", "Local name", now = 200)
            it.insertTag("t1", "Local tag", now = 200)
            it.insertGlobalSetting("k1", "local-value", now = 200)
            it.insertFeed("f1", now = 500, url = "https://local/feed")
            it.feedsQueries.updateCustomTitle("Local title", 500L, 200L, "f1")
            it.feedsQueries.updateSortOrder(1L, 500L, 200L, "f1")
        },
        cloud = {
            it.insertFolder("d1", "Cloud name", now = 100)
            it.insertTag("t1", "Cloud tag", now = 100)
            it.insertGlobalSetting("k1", "cloud-value", now = 100)
            it.insertFeed("f1", now = 400, url = "https://cloud/feed")
            it.feedsQueries.updateCustomTitle("Cloud title", 400L, 100L, "f1")
            it.feedsQueries.updateSortOrder(9L, 400L, 100L, "f1")
        },
        verify = {
            assertEquals("Local name", it.foldersQueries.getById("d1").executeAsOne().name)
            assertEquals("Local tag", it.tagsQueries.getById("t1").executeAsOne().name)
            assertEquals("local-value", it.global_settingsQueries.get("k1").executeAsOne())
            val feed = it.feedsQueries.getById("f1").executeAsOne()
            assertEquals("https://local/feed", feed.url)
            assertEquals("Local title", feed.custom_title)
            assertEquals(1L, feed.sort_order)
        },
    )

    @Test
    fun twoDevicesHoldingTiedDifferentValuesConverge() {
        fun deviceA(db: KeryxDatabase) {
            db.insertGlobalSetting("k1", "value-A", now = 100)
            db.insertFeed("f1", now = 500)
            db.feedsQueries.updateCustomTitle("Title A", 500L, 100L, "f1")
        }
        fun deviceB(db: KeryxDatabase) {
            db.insertGlobalSetting("k1", "value-B", now = 100)
            db.insertFeed("f1", now = 500)
            db.feedsQueries.updateCustomTitle("Title B", 500L, 100L, "f1")
        }

        // Step 1: the cloud holds B's database; A merges it and must adopt B's values.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        deviceB(cloudDb)
        cloudDriver.close()
        val (aFile, aDriver, aDb) = fileDb()
        deviceA(aDb)
        aDriver.close()
        DatabaseMerger.merge(aFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val (_, aVerifyDriver, aVerifyDb) = reopen(aFile)
        assertEquals("value-B", aVerifyDb.global_settingsQueries.get("k1").executeAsOne())
        assertEquals("Title B", aVerifyDb.feedsQueries.getById("f1").executeAsOne().custom_title)
        aVerifyDriver.close()

        // Step 2: A uploads its merged database (a copy of it becomes the cloud); B merges it.
        val uploaded = java.io.File.createTempFile("cloud-after-A", ".db").also { it.deleteOnExit() }
        aFile.copyTo(uploaded, overwrite = true)
        val (bFile, bDriver, bDb) = fileDb()
        deviceB(bDb)
        bDriver.close()
        DatabaseMerger.merge(bFile.absolutePath, uploaded.absolutePath, 1L, MergeSql.all)

        val (_, bVerifyDriver, bVerifyDb) = reopen(bFile)
        assertEquals("value-B", bVerifyDb.global_settingsQueries.get("k1").executeAsOne())
        assertEquals("Title B", bVerifyDb.feedsQueries.getById("f1").executeAsOne().custom_title)
        bVerifyDriver.close()
    }

    /** `PRAGMA main.foreign_key_check` rows as "table|rowid|parent|fkid" keys (a raw connection, FKs not enforced). */
    private fun foreignKeyViolations(file: java.io.File): Set<String> =
        java.sql.DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("PRAGMA main.foreign_key_check").use { rs ->
                    buildSet {
                        while (rs.next()) {
                            add("${rs.getString(1)}|${rs.getObject(2)}|${rs.getString(3)}|${rs.getObject(4)}")
                        }
                    }
                }
            }
        }

    // MergeSql's invariant (MergeFailureClassifier.mainRuledOut): no statement brings in an FK violation.
    // SQLite counts immediate FK violations per statement net, so the merge would not fail on one; only
    // foreign_key_check afterwards can show it.
    @Test
    fun mergeSqlBringsInNoForeignKeyViolation() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        // feeds.folder_id -> folders: the folder is absent from the cloud file.
        cloudDb.insertFeed("f-cloud", now = 100, folderId = "gone-folder")
        // articles.feed_id -> feeds: the feed is absent.
        insertArticle(cloudDb, "a-orphan", "gone-feed", "g1", isRead = 0, readAt = null, updatedAt = 100)
        // feed_tags.feed_id -> feeds and feed_tags.tag_id -> tags: each parent absent.
        cloudDb.insertTag("t-cloud", "tag", now = 100)
        cloudDb.insertFeedTag("gone-feed", "t-cloud", now = 100)
        cloudDb.insertFeedTag("f-cloud", "gone-tag", now = 100)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFeed("f-local", now = 50)
        localDriver.close()
        assertEquals(emptySet(), foreignKeyViolations(localFile))

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        assertEquals(emptySet(), foreignKeyViolations(localFile))
        val (_, verifyDriver, verifyDb) = reopen(localFile)
        assertNotNull(verifyDb.feedsQueries.getById("f-cloud").executeAsOneOrNull())
        assertNull(verifyDb.articlesQueries.getById("a-orphan").executeAsOneOrNull())
        verifyDriver.close()
    }

    // The other half of the invariant: no statement modifies an existing local orphan's FK column to
    // add a violation, whichever merge path a cloud row takes to it.
    @Test
    fun mergeSqlNeverAddsToALocalForeignKeyOrphan() {
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFolder("folder-ok", "ok", now = 100)
        cloudDb.insertTag("t-ok", "tag-ok", now = 100)
        // Same feeds as local, every user-edited field newer; the folder assignment points at a folder the
        // cloud file lacks.
        cloudDb.insertFeed("f-orphan-new", now = 500, folderId = "gone-folder")
        cloudDb.insertFeed("f-orphan-old", now = 500)
        cloudDb.insertFeed("f-valid", now = 500, folderId = "gone-folder")
        for (id in listOf("f-orphan-new", "f-orphan-old", "f-valid")) {
            cloudDriver.execute(
                null,
                "UPDATE feeds SET sort_order = 9, sort_order_updated_at = 500, custom_title = 'cloud', " +
                    "custom_title_updated_at = 500, deleted_at = 500, deleted_updated_at = 500 WHERE id = '$id'",
                0,
            )
        }
        // An older folder stamp for the second one: its assignment must not be adopted.
        cloudDriver.execute(null, "UPDATE feeds SET folder_updated_at = 50 WHERE id = 'f-orphan-old'", 0)
        cloudDb.insertFeed("f-ok", now = 100)
        // The local orphan articles' ids, newer read / star / deleted stamps; one with a valid feed in cloud.
        insertArticle(cloudDb, "a-orphan", "f-ok", "g1", isRead = 1, readAt = 500, updatedAt = 500, isStarred = 1, starredAt = 500)
        insertArticle(cloudDb, "a-orphan-gone", "gone-feed", "g2", isRead = 1, readAt = 500, updatedAt = 500, isStarred = 1, starredAt = 500)
        cloudDriver.stampArticleDeleted("a-orphan", 500)
        cloudDriver.stampArticleDeleted("a-orphan-gone", 500)
        cloudDb.insertFeedTag("f-orphan-new", "gone-tag", now = 500, deletedAt = 500)
        cloudDb.insertFeedTag("gone-feed", "t-ok", now = 500, deletedAt = 500)
        cloudDb.insertFeedTag("f-ok", "gone-tag", now = 500)
        cloudDriver.close()

        val (localFile, localDriver, localDb) = fileDb()
        localDb.insertFolder("folder-ok", "ok", now = 100)
        localDb.insertTag("t-ok", "tag-ok", now = 100)
        localDb.insertFeed("f-ok", now = 100)
        localDb.insertFeed("f-orphan-new", now = 100, folderId = "gone-folder")
        localDb.insertFeed("f-orphan-old", now = 100, folderId = "gone-folder")
        localDriver.execute(null, "UPDATE feeds SET folder_updated_at = 400 WHERE id = 'f-orphan-old'", 0)
        // A valid assignment: copying the cloud's dangling folder_id here would add a violation.
        localDb.insertFeed("f-valid", now = 100, folderId = "folder-ok")
        insertArticle(localDb, "a-orphan", "gone-feed", "g1", isRead = 0, readAt = 100, updatedAt = 100)
        insertArticle(localDb, "a-orphan-gone", "gone-feed", "g2", isRead = 0, readAt = 100, updatedAt = 100)
        localDb.insertFeedTag("f-orphan-new", "gone-tag", now = 100)
        localDb.insertFeedTag("gone-feed", "t-ok", now = 100)
        localDb.insertFeedTag("f-ok", "gone-tag", now = 100)
        localDriver.close()

        val before = foreignKeyViolations(localFile)
        // One orphan per FK relation the schema declares.
        assertEquals(
            setOf("feeds", "articles", "feed_tags"),
            before.map { it.substringBefore('|') }.toSet(),
        )
        assertEquals(
            setOf("folders", "feeds", "tags"),
            before.map { it.split('|')[2] }.toSet(),
        )

        DatabaseMerger.merge(localFile.absolutePath, cloudFile.absolutePath, 1L, MergeSql.all)

        val after = foreignKeyViolations(localFile)
        assertEquals(emptySet(), after - before, "the merge added FK violations")
        val (_, verifyDriver, verifyDb) = reopen(localFile)
        // The merge really did reach the rows: their user-edited fields took the cloud's values.
        assertEquals("cloud", verifyDb.feedsQueries.getById("f-orphan-old").executeAsOne().custom_title)
        assertEquals(500L, verifyDb.feedsQueries.getById("f-orphan-old").executeAsOne().deleted_at)
        assertEquals(1L, verifyDb.articlesQueries.getById("a-orphan").executeAsOne().is_read)
        // The older assignment was not adopted, so that orphan is still there.
        assertEquals("gone-folder", verifyDb.feedsQueries.getById("f-orphan-old").executeAsOne().folder_id)
        verifyDriver.close()
    }

    private fun reopen(file: java.io.File): Triple<java.io.File, app.cash.sqldelight.db.SqlDriver, KeryxDatabase> {
        val driver = app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}")
        return Triple(file, driver, KeryxDatabase(driver))
    }
}
