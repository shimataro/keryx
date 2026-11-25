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
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.InMemoryFaviconMissStore
import works.merc.keryx.app.createSchemaV2
import works.merc.keryx.app.data.local.db.KeryxDatabase
import java.io.File
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.MILLIS_PER_DAY
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_STATE_KS_ATTEMPT
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
 * - for every field group any device's local write ever changed, that state is the newest such write
 *   (the oracle, built from local writes only) — no change was lost;
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

        /** The small pool the shared-name create/rename operations pick from, so names collide. */
        val sharedNames = listOf("Shared-A", "Shared-B", "Shared-C")

        /**
         * The shared-name choices come from their own stream, so colliding names ride along with the
         * main operation stream instead of reshuffling every other decision.
         */
        val sharedRandom = Random(seed * 31 + 11)

        /** How many operations picked from [sharedNames] (the run must have exercised collisions). */
        var sharedNameOps = 0

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
                    "<pubDate>${WEEKDAYS[(i - 1) % 7]}, ${i.toString().padStart(2, '0')} Jan 2024 10:00:00 GMT</pubDate></item>"
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
        val folders = FolderRepository(db, feeds, SyncScheduler {}, sim.clock, Dispatchers.Unconfined, newId = sim.ids)
        val tags = TagRepository(db, SyncScheduler {}, sim.clock, Dispatchers.Unconfined, newId = sim.ids)

        override fun close() = sync.close()
    }

    @Test
    fun devicesConvergeUnderRandomOperationsInterruptionsAndDelays() {
        for (seed in listOf(11, 23, 37)) runSimulation(seed, operations = 400)
    }

    /**
     * The same, starting from a pre-delta cloud (only `keryx.db.gz`), which the first device migrates,
     * and with an older build now and then writing a full database over the stub (Google Drive's
     * non-atomic revision check), which the legacy watch absorbs — under the same interruptions,
     * including at every migration and legacy-watch step.
     */
    @Test
    fun devicesConvergeFromAPreDeltaCloudWithAnOlderBuildStillWriting() {
        runSimulation(53, operations = 300, legacyStart = true)
    }

    /**
     * Devices that shared v2-era tag and folder rows and each renamed them differently before
     * upgrading (every derived id differs, the pre-upgrade id is shared): whatever order they sync in,
     * every device ends on the same single tag and folder — the smallest derived id — with the same
     * link, folder assignment and values.
     */
    @Test
    fun devicesThatRenamedSharedV2RowsDifferentlyConvergeOnTheSmallestIdInEveryOrder() = runBlocking {
        val tagNames = listOf("Alpha", "Beta", "Gamma")
        val folderNames = listOf("AlphaDir", "BetaDir", "GammaDir")
        val tagId = tagNames.minOf { IdGenerator.tagId(it) }
        val folderId = folderNames.minOf { IdGenerator.folderId(it) }
        // Which devices sync first, and in what order: every permutation of the three, then a round of all.
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (order in orders) {
            val sim = Sim(61)
            val devices = (0 until 3).map { i ->
                SyncDevice("v2-$i", sim.cloud, sim.clock, sim.scope, sim.ids, existingDbFile = migratedV2Device(tagNames[i], folderNames[i], stamp = 100L * (i + 1)))
            }
            try {
                for (i in order) {
                    sim.tick(1_000)
                    val result = devices[i].repo.sync()
                    if (result is Result.Err) fail("order $order: device $i: ${result.exception}")
                }
                // Everyone syncs until a whole round changes nothing in the cloud.
                var quiet = 0
                repeat(10) {
                    if (quiet == 2) return@repeat
                    val before = sim.cloud.written.size + sim.cloud.deleteCount
                    for (device in devices) {
                        sim.tick(1_000)
                        val result = device.repo.sync()
                        if (result is Result.Err) fail("order $order: ${device.label}: ${result.exception}")
                    }
                    quiet = if (sim.cloud.written.size + sim.cloud.deleteCount == before) quiet + 1 else 0
                }
                val dumps = devices.map { TransportFixtures.dump(it.file, local = true) }
                for (i in 1 until dumps.size) assertEquals(dumps[0], dumps[i], "order $order: device 0 and $i differ")
                for (device in devices) {
                    assertEquals(listOf(tagId), device.db.tagsQueries.watchAll().executeAsList().map { it.id }, "order $order: ${device.label}")
                    assertEquals(listOf(folderId), device.db.foldersQueries.watchAll().executeAsList().map { it.id }, "order $order: ${device.label}")
                    assertEquals(listOf(tagId), device.db.feed_tagsQueries.watchTagIdsForFeed("f1").executeAsList(), "order $order: ${device.label}")
                    assertEquals(folderId, device.db.feedsQueries.getById("f1").executeAsOne().folder_id, "order $order: ${device.label}")
                    // The newest rename (device 2's, stamp 300) is the one every device shows.
                    assertEquals("Gamma", device.db.tagsQueries.getById(tagId).executeAsOne().name, "order $order: ${device.label}")
                    assertTrue(device.outbox().isEmpty(), "order $order: ${device.label} has unsent changes")
                }
            } finally {
                devices.forEach { it.close() }
                sim.scope.cancel()
                sim.client.close()
                sim.failingClient.close()
            }
        }
    }

    /**
     * The same shared v2 folder renamed differently on three devices (three aliases), and each device
     * moves the one feed within the same millisecond — two into their own alias, one into a folder of
     * its own. The assignments tie on their stamp, and the aliases are re-keyed onto the smallest id
     * while they sync, so a tie settled on mapped ids would depend on the history. Whatever order the
     * devices sync in, every device stores the same raw assignment (one the devices wrote, as written),
     * resolves the same folder, and holds the same folders and remaining synced state.
     */
    @Test
    fun sameStampFolderMovesIntoV2AliasesConvergeInEveryOrder() = runBlocking {
        val tagNames = listOf("Alpha", "Beta", "Gamma")
        val folderNames = listOf("AlphaDir", "BetaDir", "GammaDir")
        val canonical = folderNames.minOf { IdGenerator.folderId(it) }
        val ownFolder = "zz-own-folder"
        // What each device writes as the feed's raw folder, all at one stamp.
        val written = folderNames.mapIndexed { i, name -> if (i == 2) ownFolder else IdGenerator.folderId(name) }.toSet()
        val orders = listOf(listOf(0, 1, 2), listOf(0, 2, 1), listOf(1, 0, 2), listOf(1, 2, 0), listOf(2, 0, 1), listOf(2, 1, 0))
        for (order in orders) {
            val sim = Sim(67)
            val devices = (0 until 3).map { i ->
                SyncDevice("same-stamp-$i", sim.cloud, sim.clock, sim.scope, sim.ids, existingDbFile = migratedV2Device(tagNames[i], folderNames[i], stamp = 100L * (i + 1)))
            }
            try {
                // The same-millisecond moves, before any device has synced (capture is still off, so the
                // first sync sends each device's whole state).
                devices.forEachIndexed { i, device ->
                    if (i == 2) {
                        device.driver.execute(
                            null,
                            "INSERT INTO folders (id, name, sort_order, updated_at, created_at, name_updated_at, sort_order_updated_at) " +
                                "VALUES ('$ownFolder', 'Own', 9, 400, 400, 400, 400)",
                            0,
                        )
                    }
                    val target = if (i == 2) ownFolder else IdGenerator.folderId(folderNames[i])
                    device.driver.execute(
                        null,
                        "UPDATE ks_feed_folder_raw SET folder_id = '$target', folder_updated_at = 500 WHERE feed_id = 'f1'",
                        0,
                    )
                }
                for (i in order) {
                    sim.tick(1_000)
                    val result = devices[i].repo.sync()
                    if (result is Result.Err) fail("order $order: device $i: ${result.exception}")
                }
                var quiet = 0
                repeat(10) {
                    if (quiet == 2) return@repeat
                    val before = sim.cloud.written.size + sim.cloud.deleteCount
                    for (device in devices) {
                        sim.tick(1_000)
                        val result = device.repo.sync()
                        if (result is Result.Err) fail("order $order: ${device.label}: ${result.exception}")
                    }
                    quiet = if (sim.cloud.written.size + sim.cloud.deleteCount == before) quiet + 1 else 0
                }
                val dumps = devices.map { TransportFixtures.dump(it.file, local = true) }
                for (i in 1 until dumps.size) assertEquals(dumps[0], dumps[i], "order $order: device 0 and $i differ")
                val raw = devices.map { it.rawFolders() }
                for (i in 1 until raw.size) assertEquals(raw[0], raw[i], "order $order: raw assignments of device 0 and $i differ")
                val rawFolder = raw[0].single().split("|")
                assertEquals("500", rawFolder[1], "order $order")
                assertTrue(rawFolder[2] in written, "order $order: raw ${rawFolder[2]} is not a value a device wrote")
                for (device in devices) {
                    assertEquals(setOf(canonical, ownFolder), device.db.foldersQueries.watchAll().executeAsList().map { it.id }.toSet(), "order $order: ${device.label}")
                    val resolved = device.db.feedsQueries.getById("f1").executeAsOne().folder_id
                    assertEquals(if (rawFolder[2] == ownFolder) ownFolder else canonical, resolved, "order $order: ${device.label}")
                    assertTrue(device.outbox().isEmpty(), "order $order: ${device.label} has unsent changes")
                }
            } finally {
                devices.forEach { it.close() }
                sim.scope.cancel()
                sim.client.close()
                sim.failingClient.close()
            }
        }
    }

    /**
     * A version-2 database holding the shared pre-upgrade tag "old-tag" and folder "old-folder", renamed
     * to [tagName] / [folderName] at [stamp], with feed f1 in the folder and linked to the tag — migrated
     * to the current schema, which re-keys both onto the ids their names derive and keeps the old id as `v2_id`.
     */
    private fun migratedV2Device(tagName: String, folderName: String, stamp: Long): File {
        val file = File.createTempFile("v2sim", ".db").apply { deleteOnExit() }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            driver.createSchemaV2()
            listOf(
                "INSERT INTO folders (id, name, sort_order, updated_at, created_at) VALUES ('old-folder', '$folderName', 0, $stamp, 1)",
                "INSERT INTO tags (id, name, color, sort_order, updated_at, created_at) VALUES ('old-tag', '$tagName', NULL, 0, $stamp, 1)",
                "INSERT INTO feeds (id, url, title, etag, folder_id, folder_updated_at, updated_at, created_at) " +
                    "VALUES ('f1', 'https://feeds.example/v2.xml', 'Feed', 'e', 'old-folder', 50, 100, 1)",
                "INSERT INTO feed_tags (feed_id, tag_id, updated_at) VALUES ('f1', 'old-tag', 50)",
            ).forEach { driver.execute(null, it, 0) }
        }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            KeryxDatabase.Schema.migrate(driver, 2, KeryxDatabase.Schema.version, *KERYX_SCHEMA_CALLBACKS)
            driver.execute(null, "PRAGMA user_version=${KeryxDatabase.Schema.version}", 0)
        }
        return file
    }

    private fun runSimulation(seed: Int, operations: Int, legacyStart: Boolean = false) = runBlocking {
        val sim = Sim(seed)
        val devices = (0 until 3).map { Device(sim, "d$it-s$seed") }
        try {
            var oldBuildWrites = 0
            if (legacyStart) {
                sim.cloud.put("keryx.db.gz", legacyDatabase(sim, readAt = null))
                val first = devices[0].sync.repo.sync()
                if (first is Result.Err) fail("seed $seed: the migration failed: ${first.exception}")
                // The older build's database is a write like any device's own. What the migration must
                // bring in is derived from the fixture, never read back from the migrated database, so a
                // migration that drops or alters a row fails here rather than becoming the expectation.
                record(sim, legacyOracle())
                checkOracle(sim, devices[0], seed)
            }
            val resetAt = operations / 2
            for (step in 0 until operations) {
                sim.tick()
                if (legacyStart && sim.random.nextInt(100) < 3) {
                    // An older build syncs and replaces the stub with its full database.
                    oldBuildWrites++
                    sim.cloud.put("keryx.db.gz", legacyDatabase(sim, readAt = sim.now, extraFeed = "old-${sim.nameCounter++}"))
                    // Its only stamped change: la1 read at now (the extra feed carries no stamped group).
                    record(sim, listOf(Group("article la1 read", sim.now, "1")))
                }
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
                    else -> {
                        val before = groups(device).toSet()
                        operate(sim, device)
                        record(sim, groups(device).filter { it !in before })
                    }
                }
                if (sim.random.nextInt(100) < 3) sim.now += 3 * MILLIS_PER_DAY
            }
            // The run must have exercised what it is about, or it proves nothing.
            val written = sim.cloud.written.mapNotNull(TransportFileName::parse)
            val stats = "syncs=${sim.syncs} crashes=${sim.crashes} interleavings=${sim.interleavings} " +
                "bases=${written.count { it.kind == TransportFileKind.BASE }} deltas=${written.count { it.kind == TransportFileKind.DELTA }} " +
                "deletes=${sim.cloud.deleteCount} generations=${written.map { it.generation }.distinct().size}"
            assertTrue(sim.crashes > 0 && sim.interleavings > 0, "seed $seed: $stats")
            assertTrue(written.count { it.kind == TransportFileKind.BASE } > 3 && sim.cloud.deleteCount > 0, "seed $seed: $stats")
            assertTrue(sim.sharedNameOps > 0, "seed $seed: no shared-name operation ran ($stats)")
            assertEquals(2, written.map { it.generation }.distinct().size, "seed $seed: $stats")
            if (legacyStart) assertTrue(oldBuildWrites > 0, "seed $seed: no older build wrote")
            quiesce(sim, devices, seed)

            val dumps = devices.map { dump(it) }
            for (i in 1 until dumps.size) {
                assertEquals(dumps[0], dumps[i], "seed $seed: ${devices[0].sync.label} and ${devices[i].sync.label} differ")
            }
            if (legacyStart) {
                assertTrue(PreDeltaFiles.isStub(sim.cloud.bytesOf("keryx.db.gz")), "seed $seed: the stub is back in place")
                for (device in devices) {
                    assertTrue(device.db.feedsQueries.getById("lf1").executeAsOneOrNull() != null, "seed $seed: ${device.sync.label} lacks the pre-delta feed")
                    assertEquals(sim.cloud.files.getValue("keryx.db.gz").rev, device.sync.state(works.merc.keryx.app.core.SYNC_STATE_KS_LEGACY_STUB_REV))
                }
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

    /**
     * The pre-delta database an older build holds: a feed with two articles, with the first article
     * read at [readAt], plus [extraFeed] when given.
     */
    private fun legacyDatabase(sim: Sim, readAt: Long?, extraFeed: String? = null): ByteArray = PreDeltaFiles.database(
        *listOfNotNull(
            PreDeltaFiles.feed("lf1"),
            PreDeltaFiles.article("la1", "lf1", read = readAt != null, readAt = readAt),
            PreDeltaFiles.article("la2", "lf1"),
            extraFeed?.let { PreDeltaFiles.feed(it, updatedAt = sim.now) },
        ).toTypedArray(),
    )

    /**
     * The stamped field groups the migration of [legacyDatabase] (with `readAt = null`) must produce,
     * from [PreDeltaFiles.article]'s defaults: each article's body, stamped by the `body_updated_at`
     * that `2.sqm` initializes from `updated_at` (1), with no summary. Every other group of those rows
     * carries no stamp, and `lf1` itself is checked directly.
     */
    private fun legacyOracle(): List<Group> = listOf("la1", "la2").map { id ->
        Group("article $id body", 1L, "<p>legacy body $id</p>|null")
    }

    /** One sync of [device], possibly interrupted, failing transiently, or interleaved with another device's. */
    private suspend fun syncWithChaos(sim: Sim, device: Device, devices: List<Device>, where: String) {
        val r = sim.random
        val injected = CloudStorageException("injected")
        val injectedFailure = when (r.nextInt(20)) {
            0 -> injected.also { sim.cloud.queueDownload(Result.Err(it)) }
            1 -> injected.also { sim.cloud.queueCreate(Result.Err(it)) }
            2 -> injected.also { sim.cloud.queueList(Result.Err(it)) }
            else -> null
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

    /** Only the very failure this round injected (or one caused by it) may end a sync. */
    private fun checkTolerated(e: Exception, where: String, injected: Exception?) {
        if (injected == null || generateSequence<Throwable>(e) { it.cause }.none { it === injected }) {
            fail("$where: unexpected failure $e")
        }
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
        // Half the tag/folder creations and renames pick from the small shared pool, so devices
        // collide on names: same-named creations (the same name-derived id), renames crossing
        // creations, and same-named rows that cannot fold into one. Every device must still converge.
        fun sharedName(): String {
            sim.sharedNameOps++
            return sim.sharedNames[sim.sharedRandom.nextInt(sim.sharedNames.size)]
        }
        fun sharedOrUnique(kind: String): String = if (sim.sharedRandom.nextBoolean()) sharedName() else name(kind)
        when (r.nextInt(100)) {
            in 0..11 -> device.feeds.subscribeFeed(sim.urls[r.nextInt(sim.urls.size)])
            in 12..15 -> aliveFeeds.randomOrNull(r)?.let { device.feeds.unsubscribeFeed(it.id) }
            in 16..20 -> aliveFeeds.randomOrNull(r)?.let { device.feeds.renameFeed(it.id, if (r.nextBoolean()) name("title") else null) }
            in 21..24 -> device.folders.createFolder(sharedOrUnique("folder"))
            in 25..27 -> aliveFolders.randomOrNull(r)?.let { device.folders.updateFolder(it.id, sharedOrUnique("folder")) }
            in 28..29 -> aliveFolders.randomOrNull(r)?.let { device.folders.deleteFolder(it.id) }
            in 30..34 -> aliveFeeds.randomOrNull(r)?.let { feed -> device.feeds.moveFeed(feed.id, aliveFolders.randomOrNull(r)?.id) }
            in 35..37 -> device.tags.createTag(sharedOrUnique("tag"), if (r.nextBoolean()) "#ff0000" else null)
            in 38..39 -> aliveTags.randomOrNull(r)?.let { device.tags.updateTag(it.id, sharedOrUnique("tag"), "#00ff00") }
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

    /**
     * Folds the field groups a local write just changed into the oracle (the newest stamp wins). Only
     * local writes feed it, so a value a merge brought in is checked against it rather than trusted.
     */
    private fun record(sim: Sim, changed: List<Group>) {
        for ((key, stamp, value) in changed) {
            if (stamp == null) continue
            val known = sim.oracle[key]
            if (known == null || stamp > known.first) sim.oracle[key] = stamp to value
        }
    }

    private fun checkOracle(sim: Sim, device: Device, seed: Int) {
        val current = groups(device).associate { (key, stamp, value) -> key to (stamp to value) }
        // A feed's folder group is its raw assignment (ks_feed_folder_raw), compared exactly: the newest
        // write survives whatever happened to the folder since.
        for ((key, expected) in sim.oracle) {
            val actual = current[key]
            val got = actual?.let { (it.first ?: -1L) to it.second }
            assertEquals(expected, got, "seed $seed: ${device.sync.label}: $key lost its newest write")
        }
        // feeds.folder_id is the cache resolved from it: the raw folder while that folder is alive, else
        // none — for every feed, each of which has exactly one raw row.
        val driver = device.sync.driver
        val mismatches = driver.executeQuery(
            null,
            "SELECT COUNT(*) FROM feeds f LEFT JOIN ks_feed_folder_resolved v ON v.feed_id = f.id " +
                "WHERE v.feed_id IS NULL OR f.folder_id IS NOT v.folder_id OR f.folder_updated_at IS NOT v.folder_updated_at",
            { c -> c.next(); QueryResult.Value(c.getLong(0)!!) },
            0,
        ).value
        assertEquals(0L, mismatches, "seed $seed: ${device.sync.label}: a feed's folder disagrees with its raw assignment")
    }

    private data class Group(val key: String, val stamp: Long?, val value: String)

    /** Every last-write-wins field group of [device]'s synced rows, by stable identity. */
    private fun groups(device: Device): List<Group> = buildList {
        val driver = device.sync.driver
        fun rows(sql: String): List<List<String?>> = driver.executeQuery(null, sql, { c ->
            QueryResult.Value(buildList { while (c.next().value) add((0 until COLUMNS).map { i -> runCatching { c.getString(i) }.getOrNull() }) })
        }, 0).value
        for (
            r in rows(
                "SELECT f.id, f.deleted_updated_at, f.deleted_at IS NULL, f.custom_title_updated_at, f.custom_title, " +
                    "r.folder_updated_at, r.folder_id FROM feeds f JOIN ks_feed_folder_raw r ON r.feed_id = f.id",
            )
        ) {
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
        for (
            r in rows(
                "SELECT id, read_at, is_read, MAX(COALESCE(starred_at, 0), COALESCE(deleted_updated_at, 0)), " +
                    "is_starred || '/' || (deleted_at IS NULL), body_updated_at, content, summary FROM articles",
            )
        ) {
            add(Group("article ${r[0]} read", r[1]?.toLong(), r[2]!!))
            add(Group("article ${r[0]} retention", r[3]?.toLong()?.takeIf { it > 0 }, r[4]!!))
            add(Group("article ${r[0]} body", r[5]?.toLong(), "${r[6]}|${r[7]}"))
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
            // The stored folder assignment, raw and resolved, compared exactly (not just as the folder name a
            // user sees), so a device storing or sending a different value than another is caught.
            "feed_folders" to rows(
                "SELECT f.id, f.folder_id, f.folder_updated_at, r.folder_id, r.folder_updated_at " +
                    "FROM feeds f JOIN ks_feed_folder_raw r ON r.feed_id = f.id",
            ),
            "folders" to rows("SELECT id, name, sort_order, deleted_at IS NULL FROM folders"),
            "tags" to rows("SELECT id, name, color, sort_order, deleted_at IS NULL FROM tags"),
            "feed_tags" to rows(
                "SELECT ft.feed_id, ft.tag_id FROM feed_tags ft JOIN tags t ON t.id = ft.tag_id " +
                    "WHERE ft.deleted_at IS NULL AND t.deleted_at IS NULL",
            ),
            "articles" to rows("SELECT id, feed_id, guid, title, content, summary, is_read, is_starred, deleted_at IS NULL FROM articles"),
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
        const val COLUMNS = 9

        /** RFC 822 weekday names, starting from 2024-01-01 (a Monday). */
        val WEEKDAYS = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}
