package works.merc.keryx.app.domain

import app.cash.sqldelight.db.QueryResult
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import works.merc.keryx.app.InMemoryFaviconMissStore
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.MILLIS_PER_DAY
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_STATE_KS_ATTEMPT
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Several devices with their own databases share one [FakeCloudStorage] whose listing lags, repeats
 * names and keeps showing deleted files. Seeded random operations — subscribing and unsubscribing,
 * renaming, folders, tags, reading and starring, cache cleanup, a global setting — interleave with
 * syncs in random order, syncs interrupted at every send step (then the "process" restarts),
 * transient cloud failures, folds and garbage collection run from inside another device's sync, long
 * absences, and one reset. At the end every device syncs until nothing changes, and then:
 *
 * - every device shows the same state (observable columns of every synced table);
 * - for every field group anyone ever wrote, that state is the newest write (the oracle) — no
 *   change was lost;
 * - nothing is left to send.
 *
 * Deterministic for a seed: ids, clock, operations, delays and interruptions all come from it.
 */
class SyncSimulationTest {

    private class Interrupt : RuntimeException("simulated crash")

    private class Sim(seed: Int) {
        val random = Random(seed)
        var now = 1_700_000_000_000L
        val clock = Clock { now }
        val ids = idSource(Random(seed * 31 + 7))
        val cloud = FakeCloudStorage()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val urls = (1..6).map { "https://feeds.example/$it.xml" }
        val client = HttpClient(MockEngine { request ->
            respond(rss(request.url.toString()), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/rss+xml"))
        }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        val failingClient = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        var nameCounter = 0
        var crashes = 0
        var interleavings = 0
        var syncs = 0

        /** field group key -> (stamp, value): the newest write anyone made. */
        val oracle = mutableMapOf<String, Pair<Long, String>>()

        fun tick(max: Long = 5_000) {
            now += 1 + random.nextLong(max)
        }

        fun rss(url: String): String {
            val n = url.substringAfterLast('/').substringBefore('.')
            val items = (1..12).joinToString("") { i ->
                "<item><guid>$url#$i</guid><title>Item $n.$i</title><link>https://site.example/$n/$i</link>" +
                    "<description>&lt;p&gt;Body $n $i&lt;/p&gt;</description>" +
                    "<pubDate>Mon, ${String.format("%02d", i)} Jan 2024 10:00:00 GMT</pubDate></item>"
            }
            return "<?xml version=\"1.0\"?><rss version=\"2.0\"><channel><title>Feed $n</title>" +
                "<link>https://site.example/$n</link><description>d</description>$items</channel></rss>"
        }
    }

    private class Device(val sim: Sim, label: String) : AutoCloseable {
        val sync = SyncDevice(label, sim.cloud, sim.clock, sim.scope, sim.ids)
        val db get() = sync.db
        val articles = ArticleRepository(db, FtsSearch(sync.driver), SyncScheduler {}, sim.clock, Dispatchers.Unconfined)
        val feeds = FeedRepository(
            db, FeedFetcher(sim.client), FaviconResolver(sim.failingClient), articles, sync.fts, SyncScheduler {},
            NotificationCenter(), sim.clock, Dispatchers.Unconfined, faviconMisses = InMemoryFaviconMissStore(),
        )
        val folders = FolderRepository(db, feeds, SyncScheduler {}, sim.clock, Dispatchers.Unconfined)
        val tags = TagRepository(db, SyncScheduler {}, sim.clock, Dispatchers.Unconfined)

        override fun close() = sync.close()
    }

    @Test
    fun devicesConvergeUnderRandomOperationsInterruptionsAndDelays() {
        for (seed in listOf(11, 23, 37)) runSimulation(seed, operations = 400)
    }

    private fun runSimulation(seed: Int, operations: Int) = runBlocking {
        val sim = Sim(seed)
        val devices = (0 until 3).map { Device(sim, "d$it-s$seed") }
        try {
            val resetAt = operations / 2
            for (step in 0 until operations) {
                sim.tick()
                sim.cloud.listingDelay = { sim.random.nextLong(3) }
                sim.cloud.ghostListings = { sim.random.nextLong(2) }
                sim.cloud.duplicateNames = sim.random.nextInt(10) == 0
                val device = devices[sim.random.nextInt(devices.size)]
                when {
                    step == resetAt -> {
                        val result = device.sync.repo.resetCloudData()
                        if (result is Result.Err) fail("seed $seed step $step: reset failed: ${result.exception}")
                    }
                    sim.random.nextInt(100) < 40 -> syncWithChaos(sim, device, devices, "seed $seed step $step")
                    else -> operate(sim, device)
                }
                record(sim, device)
                if (sim.random.nextInt(100) < 3) sim.now += 3 * MILLIS_PER_DAY
            }
            // The run must have exercised what it is about, or it proves nothing.
            val written = sim.cloud.written.mapNotNull(TransportFileName::parse)
            val stats = "syncs=${sim.syncs} crashes=${sim.crashes} interleavings=${sim.interleavings} " +
                "bases=${written.count { it.kind == TransportFileKind.BASE }} deltas=${written.count { it.kind == TransportFileKind.DELTA }} " +
                "deletes=${sim.cloud.deleteCount} generations=${written.map { it.generation }.distinct().size}"
            assertTrue(sim.crashes > 0 && sim.interleavings > 0, "seed $seed: $stats")
            assertTrue(written.count { it.kind == TransportFileKind.BASE } > 3 && sim.cloud.deleteCount > 0, "seed $seed: $stats")
            assertEquals(2, written.map { it.generation }.distinct().size, "seed $seed: $stats")
            quiesce(sim, devices, seed)
            devices.forEach { record(sim, it) }

            val dumps = devices.map { dump(it) }
            for (i in 1 until dumps.size) {
                assertEquals(dumps[0], dumps[i], "seed $seed: ${devices[0].sync.label} and ${devices[i].sync.label} differ")
            }
            for (device in devices) {
                checkOracle(sim, device, seed)
                assertTrue(device.sync.outbox().isEmpty(), "seed $seed: ${device.sync.label} has unsent changes")
                assertEquals(0L, device.sync.capture().full_pending)
                assertNull(device.sync.state(SYNC_STATE_KS_ATTEMPT))
            }
        } finally {
            devices.forEach { it.close() }
            sim.scope.cancel()
            sim.client.close()
            sim.failingClient.close()
        }
    }

    /** One sync of [device], possibly interrupted, failing transiently, or interleaved with another device's. */
    private suspend fun syncWithChaos(sim: Sim, device: Device, devices: List<Device>, where: String) {
        val r = sim.random
        var injectedFailure = false
        when (r.nextInt(20)) {
            0 -> { sim.cloud.queueDownload(Result.Err(CloudStorageException("injected"))); injectedFailure = true }
            1 -> { sim.cloud.queueCreate(Result.Err(CloudStorageException("injected"))); injectedFailure = true }
            2 -> { sim.cloud.queueList(Result.Err(CloudStorageException("injected"))); injectedFailure = true }
        }
        val crashAt = if (r.nextInt(4) == 0) SyncHookPoint.entries[r.nextInt(SyncHookPoint.entries.size)] else null
        val other = devices.filter { it !== device }[r.nextInt(devices.size - 1)]
        val interleaveAt = if (r.nextInt(6) == 0) listOf(SyncHookPoint.BEFORE_BASE_UPLOAD, SyncHookPoint.BEFORE_GC_DELETE)[r.nextInt(2)] else null
        var interleaved = false
        device.sync.repo.testHook = { point, _ ->
            if (point == interleaveAt && !interleaved) {
                interleaved = true
                sim.interleavings++
                sim.tick(50)
                val nested = other.sync.repo.sync()
                if (nested is Result.Err) checkTolerated(nested.exception, "$where (nested)", injectedFailure)
            }
            if (point == crashAt) {
                device.sync.repo.testHook = null
                throw Interrupt()
            }
        }
        try {
            sim.syncs++
            val result = device.sync.repo.sync()
            if (result is Result.Err) checkTolerated(result.exception, where, injectedFailure)
        } catch (_: Interrupt) {
            sim.crashes++
            device.sync.restart()
        } finally {
            device.sync.repo.testHook = null
            sim.cloud.clearQueues()
        }
    }

    private fun checkTolerated(e: Exception, where: String, injected: Boolean) {
        if (e is CloudDataIncompatibleException || e is SchemaVersionException) fail("$where: unexpected $e")
        if (!injected) fail("$where: unexpected failure $e")
    }

    /** One random user action on [device]. */
    private suspend fun operate(sim: Sim, device: Device) {
        val r = sim.random
        val db = device.db
        val aliveFeeds = db.feedsQueries.getAllIncludingDeleted().executeAsList().filter { it.deleted_at == null }
        val aliveFolders = db.foldersQueries.watchAll().executeAsList().filter { it.deleted_at == null }
        val aliveTags = db.tagsQueries.watchAll().executeAsList().filter { it.deleted_at == null }
        val liveArticles = device.sync.driver.executeQuery(null, "SELECT id FROM articles WHERE deleted_at IS NULL ORDER BY id", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(0)!!) })
        }, 0).value
        fun name(kind: String) = "$kind-${device.sync.label}-${sim.nameCounter++}"
        when (r.nextInt(100)) {
            in 0..11 -> device.feeds.subscribeFeed(sim.urls[r.nextInt(sim.urls.size)])
            in 12..15 -> aliveFeeds.randomOrNull(r)?.let { device.feeds.unsubscribeFeed(it.id) }
            in 16..20 -> aliveFeeds.randomOrNull(r)?.let { device.feeds.renameFeed(it.id, if (r.nextBoolean()) name("title") else null) }
            in 21..24 -> device.folders.createFolder(name("folder"))
            in 25..27 -> aliveFolders.randomOrNull(r)?.let { device.folders.updateFolder(it.id, name("folder")) }
            in 28..29 -> aliveFolders.randomOrNull(r)?.let { device.folders.deleteFolder(it.id) }
            in 30..34 -> aliveFeeds.randomOrNull(r)?.let { feed -> device.feeds.moveFeed(feed.id, aliveFolders.randomOrNull(r)?.id) }
            in 35..37 -> device.tags.createTag(name("tag"), if (r.nextBoolean()) "#ff0000" else null)
            in 38..39 -> aliveTags.randomOrNull(r)?.let { device.tags.updateTag(it.id, name("tag"), "#00ff00") }
            in 40..41 -> aliveTags.randomOrNull(r)?.let { device.tags.deleteTag(it.id) }
            in 42..49 -> {
                val feed = aliveFeeds.randomOrNull(r)
                val tag = aliveTags.randomOrNull(r)
                if (feed != null && tag != null) device.tags.setFeedTag(feed.id, tag.id, r.nextBoolean())
            }
            in 50..69 -> liveArticles.randomOrNull(r)?.let { if (r.nextBoolean()) device.articles.markAsRead(it) else device.articles.markAsUnread(it) }
            in 70..86 -> liveArticles.randomOrNull(r)?.let { device.articles.setStarred(it, r.nextBoolean()) }
            in 87..92 -> device.articles.deleteExpiredArticles(1)
            else -> db.global_settingsQueries.upsert("read_timeout_seconds", (10 + r.nextInt(50)).toString(), sim.now)
        }
    }

    /** Folds [device]'s current field groups into the oracle (the newest stamp wins). */
    private fun record(sim: Sim, device: Device) {
        for ((key, stamp, value) in groups(device)) {
            if (stamp == null) continue
            val known = sim.oracle[key]
            if (known == null || stamp > known.first) sim.oracle[key] = stamp to value
        }
    }

    private fun checkOracle(sim: Sim, device: Device, seed: Int) {
        val current = groups(device).associate { (key, stamp, value) -> key to (stamp to value) }
        // A feed's folder is what the feed list shows: the folder while it is alive, else none. (A merge
        // may null a reference to a folder deleted elsewhere without a stamp of its own.)
        fun effectiveFolder(folderId: String): String =
            if (folderId != "null" && sim.oracle["folder $folderId alive"]?.second == "1") folderId else "null"
        for ((key, expected) in sim.oracle) {
            val actual = current[key]
            val isFolder = key.endsWith(" folder")
            val want = expected.first to (if (isFolder) effectiveFolder(expected.second) else expected.second)
            val got = actual?.let { (it.first ?: -1L) to (if (isFolder) effectiveFolder(it.second) else it.second) }
            assertEquals(want, got, "seed $seed: ${device.sync.label}: $key lost its newest write")
        }
    }

    private data class Group(val key: String, val stamp: Long?, val value: String)

    /** Every last-write-wins field group of [device]'s synced rows, by stable identity. */
    private fun groups(device: Device): List<Group> = buildList {
        val driver = device.sync.driver
        fun rows(sql: String): List<List<String?>> = driver.executeQuery(null, sql, { c ->
            QueryResult.Value(buildList { while (c.next().value) add((0 until COLUMNS).map { i -> runCatching { c.getString(i) }.getOrNull() }) })
        }, 0).value
        for (r in rows("SELECT id, deleted_updated_at, deleted_at IS NULL, custom_title_updated_at, custom_title, folder_updated_at, folder_id FROM feeds")) {
            add(Group("feed ${r[0]} deleted", r[1]?.toLong(), r[2]!!))
            add(Group("feed ${r[0]} title", r[3]?.toLong(), r[4].toString()))
            add(Group("feed ${r[0]} folder", r[5]?.toLong(), r[6].toString()))
        }
        for (r in rows("SELECT id, updated_at, deleted_at IS NULL, name_updated_at, name FROM folders")) {
            add(Group("folder ${r[0]} alive", r[1]?.toLong(), r[2]!!))
            add(Group("folder ${r[0]} name", r[3]?.toLong(), r[4]!!))
        }
        for (r in rows("SELECT id, updated_at, deleted_at IS NULL, name_updated_at, name, color_updated_at, color FROM tags")) {
            add(Group("tag ${r[0]} alive", r[1]?.toLong(), r[2]!!))
            add(Group("tag ${r[0]} name", r[3]?.toLong(), r[4]!!))
            add(Group("tag ${r[0]} color", r[5]?.toLong(), r[6].toString()))
        }
        for (r in rows("SELECT feed_id, tag_id, updated_at, deleted_at IS NULL FROM feed_tags")) {
            add(Group("feed_tag ${r[0]}/${r[1]}", r[2]?.toLong(), r[3]!!))
        }
        for (r in rows("SELECT id, read_at, is_read, MAX(COALESCE(starred_at, 0), COALESCE(deleted_updated_at, 0)), is_starred || '/' || (deleted_at IS NULL) FROM articles")) {
            add(Group("article ${r[0]} read", r[1]?.toLong(), r[2]!!))
            add(Group("article ${r[0]} retention", r[3]?.toLong()?.takeIf { it > 0 }, r[4]!!))
        }
        for (r in rows("SELECT key, updated_at, value FROM global_settings")) {
            add(Group("setting ${r[0]}", r[1]?.toLong(), r[2]!!))
        }
    }

    /** What a user can observe, per table, with ids where they are stable and effective references. */
    private fun dump(device: Device): Map<String, List<String>> {
        val driver = device.sync.driver
        fun rows(sql: String): List<String> = driver.executeQuery(null, sql, { c ->
            QueryResult.Value(buildList { while (c.next().value) add((0 until COLUMNS).joinToString("|") { i -> runCatching { c.getString(i) }.getOrNull().toString() }) })
        }, 0).value.sorted()
        return mapOf(
            "feeds" to rows(
                "SELECT f.id, f.url, f.title, f.custom_title, f.deleted_at IS NULL, f.sort_order, " +
                    "(SELECT d.name FROM folders d WHERE d.id = f.folder_id AND d.deleted_at IS NULL) FROM feeds f",
            ),
            "folders" to rows("SELECT id, name, sort_order, deleted_at IS NULL FROM folders"),
            "tags" to rows("SELECT id, name, color, sort_order, deleted_at IS NULL FROM tags"),
            "feed_tags" to rows(
                "SELECT ft.feed_id, ft.tag_id FROM feed_tags ft JOIN tags t ON t.id = ft.tag_id " +
                    "WHERE ft.deleted_at IS NULL AND t.deleted_at IS NULL",
            ),
            "articles" to rows("SELECT id, feed_id, guid, title, content IS NOT NULL OR summary IS NOT NULL, is_read, is_starred, deleted_at IS NULL FROM articles"),
            "global_settings" to rows("SELECT key, value FROM global_settings"),
            "search" to device.articles.search("Body", ArticleFilter.All).map { it.article.id }.sorted(),
        )
    }

    /** Everyone syncs, with the cloud now consistent, until a whole round changes nothing. */
    private suspend fun quiesce(sim: Sim, devices: List<Device>, seed: Int) {
        sim.cloud.listingDelay = { 0L }
        sim.cloud.ghostListings = { 0L }
        sim.cloud.duplicateNames = false
        sim.cloud.clearQueues()
        sim.cloud.files.replaceAll { _, f -> FakeCloudStorage.StoredFile(f.bytes, f.rev, 0) }
        var quietRounds = 0
        repeat(12) { round ->
            val writesBefore = sim.cloud.written.size + sim.cloud.deleteCount
            for (device in devices) {
                sim.tick(1_000)
                val result = device.sync.repo.sync()
                if (result is Result.Err) fail("seed $seed quiesce round $round ${device.sync.label}: ${result.exception}")
            }
            if (sim.cloud.written.size + sim.cloud.deleteCount == writesBefore && devices.map(::dump).distinct().size == 1) {
                if (++quietRounds == 2) return
            } else {
                quietRounds = 0
            }
        }
        fail("seed $seed: the devices never became quiescent")
    }

    private companion object {
        const val COLUMNS = 8
    }
}
