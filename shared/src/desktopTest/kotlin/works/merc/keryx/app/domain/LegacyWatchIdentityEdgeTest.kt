package works.merc.keryx.app.domain

import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.CLOUD_DB_GZ_PATH
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.insertFeedTag
import works.merc.keryx.app.insertTag
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * An identity edge that only a pre-delta file brings (absorbed by the legacy watch of a device that
 * has already joined its generation) must reach the other devices: an ordinary delta carries rows,
 * not the edges of `ks_v2_edge`, and a joined device sends no full file.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyWatchIdentityEdgeTest {

    private val devices = mutableListOf<SyncDevice>()
    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private val ids = idSource(Random(11))

    @AfterTest
    fun tearDown() {
        devices.forEach { it.close() }
    }

    private fun TestScope.device(label: String, cloud: FakeCloudStorage): SyncDevice =
        SyncDevice(label, cloud, clock, backgroundScope, ids).also(devices::add)

    private suspend fun SyncDevice.syncOk() {
        val result = repo.sync()
        assertIs<Result.Ok<Unit>>(result, "$label: $result")
    }

    private fun SyncDevice.column(sql: String): List<String> = driver.executeQuery(null, sql, { c ->
        QueryResult.Value(buildList { while (c.next().value) add(c.getString(0)!!) })
    }, 0).value

    private fun SyncDevice.aliveTagsOfFeed(feedId: String): List<String> = column(
        "SELECT t.id FROM feed_tags ft JOIN tags t ON t.id = ft.tag_id " +
            "WHERE ft.feed_id = '$feedId' AND ft.deleted_at IS NULL AND t.deleted_at IS NULL ORDER BY t.id",
    )

    @Test
    fun anEdgeOnlyTheLegacyFileBringsReachesADeviceThatReceivesJustTheDelta() = runTest {
        // The canonical id is the smaller one, so make E's own tag (which carries the pre-upgrade
        // id) the smaller: the folded row E sends then names no id F does not already know.
        val (smallName, largeName) = listOf("office", "job").sortedBy { IdGenerator.tagId(it) }
        val eTag = IdGenerator.tagId(smallName)
        val fTag = IdGenerator.tagId(largeName)
        assertTrue(eTag < fTag)
        val preUpgradeId = "R_L"

        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_GZ_PATH, PreDeltaFiles.stub(), rev = "stub-1")
        val e = device("e", cloud)
        val f = device("f", cloud)
        e.db.insertFeed("fx", now = 1)
        e.syncOk()
        f.syncOk()

        // E renamed the pre-upgrade row R_L to smallName before upgrading; F made largeName itself.
        e.db.insertTag(eTag, smallName, now = 10)
        e.driver.execute(null, "UPDATE tags SET v2_id = '$preUpgradeId' WHERE id = '$eTag'", 0)
        e.driver.execute(null, "INSERT OR IGNORE INTO ks_v2_edge (kind, v2_id, id) VALUES ('tag', '$preUpgradeId', '$eTag')", 0)
        e.db.insertFeedTag("fx", eTag, now = 10)
        f.db.insertTag(fTag, largeName, now = 10)
        f.db.insertFeedTag("fx", fTag, now = 10)
        e.syncOk(); f.syncOk(); e.syncOk(); f.syncOk()
        assertEquals(listOf(eTag, fTag).sorted(), e.aliveTagsOfFeed("fx"), "precondition: E holds both tags")
        assertEquals(listOf(eTag, fTag).sorted(), f.aliveTagsOfFeed("fx"), "precondition: F holds both tags")

        // The old pre-delta file (an older build wrote it over the stub) names R_L largeName: the
        // edge (R_L, fTag) exists only there. E absorbs it and puts the stub back; F never sees it.
        cloud.put(
            CLOUD_DB_GZ_PATH,
            PreDeltaFiles.database("INSERT INTO tags (id, name, sort_order, updated_at, created_at) VALUES ('$preUpgradeId', '$largeName', 0, 1, 1)"),
        )
        e.syncOk()
        assertTrue(PreDeltaFiles.isStub(cloud.bytesOf(CLOUD_DB_GZ_PATH)), "E put the stub back")
        f.syncOk(); e.syncOk(); f.syncOk()

        assertEquals(1, e.aliveTagsOfFeed("fx").size, "E folded the two tags into one identity: ${e.aliveTagsOfFeed("fx")}")
        assertEquals(e.aliveTagsOfFeed("fx"), f.aliveTagsOfFeed("fx"), "F ends on the same tags as E")
    }
}
