package works.merc.keryx.app.domain

import app.cash.sqldelight.db.QueryResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.CLOUD_DB_GZ_PATH
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_POINTER_PATH
import works.merc.keryx.app.core.SYNC_STATE_KS_LEGACY_STUB_REV
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.core.cloudBackupPath
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.platform.DatabaseMerger
import java.io.File
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Thrown by a test hook to stand in for the process dying at that point. */
private class LegacyCrash : RuntimeException("simulated crash")

/**
 * "Migration from the Pre-Delta Format" and the legacy watch (`docs/sync-architecture.md`) end to end
 * against [FakeCloudStorage]: a cloud that still holds only `keryx.db.gz`, devices racing on the
 * pointer and the stub, an older build writing in between, a crash after every step, and the stub an
 * older build must refuse.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LegacyMigrationTest {

    private val devices = mutableListOf<SyncDevice>()
    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private val ids = idSource(Random(7))

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

    private fun SyncDevice.rows(sql: String): List<List<String?>> = driver.executeQuery(null, sql, { c ->
        QueryResult.Value(buildList { while (c.next().value) add((0 until 3).map { i -> runCatching { c.getString(i) }.getOrNull() }) })
    }, 0).value

    private fun KeryxDatabase.hasFeed(id: String) = feedsQueries.getById(id).executeAsOneOrNull()?.let { it.deleted_at == null } == true

    private fun generationOf(cloud: FakeCloudStorage): String =
        (SyncPointer.parse(cloud.bytesOf(SYNC_POINTER_PATH)!!.decodeToString()) as Result.Ok).value

    private fun transportFeeds(cloud: FakeCloudStorage, name: TransportFileName): Set<String> =
        SyncFiles.query(cloud.bytesOf(name.fileName)!!, "SELECT id FROM feeds").map { it[0]!! }.toSet()

    /** A pre-delta cloud: one feed with an article, a folder, a tag on the feed, a global setting. */
    private fun legacyCloud(): Pair<FakeCloudStorage, ByteArray> {
        val cloud = FakeCloudStorage()
        val bytes = PreDeltaFiles.database(
            "INSERT INTO folders (id, name, sort_order, updated_at, created_at) VALUES ('lfolder', 'Legacy folder', 0, 1, 1)",
            PreDeltaFiles.feed("lf1"),
            "UPDATE feeds SET folder_id = 'lfolder', folder_updated_at = 2 WHERE id = 'lf1'",
            PreDeltaFiles.article("la1", "lf1", body = "ancient wisdom", read = true, readAt = 5),
            "INSERT INTO tags (id, name, sort_order, updated_at, created_at) VALUES ('ltag', 'Legacy tag', 0, 1, 1)",
            "INSERT INTO feed_tags (feed_id, tag_id, updated_at) VALUES ('lf1', 'ltag', 1)",
            "INSERT INTO global_settings (key, value, updated_at) VALUES ('read_timeout_seconds', '45', 3)",
        )
        cloud.put(CLOUD_DB_GZ_PATH, bytes)
        return cloud to bytes
    }

    private fun assertMigrated(cloud: FakeCloudStorage, original: ByteArray, vararg devicesSeeingTheStub: SyncDevice) {
        assertNotNull(cloud.bytesOf(SYNC_POINTER_PATH), "the pointer exists")
        assertTrue(PreDeltaFiles.isStub(cloud.bytesOf(CLOUD_DB_GZ_PATH)), "keryx.db.gz is the stub")
        val backups = PreDeltaFiles.backups(cloud)
        assertTrue(backups.isNotEmpty(), "the pre-delta file is archived")
        backups.forEach { assertContentEquals(original, cloud.bytesOf(it), "$it holds the downloaded file unchanged") }
        for (d in devicesSeeingTheStub) {
            assertEquals(cloud.files.getValue("keryx.db.gz").rev, d.state(SYNC_STATE_KS_LEGACY_STUB_REV), "${d.label} recorded the stub")
        }
    }

    // ---- The migration ----

    @Test
    fun aDeviceMigratesAPreDeltaCloud() = runTest {
        val (cloud, original) = legacyCloud()
        val a = device("a", cloud)
        a.db.insertFeed("fa", now = 1)

        a.syncOk()

        // Everything in keryx.db.gz is merged, search text included.
        assertTrue(a.db.hasFeed("lf1"))
        assertEquals(listOf(listOf("lfolder", "1", "ancient wisdom")), a.rows("SELECT f.folder_id, ar.is_read, ar.search_text FROM feeds f JOIN articles ar ON ar.feed_id = f.id WHERE f.id = 'lf1'"))
        assertEquals(listOf(listOf("ltag", null, null)), a.rows("SELECT tag_id FROM feed_tags WHERE feed_id = 'lf1' AND deleted_at IS NULL"))
        assertEquals(listOf(listOf("45", null, null)), a.rows("SELECT value FROM global_settings WHERE key = 'read_timeout_seconds'"))
        assertTrue(a.rows("SELECT rowid FROM ks_search_pending").isEmpty())
        // The first base carries both the pre-delta rows and the device's own, and the pointer names it.
        val base = cloud.transportNames().single { it.kind == TransportFileKind.BASE }
        assertEquals(setOf("lf1", "fa"), transportFeeds(cloud, base))
        assertEquals(base.generation, generationOf(cloud))
        // Order: base, pointer, archive, stub.
        assertEquals(
            listOf(base.fileName, "keryx-sync.json", cloudBackupPath(now).removePrefix("/"), "keryx.db.gz"),
            cloud.written,
        )
        assertMigrated(cloud, original, a)

        // The next sync is quiet: the pointer and keryx.db.gz are checked, nothing is downloaded.
        val (metadata, downloads, writes) = Triple(cloud.metadataCount, cloud.downloadCount, cloud.written.size)
        a.syncOk()
        assertEquals(2, cloud.metadataCount - metadata)
        assertEquals(downloads, cloud.downloadCount)
        assertEquals(writes, cloud.written.size)

        // A second device joins the delta generation, records the stub, and sees everything.
        val b = device("b", cloud)
        b.syncOk()
        assertTrue(b.db.hasFeed("lf1") && b.db.hasFeed("fa"))
        assertEquals(generationOf(cloud), b.state(works.merc.keryx.app.core.SYNC_STATE_KS_GENERATION))
        assertMigrated(cloud, original, a, b)
        assertEquals(1, PreDeltaFiles.backups(cloud).size, "only the migrating device archives")
    }

    @Test
    fun aPointerCreatedMeanwhileTurnsTheMigrationIntoAnOrdinaryJoin() = runTest {
        val (cloud, original) = legacyCloud()
        val a = device("a", cloud)
        a.db.insertFeed("fa", now = 1)
        val b = device("b", cloud)
        b.db.insertFeed("fb", now = 1)
        var nested = false
        a.repo.testHook = { point, _ ->
            if (point == SyncHookPoint.AFTER_LEGACY_MERGE && !nested) {
                nested = true
                b.syncOk() // b migrates completely while a is between its merge and its pointer
            }
        }

        a.syncOk()
        a.repo.testHook = null

        val generation = generationOf(cloud)
        assertEquals(generation, b.state(works.merc.keryx.app.core.SYNC_STATE_KS_GENERATION))
        assertEquals(generation, a.state(works.merc.keryx.app.core.SYNC_STATE_KS_GENERATION), "a rejoined b's generation")
        assertMigrated(cloud, original, a, b)
        assertEquals(1, PreDeltaFiles.backups(cloud).size)
        b.syncOk()
        assertTrue(b.db.hasFeed("fa") && b.db.hasFeed("lf1"))
        assertTrue(a.db.hasFeed("fb"))
    }

    @Test
    fun aStubPlacedByAnotherDeviceFirstIsRecorded() = runTest {
        val (cloud, original) = legacyCloud()
        val a = device("a", cloud)
        var planted = false
        a.repo.testHook = { point, _ ->
            if (point == SyncHookPoint.BEFORE_STUB_UPLOAD && !planted) {
                planted = true
                cloud.put(CLOUD_DB_GZ_PATH, PreDeltaFiles.stub(), rev = "other-device-stub")
            }
        }

        a.syncOk()

        assertEquals("other-device-stub", a.state(SYNC_STATE_KS_LEGACY_STUB_REV))
        assertEquals("other-device-stub", cloud.files.getValue("keryx.db.gz").rev, "the other device's stub is left in place")
        assertMigrated(cloud, original, a)
    }

    @Test
    fun anOlderBuildWritingBeforeTheStubIsMergedWithCaptureAndReachesOtherDevices() = runTest {
        val (cloud, _) = legacyCloud()
        val a = device("a", cloud)
        var written = false
        a.repo.testHook = { point, _ ->
            if (point == SyncHookPoint.BEFORE_STUB_UPLOAD && !written) {
                written = true
                // An older build syncs: its full upload replaces the file a just merged.
                cloud.put(
                    CLOUD_DB_GZ_PATH,
                    PreDeltaFiles.database(PreDeltaFiles.feed("lf1"), PreDeltaFiles.feed("old2"), PreDeltaFiles.article("oa", "old2")),
                )
            }
        }

        a.syncOk()
        a.repo.testHook = null

        assertTrue(a.db.hasFeed("old2"))
        assertTrue(PreDeltaFiles.isStub(cloud.bytesOf(CLOUD_DB_GZ_PATH)))
        assertEquals(cloud.files.getValue("keryx.db.gz").rev, a.state(SYNC_STATE_KS_LEGACY_STUB_REV))
        assertEquals(1, PreDeltaFiles.backups(cloud).size, "the older build's later write is merged, not archived")
        // Merged with capture on: its rows went out in a's next delta, not only into a's database.
        val delta = cloud.transportNames().single { it.kind == TransportFileKind.DELTA }
        assertTrue("old2" in transportFeeds(cloud, delta))
        assertTrue(a.outbox().isEmpty())

        val b = device("b", cloud)
        b.syncOk()
        assertTrue(b.db.hasFeed("old2"))
        assertEquals(1L, b.rows("SELECT COUNT(*) FROM articles WHERE id = 'oa'").single()[0]!!.toLong())
    }

    /**
     * Makes this device's stub preparation fail: the compressed stub's temporary path becomes a link
     * into a directory that does not exist, so writing it fails the way a full disk would, while
     * deleting it afterwards still succeeds. Returns the link, for the test to remove.
     */
    private fun SyncDevice.breakStubPreparation(): java.nio.file.Path = java.nio.file.Files.createSymbolicLink(
        File(tempDir, "ks-legacy-stub.db.gz").toPath(),
        File(tempDir, "missing-dir/stub.db.gz").toPath(),
    )

    @Test
    fun theStubIsRetriedAtMostSyncMaxRetryTimesThenTheSyncFailsTransiently() = runTest {
        val (cloud, _) = legacyCloud()
        val a = device("a", cloud)
        a.repo.testHook = { point, _ ->
            if (point == SyncHookPoint.BEFORE_STUB_UPLOAD) {
                cloud.put(CLOUD_DB_GZ_PATH, PreDeltaFiles.database(PreDeltaFiles.feed("lf1"), PreDeltaFiles.feed("again")))
            }
        }

        val result = a.repo.sync()
        a.repo.testHook = null

        assertIs<Result.Err>(result)
        assertIs<works.merc.keryx.app.core.CloudStorageException>(result.exception)
        assertNull(a.state(SYNC_STATE_KS_LEGACY_STUB_REV))
        // The next sync finishes the job.
        a.syncOk()
        assertTrue(PreDeltaFiles.isStub(cloud.bytesOf(CLOUD_DB_GZ_PATH)))
        assertTrue(a.db.hasFeed("again"))
    }

    @Test
    fun aStubThatCannotBePreparedFailsTheMigrationTransientlyAndTheNextSyncFinishes() = runTest {
        val (cloud, original) = legacyCloud()
        val a = device("a", cloud)
        val link = a.breakStubPreparation()

        val result = a.repo.sync()

        assertIs<Result.Err>(result)
        assertIs<works.merc.keryx.app.core.CloudStorageException>(result.exception)
        assertContentEquals(original, cloud.bytesOf(CLOUD_DB_GZ_PATH), "the pre-delta file is left as it was")
        assertNull(a.state(SYNC_STATE_KS_LEGACY_STUB_REV))
        assertNotNull(a.repo.lastSyncError.value, "the failure is reported like any other sync failure")
        java.nio.file.Files.deleteIfExists(link)

        a.syncOk()

        assertMigrated(cloud, original, a)
    }

    @Test
    fun aCrashAfterAnyMigrationStepConvergesOnTheNextSync() = runTest {
        val points = listOf(
            SyncHookPoint.AFTER_LEGACY_MERGE,
            SyncHookPoint.BEFORE_SEND_UPLOAD,
            SyncHookPoint.AFTER_SEND_UPLOAD,
            SyncHookPoint.AFTER_SEND_COMMIT,
            SyncHookPoint.AFTER_POINTER_CREATE,
            SyncHookPoint.BEFORE_LEGACY_BACKUP,
            SyncHookPoint.BEFORE_STUB_UPLOAD,
            SyncHookPoint.AFTER_STUB_UPLOAD,
        )
        for (point in points) {
            val (cloud, original) = legacyCloud()
            val a = device("a-$point", cloud)
            a.db.insertFeed("fa", now = 1)
            var crashed = false
            a.repo.testHook = { p, _ ->
                if (p == point && !crashed) {
                    crashed = true
                    throw LegacyCrash()
                }
            }
            assertFailsWith<LegacyCrash> { a.repo.sync() }
            assertTrue(crashed, "$point was reached")
            a.restart()

            a.syncOk()

            assertTrue(a.db.hasFeed("lf1"), "$point")
            assertMigrated(cloud, original, a)
            val writes = cloud.written.size
            a.syncOk()
            assertEquals(writes, cloud.written.size, "$point: the sync after recovering is quiet")
            val b = device("b-$point", cloud)
            b.syncOk()
            assertTrue(b.db.hasFeed("lf1") && b.db.hasFeed("fa"), "$point: a second device sees everything")
            assertEquals(1, cloud.transportNames().map { it.generation }.distinct().size, "$point: one generation")
        }
    }

    // ---- The legacy watch ----

    @Test
    fun theLegacyWatchAbsorbsAFullDatabaseWrittenOverTheStubAndPutsTheStubBack() = runTest {
        val (cloud, _) = legacyCloud()
        val a = device("a", cloud)
        a.syncOk()
        val b = device("b", cloud)
        b.syncOk()
        // Google Drive's non-atomic revision check: an older build's upload replaced the stub.
        cloud.put(CLOUD_DB_GZ_PATH, PreDeltaFiles.database(PreDeltaFiles.feed("late"), PreDeltaFiles.article("lt", "late")))

        a.syncOk()

        assertTrue(a.db.hasFeed("late"))
        assertTrue(PreDeltaFiles.isStub(cloud.bytesOf(CLOUD_DB_GZ_PATH)))
        assertEquals(cloud.files.getValue("keryx.db.gz").rev, a.state(SYNC_STATE_KS_LEGACY_STUB_REV))
        assertEquals(1, PreDeltaFiles.backups(cloud).size)
        b.syncOk()
        assertTrue(b.db.hasFeed("late"), "the absorbed rows reach other devices as a delta")
        assertEquals(cloud.files.getValue("keryx.db.gz").rev, b.state(SYNC_STATE_KS_LEGACY_STUB_REV))
    }

    @Test
    fun aStubWithoutAPointerIsRecordedAndTheDeviceStartsAsTheFirstOne() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_GZ_PATH, PreDeltaFiles.stub(), rev = "stub-1")
        val a = device("a", cloud)

        a.syncOk()

        assertEquals("stub-1", a.state(SYNC_STATE_KS_LEGACY_STUB_REV))
        assertNotNull(cloud.bytesOf(SYNC_POINTER_PATH))
        assertTrue(PreDeltaFiles.backups(cloud).isEmpty())
        assertEquals("stub-1", cloud.files.getValue("keryx.db.gz").rev)
    }

    // ---- Unusable pre-delta files ----

    private suspend fun TestScope.assertRejectedBeforeAnyChange(bytes: ByteArray, expected: kotlin.reflect.KClass<out Exception>) {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_GZ_PATH, bytes)
        val a = device("a-${expected.simpleName}-${devices.size}", cloud)
        a.db.insertFeed("fa", now = 1)
        val feedsBefore = a.rows("SELECT id FROM feeds")

        val result = a.repo.sync()

        assertIs<Result.Err>(result)
        assertTrue(expected.isInstance(result.exception), "${result.exception}")
        assertEquals(feedsBefore, a.rows("SELECT id FROM feeds"), "nothing merged")
        assertEquals(setOf("keryx.db.gz"), cloud.names(), "nothing written")
        assertEquals(0L, a.capture().enabled)
        assertNull(a.state(SYNC_STATE_KS_LEGACY_STUB_REV))
    }

    @Test
    fun aCorruptPreDeltaFileIsIncompatibleAndNothingIsMerged() = runTest {
        assertRejectedBeforeAnyChange(byteArrayOf(1, 2, 3), CloudDataIncompatibleException::class)
        // A well-formed database whose stamp holds text (check 7) is just as unusable.
        assertRejectedBeforeAnyChange(
            PreDeltaFiles.database(PreDeltaFiles.feed("lf1"), "UPDATE feeds SET updated_at = 'soon'"),
            CloudDataIncompatibleException::class,
        )
        // A dangling reference (check 9): an article whose feed is not in the file.
        assertRejectedBeforeAnyChange(PreDeltaFiles.database(PreDeltaFiles.article("orphan", "nofeed")), CloudDataIncompatibleException::class)
    }

    @Test
    fun aCorruptPreDeltaFileIsDownloadedTwiceBeforeItIsJudgedCorrupt() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_GZ_PATH, byteArrayOf(9, 9, 9))
        val a = device("a", cloud)
        assertIs<Result.Err>(a.repo.sync())
        assertEquals(2, cloud.downloadCount)
    }

    @Test
    fun aNewerPreDeltaDatabaseAsksForAnUpdate() = runTest {
        assertRejectedBeforeAnyChange(PreDeltaFiles.database(PreDeltaFiles.feed("lf1"), userVersion = 7), SchemaVersionException::class)
        // user_version 1000 makes a stub only when the file is empty.
        assertRejectedBeforeAnyChange(PreDeltaFiles.database(PreDeltaFiles.feed("lf1"), userVersion = 1000), SchemaVersionException::class)
    }

    // ---- Reset ----

    private suspend fun SyncDevice.resetOk() {
        val result = repo.resetCloudData()
        assertIs<Result.Ok<Unit>>(result, "$label: $result")
    }

    /** A migrated cloud whose `keryx.db.gz` was then overwritten by [unusable]; [a] and [b] synced before that. */
    private suspend fun TestScope.migratedThenOverwritten(unusable: ByteArray): Triple<FakeCloudStorage, SyncDevice, SyncDevice> {
        val (cloud, _) = legacyCloud()
        val a = device("a", cloud)
        a.db.insertFeed("fa", now = 1)
        a.syncOk()
        val b = device("b", cloud)
        b.syncOk()
        cloud.put(CLOUD_DB_GZ_PATH, unusable, rev = "unusable")
        now += 60_000 // the reset's archive gets a name of its own, not the migration's
        return Triple(cloud, a, b)
    }

    private fun assertArchivedAndStubbed(cloud: FakeCloudStorage, unusable: ByteArray, vararg devices: SyncDevice) {
        assertTrue(PreDeltaFiles.isStub(cloud.bytesOf(CLOUD_DB_GZ_PATH)), "keryx.db.gz is the stub again")
        assertTrue(
            PreDeltaFiles.backups(cloud).any { cloud.bytesOf(it)!!.contentEquals(unusable) },
            "a .bak holds the unusable file's raw bytes",
        )
        for (d in devices) {
            assertEquals(cloud.files.getValue("keryx.db.gz").rev, d.state(SYNC_STATE_KS_LEGACY_STUB_REV), "${d.label} recorded the stub")
        }
    }

    @Test
    fun aResetUnblocksSyncingFromACorruptPreDeltaFile() = runTest {
        val corrupt = byteArrayOf(1, 2, 3)
        val (cloud, a, b) = migratedThenOverwritten(corrupt)
        val blocked = a.repo.sync()
        assertIs<Result.Err>(blocked)
        assertIs<CloudDataIncompatibleException>(blocked.exception)

        a.resetOk()

        assertArchivedAndStubbed(cloud, corrupt, a)
        assertEquals(2, PreDeltaFiles.backups(cloud).size, "the migration's archive and the reset's")
        assertEquals(cloudBackupPath(now).removePrefix("/"), PreDeltaFiles.backups(cloud).max())
        a.syncOk()
        b.syncOk()
        assertArchivedAndStubbed(cloud, corrupt, a, b)
        assertTrue(b.db.hasFeed("fa") && b.db.hasFeed("lf1"))
    }

    @Test
    fun aResetArchivesAnUnusableFullDatabaseWithoutMergingIt() = runTest {
        // A dangling reference fails validation (check 9), so the legacy watch cannot absorb it.
        val unusable = PreDeltaFiles.database(PreDeltaFiles.feed("never"), PreDeltaFiles.article("orphan", "nofeed"))
        val (cloud, a, b) = migratedThenOverwritten(unusable)
        assertIs<Result.Err>(a.repo.sync())

        a.resetOk()

        assertArchivedAndStubbed(cloud, unusable, a)
        assertFalse(a.db.hasFeed("never"), "a reset merges nothing")
        a.syncOk()
        b.syncOk()
        assertFalse(b.db.hasFeed("never"))
    }

    @Test
    fun aResetLeavesAValidStubInPlaceAndRecordsIt() = runTest {
        val (cloud, original) = legacyCloud()
        val a = device("a", cloud)
        a.syncOk()
        val stubRev = cloud.files.getValue("keryx.db.gz").rev
        now += 60_000
        // A device that has never seen the stub resets: it records it and writes nothing to it.
        val b = device("b", cloud)
        val stubWritesBefore = cloud.written.count { it == "keryx.db.gz" }

        b.resetOk()
        a.resetOk()

        assertEquals(stubRev, cloud.files.getValue("keryx.db.gz").rev, "the stub is left in place")
        assertEquals(stubWritesBefore, cloud.written.count { it == "keryx.db.gz" })
        assertEquals(stubRev, a.state(SYNC_STATE_KS_LEGACY_STUB_REV))
        assertEquals(stubRev, b.state(SYNC_STATE_KS_LEGACY_STUB_REV))
        assertMigrated(cloud, original, a, b)
        assertEquals(1, PreDeltaFiles.backups(cloud).size, "nothing more is archived")
    }

    @Test
    fun aResetWithNoPreDeltaFileWritesNone() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.db.insertFeed("fa", now = 1)
        a.syncOk()

        a.resetOk()

        assertNull(cloud.bytesOf(CLOUD_DB_GZ_PATH))
        assertTrue(PreDeltaFiles.backups(cloud).isEmpty())
        assertNull(a.state(SYNC_STATE_KS_LEGACY_STUB_REV))
        a.syncOk()
    }

    @Test
    fun aPreDeltaFileChangingBeforeTheResetsStubIsArchivedAgain() = runTest {
        val corrupt = byteArrayOf(1, 2, 3)
        val rewritten = byteArrayOf(4, 5, 6, 7)
        val (cloud, a, _) = migratedThenOverwritten(corrupt)
        var planted = false
        a.repo.testHook = { point, _ ->
            if (point == SyncHookPoint.BEFORE_STUB_UPLOAD && !planted) {
                planted = true
                cloud.put(CLOUD_DB_GZ_PATH, rewritten, rev = "rewritten")
                now += 1_000
            }
        }

        a.resetOk()
        a.repo.testHook = null

        assertTrue(planted)
        assertArchivedAndStubbed(cloud, corrupt, a)
        assertArchivedAndStubbed(cloud, rewritten, a)
        a.syncOk()
    }

    @Test
    fun aPreDeltaFileChangingWithinTheSameSecondIsStillArchivedUnderTheNextFreeName() = runTest {
        val corrupt = byteArrayOf(1, 2, 3)
        val rewritten = byteArrayOf(4, 5, 6, 7)
        val (cloud, a, _) = migratedThenOverwritten(corrupt)
        var planted = false
        a.repo.testHook = { point, _ ->
            if (point == SyncHookPoint.BEFORE_STUB_UPLOAD && !planted) {
                planted = true
                // Same wall-clock second: the second archive's name collides with the first's.
                cloud.put(CLOUD_DB_GZ_PATH, rewritten, rev = "rewritten")
            }
        }

        a.resetOk()
        a.repo.testHook = null

        assertTrue(planted)
        assertArchivedAndStubbed(cloud, corrupt, a)
        assertArchivedAndStubbed(cloud, rewritten, a)
    }

    @Test
    fun aPreDeltaFileThatKeepsChangingFailsTheResetTransientlyAndTheNextResetFinishes() = runTest {
        val corrupt = byteArrayOf(1, 2, 3)
        val (cloud, a, _) = migratedThenOverwritten(corrupt)
        val stubRevBefore = a.state(SYNC_STATE_KS_LEGACY_STUB_REV)
        var round = 0
        a.repo.testHook = { point, _ ->
            if (point == SyncHookPoint.BEFORE_STUB_UPLOAD) {
                cloud.put(CLOUD_DB_GZ_PATH, byteArrayOf(9, round++.toByte()))
                now += 1_000
            }
        }

        val result = a.repo.resetCloudData()
        a.repo.testHook = null

        assertIs<Result.Err>(result)
        assertIs<works.merc.keryx.app.core.CloudStorageException>(result.exception)
        assertEquals(works.merc.keryx.app.core.SYNC_MAX_RETRY, round)
        assertContentEquals(byteArrayOf(9, (round - 1).toByte()), cloud.bytesOf(CLOUD_DB_GZ_PATH), "the last write is not replaced")
        assertEquals(stubRevBefore, a.state(SYNC_STATE_KS_LEGACY_STUB_REV))
        val last = cloud.bytesOf(CLOUD_DB_GZ_PATH)!!
        now += 60_000

        a.resetOk()

        assertArchivedAndStubbed(cloud, last, a)
        a.syncOk()
    }

    @Test
    fun aResetThatCannotDownloadThePreDeltaFileFailsWithoutReplacingIt() = runTest {
        val corrupt = byteArrayOf(1, 2, 3)
        val (cloud, a, _) = migratedThenOverwritten(corrupt)
        cloud.downloadHook = { name ->
            if (name == "keryx.db.gz") cloud.queueDownload(Result.Err(works.merc.keryx.app.core.CloudStorageException("HTTP 503")))
        }

        val result = a.repo.resetCloudData()

        assertIs<Result.Err>(result)
        assertIs<works.merc.keryx.app.core.CloudStorageException>(result.exception)
        assertContentEquals(corrupt, cloud.bytesOf(CLOUD_DB_GZ_PATH), "never replaced unarchived")
        assertEquals(1, PreDeltaFiles.backups(cloud).size)
        cloud.downloadHook = null

        a.resetOk()

        assertArchivedAndStubbed(cloud, corrupt, a)
    }

    @Test
    fun aResetThatCannotPrepareTheStubFailsTransientlyWithoutReplacingThePreDeltaFile() = runTest {
        val corrupt = byteArrayOf(1, 2, 3)
        val (cloud, a, _) = migratedThenOverwritten(corrupt)
        val link = a.breakStubPreparation()

        val result = a.repo.resetCloudData()

        assertIs<Result.Err>(result)
        assertIs<works.merc.keryx.app.core.CloudStorageException>(result.exception)
        assertContentEquals(corrupt, cloud.bytesOf(CLOUD_DB_GZ_PATH), "the pre-delta file is left as it was")
        assertNotNull(a.repo.lastSyncError.value, "the failure is reported like any other reset failure")
        java.nio.file.Files.deleteIfExists(link)

        a.resetOk()

        assertArchivedAndStubbed(cloud, corrupt, a)
    }

    @Test
    fun aCrashDuringTheResetsPreDeltaStepConvergesOnTheNextResetOrSync() = runTest {
        val points = listOf(SyncHookPoint.BEFORE_LEGACY_BACKUP, SyncHookPoint.BEFORE_STUB_UPLOAD, SyncHookPoint.AFTER_STUB_UPLOAD)
        for (point in points) {
            val corrupt = byteArrayOf(1, 2, 3)
            val (cloud, a, b) = migratedThenOverwritten(corrupt)
            var crashed = false
            a.repo.testHook = { p, _ ->
                if (p == point && !crashed) {
                    crashed = true
                    throw LegacyCrash()
                }
            }
            assertFailsWith<LegacyCrash> { a.repo.resetCloudData() }
            assertTrue(crashed, "$point was reached")
            a.restart()

            if (point == SyncHookPoint.AFTER_STUB_UPLOAD) {
                // The stub is in place: the legacy watch records it.
                a.syncOk()
            } else {
                assertIs<Result.Err>(a.repo.sync(), "$point: still blocked until the reset is repeated")
                now += 1_000
                a.resetOk()
                a.syncOk()
            }

            assertArchivedAndStubbed(cloud, corrupt, a)
            b.syncOk()
            assertArchivedAndStubbed(cloud, corrupt, a, b)
            devices.forEach { it.close() }
            devices.clear()
        }
    }

    // ---- An older build reading the stub ----

    @Test
    fun anOlderBuildsMergeRefusesTheStubBeforeTouchingItsDatabase() {
        val (file, driver, db) = fileDb()
        val stub = SyncFiles.gunzip(PreDeltaFiles.stub())
        try {
            db.insertFeed("mine", now = 1)
            val before = file.readBytes()
            // The pre-delta merge path, as an older build (local schema 2) runs it on what it downloads.
            for (oldVersion in listOf(2L, KeryxDatabase.Schema.version)) {
                val e = assertFailsWith<SchemaVersionException> {
                    DatabaseMerger.merge(file.absolutePath, stub.absolutePath, oldVersion, MergeSql.all)
                }
                assertEquals(1000L, e.cloudVersion)
            }
            assertContentEquals(before, file.readBytes(), "the local database is untouched")
            assertFalse(File("${file.absolutePath}-journal").exists())
        } finally {
            driver.close()
            stub.delete()
            file.delete()
        }
    }
}
