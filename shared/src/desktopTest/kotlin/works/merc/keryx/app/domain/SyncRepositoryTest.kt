package works.merc.keryx.app.domain

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.ArticleFilter
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.CLOUD_DB_GZ_PATH
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.DEFERRED_SYNC_DEBOUNCE_MS
import works.merc.keryx.app.core.ErrorKind
import works.merc.keryx.app.core.KS_DELTA_GRACE_MS
import works.merc.keryx.app.core.KS_RESUME_MAX_AGE_MS
import works.merc.keryx.app.core.LOCAL_WRITES_FLUSH_WAIT_MS
import works.merc.keryx.app.core.MILLIS_PER_DAY
import works.merc.keryx.app.core.NetworkCondition
import works.merc.keryx.app.core.NotificationText
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_DEBOUNCE_MS
import works.merc.keryx.app.core.SYNC_POINTER_PATH
import works.merc.keryx.app.core.SYNC_STATE_INDEX_PENDING
import works.merc.keryx.app.core.SYNC_STATE_KS_ATTEMPT
import works.merc.keryx.app.core.SYNC_STATE_KS_BASES
import works.merc.keryx.app.core.SYNC_STATE_KS_DEVICE_ID
import works.merc.keryx.app.core.SYNC_STATE_KS_GENERATION
import works.merc.keryx.app.core.SYNC_STATE_KS_RECEIVED_AT
import works.merc.keryx.app.core.SYNC_STATE_LAST_SYNCED_AT
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.data.cloud.CloudFileEntry
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.platform.ExternalAppLaunch
import works.merc.keryx.app.platform.FakeNetworkConditionMonitor
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Thrown by a test hook to stand in for the process dying at that point. */
private class SimulatedCrash : RuntimeException("simulated crash")

/**
 * The delta sync flow (`docs/sync-architecture.md` "Sync Flow" … "Joining, Reset, Disconnect") end to
 * end against [FakeCloudStorage], plus the scheduling, gates and user-facing state around it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncRepositoryTest {

    private lateinit var localFile: File
    private lateinit var localDriver: SqlDriver
    private lateinit var localDb: KeryxDatabase
    private lateinit var ftsManager: FtsManager
    private lateinit var tempDir: File
    private lateinit var notificationCenter: NotificationCenter
    private val devices = mutableListOf<SyncDevice>()

    /** The shared clock of every repository a test builds. */
    private var now = 1_700_000_000_000L
    private val clock = Clock { now }
    private val ids = idSource(Random(42))

    @BeforeTest
    fun setUp() {
        val (f, d, db) = fileDb()
        localFile = f
        localDriver = d
        localDb = db
        localDb.insertFeed("f1", now = 0)
        ftsManager = FtsManager(localDriver)
        runBlocking { ftsManager.ensureIndexed() }
        tempDir = Files.createTempDirectory("keryx-sync-test").toFile()
        notificationCenter = NotificationCenter()
    }

    @AfterTest
    fun tearDown() {
        devices.forEach { it.close() }
        localDriver.close()
        tempDir.deleteRecursively()
        localFile.delete()
    }

    private fun TestScope.newRepo(
        cloud: FakeCloudStorage,
        activityCenter: ActivityCenter = ActivityCenter(),
        automaticSyncAllowedFlow: () -> Flow<Boolean> = { flowOf(true) },
        pendingLocalWrites: PendingLocalWrites = PendingLocalWrites(),
        cloudProvider: () -> FakeCloudStorage? = { cloud },
    ): SyncRepository =
        SyncRepository(
            driver = localDriver,
            db = localDb,
            ftsManager = ftsManager,
            cloudProvider = cloudProvider,
            clock = clock,
            scope = backgroundScope,
            activityCenter = activityCenter,
            notificationCenter = notificationCenter,
            localDbPath = localFile.absolutePath,
            tempDir = tempDir.absolutePath,
            dataDir = tempDir.absolutePath,
            automaticSyncAllowedFlow = automaticSyncAllowedFlow,
            pendingLocalWrites = pendingLocalWrites,
            newId = ids,
        )

    private fun TestScope.device(
        label: String,
        cloud: FakeCloudStorage,
        compactionAllowed: () -> Boolean = { true },
        existingDbFile: File? = null,
    ): SyncDevice =
        SyncDevice(label, cloud, clock, backgroundScope, ids, compactionAllowed = compactionAllowed, existingDbFile = existingDbFile)
            .also(devices::add)

    private fun networkGate(monitor: FakeNetworkConditionMonitor, syncOnMetered: MutableStateFlow<Boolean>): () -> Flow<Boolean> = {
        NetworkUsagePolicy.automaticSyncAllowedFlow(monitor.condition, syncOnMetered)
    }

    private fun feedIds(db: KeryxDatabase): Set<String> =
        db.feedsQueries.getAllIncludingDeleted().executeAsList().filter { it.deleted_at == null }.map { it.id }.toSet()

    private fun KeryxDatabase.hasFeed(id: String) = feedsQueries.getById(id).executeAsOneOrNull()?.let { it.deleted_at == null } == true

    private fun generationOf(cloud: FakeCloudStorage): String =
        (SyncPointer.parse(cloud.bytesOf(SYNC_POINTER_PATH)!!.decodeToString()) as Result.Ok).value

    private fun deltasOf(cloud: FakeCloudStorage, device: String? = null) =
        cloud.transportNames().filter { it.kind == TransportFileKind.DELTA && (device == null || it.device == device) }

    private fun basesOf(cloud: FakeCloudStorage) = cloud.transportNames().filter { it.kind == TransportFileKind.BASE }

    private fun SyncDevice.deviceId() = checkNotNull(state(SYNC_STATE_KS_DEVICE_ID))

    private suspend fun SyncDevice.syncOk() {
        val result = repo.sync()
        assertIs<Result.Ok<Unit>>(result, "$label: $result")
    }

    private fun malformedPointer(cloud: FakeCloudStorage) = cloud.put(SYNC_POINTER_PATH, "not json".encodeToByteArray())

    // ---- First device, steady state, ordinary deltas ----

    @Test
    fun theFirstDeviceUploadsItsBaseBeforeCreatingThePointer() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.db.insertFeed("fa", now = 1)

        a.syncOk()

        val base = basesOf(cloud).single()
        assertEquals(a.deviceId(), base.device)
        assertEquals(1L, base.number)
        assertEquals(listOf(base.fileName, "keryx-sync.json"), cloud.written, "the base must exist before the pointer names it")
        assertEquals(base.generation, generationOf(cloud))
        assertEquals(listOf(listOf(a.deviceId(), "1")), SyncFiles.query(cloud.bytesOf(base.fileName)!!, "SELECT device, seq FROM ks_vector"))
        assertEquals(listOf(listOf("fa")), SyncFiles.query(cloud.bytesOf(base.fileName)!!, "SELECT id FROM feeds"))
        assertEquals(1L, a.capture().enabled)
        assertEquals(0L, a.capture().full_pending)
        assertTrue(a.outbox().isEmpty())
    }

    @Test
    fun aSyncWithNothingToDoTransfersNoPayload() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val metadataBefore = cloud.metadataCount
        val (down, up) = cloud.downloadCount to cloud.createCount + cloud.uploadCount

        a.syncOk()

        assertEquals(1, cloud.metadataCount - metadataBefore, "only the pointer's metadata")
        assertEquals(down, cloud.downloadCount)
        assertEquals(up, cloud.createCount + cloud.uploadCount)
    }

    @Test
    fun aLocalChangeIsSentAsTheNextDeltaAndClearsTheOutbox() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        a.db.insertFeed("f2", now = 5)
        assertTrue(a.outbox().isNotEmpty())

        a.syncOk()

        val delta = deltasOf(cloud).single()
        assertEquals(2L, delta.number)
        assertTrue(a.outbox().isEmpty())
        assertEquals(listOf(listOf("f2")), SyncFiles.query(cloud.bytesOf(delta.fileName)!!, "SELECT id FROM feeds"))
        assertEquals(2L, a.db.sync_bookkeepingQueries.cursorAll().executeAsList().single { it.device == a.deviceId() }.seq)
    }

    @Test
    fun aWriteMadeWhileTheDeltaIsBeingSentGoesIntoTheNextOne() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        a.db.insertFeed("f2", now = 5)
        a.repo.testHook = { point, _ -> if (point == SyncHookPoint.BEFORE_SEND_UPLOAD) a.db.insertFeed("f3", now = 6) }

        a.syncOk()
        a.repo.testHook = null
        assertEquals(1, a.outbox().size, "the write after the delta was built stays in the outbox")

        a.syncOk()
        val latest = deltasOf(cloud).maxBy { it.number }
        assertEquals(listOf(listOf("f3")), SyncFiles.query(cloud.bytesOf(latest.fileName)!!, "SELECT id FROM feeds"))
    }

    // ---- Joining ----

    @Test
    fun aJoiningDeviceMergesTheCloudAndSendsOnlyWhatTheCloudLacks() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.db.insertFeed("shared", now = 1)
        a.db.insertFeed("fa", now = 1)
        a.syncOk()
        val b = device("b", cloud)
        b.db.insertFeed("shared", now = 1)
        b.db.insertFeed("fb", now = 1)

        b.syncOk()

        assertTrue(b.db.hasFeed("fa"))
        val full = deltasOf(cloud, b.deviceId()).single()
        assertEquals(1L, full.number)
        assertEquals(listOf(listOf("fb")), SyncFiles.query(cloud.bytesOf(full.fileName)!!, "SELECT id FROM feeds"), "rows identical to the cloud's are left out")

        a.syncOk()
        assertTrue(a.db.hasFeed("fb"))
    }

    @Test
    fun aJoiningDeviceWithNothingNewUploadsNothing() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.db.insertFeed("fa", now = 1)
        a.syncOk()
        val b = device("b", cloud) // an empty database
        val createsBefore = cloud.createCount

        b.syncOk()

        // After merging, every row b holds is identical to the cloud's: nothing to send.
        assertEquals(createsBefore, cloud.createCount)
        assertEquals(0L, b.capture().full_pending)
        assertTrue(b.db.hasFeed("fa"))
    }

    @Test
    fun aGenerationChangeMakesADeviceRejoin() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val oldId = a.deviceId()
        val newGeneration = ids()
        val (base, bytes) = SyncFiles.transportFile(
            TransportMeta(newGeneration, TransportFileKind.BASE, ids(), 1, now),
            "INSERT INTO feeds (id, url, title, updated_at, created_at, sort_order) VALUES ('fx', 'https://x', 'X', 1, 1, 0)",
        )
        cloud.put(base.fileName, bytes)
        cloud.put(SYNC_POINTER_PATH, SyncPointer.encode(newGeneration).encodeToByteArray())

        a.syncOk()

        assertEquals(newGeneration, a.state(SYNC_STATE_KS_GENERATION))
        assertNotEquals(oldId, a.deviceId())
        assertTrue(a.db.hasFeed("fx"))
    }

    @Test
    fun joiningAGenerationClearsTheRawFolderVersionsOfTheFilesMergedBefore() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.db.insertFeed("fa", now = 1)
        a.syncOk()
        // A raw folder version recorded from the old generation's files would outrank the new
        // generation's equal-stamped version in the merge, so it must not survive the rejoin.
        a.driver.execute(null, "INSERT INTO ks_feed_folder_raw (feed_id, folder_updated_at, folder_id) VALUES ('fa', 500, 'dz')", 0)
        val newGeneration = ids()
        val (base, bytes) = SyncFiles.transportFile(TransportMeta(newGeneration, TransportFileKind.BASE, ids(), 1, now), feedRow("fx"))
        cloud.put(base.fileName, bytes)
        cloud.put(SYNC_POINTER_PATH, SyncPointer.encode(newGeneration).encodeToByteArray())

        a.syncOk()

        assertEquals(newGeneration, a.state(SYNC_STATE_KS_GENERATION))
        // The new generation's merge seeds rows of its own (for the feeds its files carry); the old one's must be gone.
        assertEquals(0L, a.driver.executeQuery(null, "SELECT COUNT(*) FROM ks_feed_folder_raw WHERE feed_id = 'fa'", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value)
    }

    // ---- Pointer and pre-delta data ----

    @Test
    fun aMalformedPointerIsIncompatibleCloudData() = runTest {
        val cloud = FakeCloudStorage()
        malformedPointer(cloud)
        val result = newRepo(cloud).sync()
        assertIs<Result.Err>(result)
        assertIs<CloudDataIncompatibleException>(result.exception)
        assertEquals(0, cloud.createCount + cloud.uploadCount)
    }

    @Test
    fun aPointerOfANewerFormatAsksForAnUpdate() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(SYNC_POINTER_PATH, """{"format":4,"generation":"${ids()}"}""".encodeToByteArray())
        val result = newRepo(cloud).sync()
        assertIs<Result.Err>(result)
        assertIs<SchemaVersionException>(result.exception)
        assertEquals(0, cloud.createCount + cloud.uploadCount)
    }

    @Test
    fun preDeltaDataWithoutAPointerIsLeftForTheMigration() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_GZ_PATH, byteArrayOf(1, 2, 3))
        val result = newRepo(cloud).sync()
        assertIs<Result.Err>(result)
        assertIs<CloudStorageException>(result.exception)
        assertEquals(setOf("keryx.db.gz"), cloud.names())
        assertEquals(0L, localDb.sync_bookkeepingQueries.captureState().executeAsOne().enabled)
    }

    // ---- Receiving: validation and failures ----

    /** A cloud with a's base, plus whatever a test plants; returns a (synced) and the generation. */
    private suspend fun TestScope.seeded(cloud: FakeCloudStorage): Pair<SyncDevice, String> {
        val a = device("a", cloud)
        a.syncOk()
        return a to generationOf(cloud)
    }

    private fun feedRow(id: String) =
        "INSERT INTO feeds (id, url, title, updated_at, created_at, sort_order) VALUES ('$id', 'https://$id', '$id', 1, 1, 0)"

    @Test
    fun aDeltaFromAnotherDeviceIsMergedAndRecorded() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val other = ids()
        val (name, bytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, other, 1, now), feedRow("fy"))
        cloud.put(name.fileName, bytes)

        a.syncOk()

        assertTrue(a.db.hasFeed("fy"))
        assertTrue(name.fileName in a.db.sync_bookkeepingQueries.appliedAll().executeAsList())
        assertEquals(1L, a.db.sync_bookkeepingQueries.cursorAll().executeAsList().single { it.device == other }.seq)
        assertTrue(a.outbox().isEmpty(), "a merge records nothing in the outbox")
        val downloads = cloud.downloadCount
        a.syncOk()
        assertEquals(downloads, cloud.downloadCount, "an applied delta is not downloaded again")
    }

    @Test
    fun aCorruptDeltaFailsTheSyncAndNothingIsMerged() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val (good, goodBytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, ids(), 1, now), feedRow("good"))
        cloud.put(good.fileName, goodBytes)
        val corrupt = TransportFileName(gen, TransportFileKind.DELTA, ids(), 1, now, "0123456789ab")
        cloud.put(corrupt.fileName, "not gzip".encodeToByteArray())
        var corruptDownloads = 0
        cloud.downloadHook = { if (it == corrupt.fileName) corruptDownloads++ }

        val result = a.repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudDataIncompatibleException>(result.exception)
        assertEquals(2, corruptDownloads, "downloaded once more before being judged corrupt")
        assertFalse(a.db.hasFeed("good"), "one failing file and nothing from that sync is merged")
        assertTrue(a.repo.autoSyncSuspended.value)
    }

    @Test
    fun aHashMismatchIsCorrupt() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val (name, bytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, ids(), 1, now), feedRow("fy"))
        val wrongName = name.copy(hash12 = if (name.hash12 == "000000000000") "111111111111" else "000000000000")
        cloud.put(wrongName.fileName, bytes)

        val result = a.repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudDataIncompatibleException>(result.exception)
        assertFalse(a.db.hasFeed("fy"))
    }

    @Test
    fun aWrongTypeInAStampColumnIsCorrupt() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val (name, bytes) = SyncFiles.transportFile(
            TransportMeta(gen, TransportFileKind.DELTA, ids(), 1, now),
            "INSERT INTO feeds (id, url, title, updated_at, created_at, sort_order) VALUES ('fy', 'https://y', 'Y', 'soon', 1, 0)",
        )
        cloud.put(name.fileName, bytes)
        val result = a.repo.sync()
        assertIs<Result.Err>(result)
        assertIs<CloudDataIncompatibleException>(result.exception)
        assertFalse(a.db.hasFeed("fy"))
    }

    @Test
    fun aDeltaOfANewerTransportVersionAsksForAnUpdateWithoutAdvancingAnything() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val other = ids()
        val (good, goodBytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, other, 1, now), feedRow("good"))
        cloud.put(good.fileName, goodBytes)
        val (newer, newerBytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, other, 2, now), "PRAGMA user_version = 2")
        cloud.put(newer.fileName, newerBytes)
        val cursorsBefore = a.db.sync_bookkeepingQueries.cursorAll().executeAsList()

        val result = a.repo.sync()

        assertIs<Result.Err>(result)
        assertIs<SchemaVersionException>(result.exception)
        assertFalse(a.db.hasFeed("good"))
        assertEquals(cursorsBefore, a.db.sync_bookkeepingQueries.cursorAll().executeAsList())
        assertFalse(a.repo.autoSyncSuspended.value)
    }

    @Test
    fun aTransientDownloadFailureIsRetriedByTheNextSync() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val (name, bytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, ids(), 1, now), feedRow("fy"))
        cloud.put(name.fileName, bytes)
        cloud.queueDownload(Result.Err(CloudStorageException("timeout")))

        val first = a.repo.sync()
        assertIs<Result.Err>(first)
        assertIs<CloudStorageException>(first.exception)
        assertFalse(a.repo.autoSyncSuspended.value)

        a.syncOk()
        assertTrue(a.db.hasFeed("fy"))
    }

    @Test
    fun aListedFileGoneByTheTimeItIsDownloadedIsTreatedAsNotListedYet() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val other = ids()
        val (name, bytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, other, 1, now), feedRow("fy"))
        cloud.put(name.fileName, bytes)
        cloud.downloadHook = { if (it == name.fileName) cloud.files.remove(it) }
        val receivedBefore = a.state(SYNC_STATE_KS_RECEIVED_AT)
        now += 1

        a.syncOk()

        assertFalse(a.db.hasFeed("fy"))
        assertEquals(receivedBefore, a.state(SYNC_STATE_KS_RECEIVED_AT), "a receive that missed a file is not complete")
        assertTrue(a.db.sync_bookkeepingQueries.cursorAll().executeAsList().none { it.device == other })
    }

    @Test
    fun aGapInTheListingStopsTheCursorButEverythingElseIsMerged() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val other = ids()
        val (first, firstBytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, other, 1, now), feedRow("one"))
        val (second, secondBytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, other, 2, now), feedRow("two"))
        cloud.put(second.fileName, secondBytes)

        a.syncOk()
        assertTrue(a.db.hasFeed("two"), "the merge is commutative: later deltas need not wait")
        assertTrue(a.db.sync_bookkeepingQueries.cursorAll().executeAsList().none { it.device == other })

        cloud.put(first.fileName, firstBytes) // the listing catches up
        a.syncOk()
        assertTrue(a.db.hasFeed("one"))
        assertEquals(2L, a.db.sync_bookkeepingQueries.cursorAll().executeAsList().single { it.device == other }.seq)
    }

    @Test
    fun aKnownBaseNamingACloneDeltaBelowTheCursorIsFetchedOnceThatDeltaIsGone() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val x = ids()
        // Two copies of device x each wrote a delta 1. a merges copy A; copy B never shows in a's listing.
        val (deltaA, bytesA) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, x, 1, now), feedRow("fromA"))
        val (deltaB, bytesB) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, x, 1, now), feedRow("fromB"))
        cloud.put(deltaA.fileName, bytesA)
        cloud.files[deltaB.fileName] = FakeCloudStorage.StoredFile(bytesB, "rev-b", Long.MAX_VALUE)
        // Another device's fold of both copies, which names each of them explicitly.
        val (base, baseBytes) = SyncFiles.transportFile(
            TransportMeta(gen, TransportFileKind.BASE, ids(), 1, now),
            feedRow("fromA"),
            feedRow("fromB"),
            "INSERT INTO ks_covered (name, created_at) VALUES ('${deltaA.fileName}', $now), ('${deltaB.fileName}', $now)",
        )
        cloud.put(base.fileName, baseBytes)

        a.syncOk()

        assertTrue(a.db.hasFeed("fromA"))
        assertFalse(a.db.hasFeed("fromB"))
        assertTrue(base.fileName in SyncBaseCache.decode(a.state(SYNC_STATE_KS_BASES)), "the fold looked the base up without merging it")
        assertEquals(1L, a.db.sync_bookkeepingQueries.cursorAll().executeAsList().single { it.device == x }.seq)

        cloud.files.remove(deltaB.fileName) // garbage-collected before a ever listed it

        a.syncOk()

        assertTrue(a.db.hasFeed("fromB"), "the cursor is no proof that the clone's delta 1 was merged")
        val downloads = cloud.downloadCount
        a.syncOk()
        a.syncOk()
        assertEquals(downloads, cloud.downloadCount, "a merged base is not fetched again for the names it lists")
    }

    @Test
    fun duplicateNamesInAListingAreFetchedOnce() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val (name, bytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, ids(), 1, now), feedRow("fy"))
        cloud.put(name.fileName, bytes)
        cloud.duplicateNames = true
        var downloads = 0
        cloud.downloadHook = { if (it == name.fileName) downloads++ }

        a.syncOk()

        assertEquals(1, downloads)
        assertTrue(a.db.hasFeed("fy"))
    }

    @Test
    fun aFileThatIsNotOursIsIgnored() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        cloud.put("ks-$gen-d-NOT-A-UUID-1-0123456789ab.db.gz", byteArrayOf(1))
        cloud.put("notes.txt", byteArrayOf(1))
        a.syncOk()
    }

    // ---- Merge side effects ----

    @Test
    fun aMergeNotifiesQueryListenersAndIndexesMergedArticlesWithTheirText() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val (name, bytes) = SyncFiles.transportFile(
            TransportMeta(gen, TransportFileKind.DELTA, ids(), 1, now),
            feedRow("fy"),
            "INSERT INTO articles (id, feed_id, guid, url, title, content, is_read, is_starred, cached_at, updated_at, created_at, body_updated_at) " +
                "VALUES ('merged', 'fy', 'g', 'https://a', 'Merged', '<p>multiplatform <b>body</b></p>', 0, 0, 1, 1, 1, 1)",
        )
        cloud.put(name.fileName, bytes)
        var foldersNotified = false
        val listener = app.cash.sqldelight.Query.Listener { foldersNotified = true }
        a.driver.addListener("folders", listener = listener)

        a.syncOk()

        assertTrue(foldersNotified)
        a.driver.removeListener("folders", listener = listener)
        assertEquals("multiplatform body", a.db.articlesQueries.getById("merged").executeAsOne().search_text)
        assertEquals(0L, a.driver.executeQuery(null, "SELECT COUNT(*) FROM ks_search_pending", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value)
        assertEquals(listOf("merged"), FtsSearch(a.driver).search("multiplatform", ArticleFilter.All).map { it.id })
    }

    @Test
    fun aFailureAfterTheMergeCommittedIsNeverBlamedOnTheCloud() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        a.driver.execute(null, "DROP TABLE articles_fts", 0)
        val (name, bytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, ids(), 1, now), feedRow("fy"))
        cloud.put(name.fileName, bytes)

        val result = a.repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudStorageException>(result.exception)
        assertTrue(a.db.hasFeed("fy"), "the merge itself committed")
        assertEquals(AppNotificationAction.ShowSettingsTab("cloud_sync"), a.notificationCenter.items.value.single().action)
    }

    @Test
    fun aFailedIndexingAfterTheMergeNotifiesListenersAndIsRetriedByTheNextSync() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val (name, bytes) = SyncFiles.transportFile(
            TransportMeta(gen, TransportFileKind.DELTA, ids(), 1, now),
            feedRow("fy"),
            "INSERT INTO articles (id, feed_id, guid, url, title, content, is_read, is_starred, cached_at, updated_at, created_at, body_updated_at) " +
                "VALUES ('merged', 'fy', 'g', 'https://a', 'Merged', '<p>multiplatform <b>body</b></p>', 0, 0, 1, 1, 1, 1)",
        )
        cloud.put(name.fileName, bytes)
        var feedsNotified = false
        val listener = app.cash.sqldelight.Query.Listener { feedsNotified = true }
        a.driver.addListener("feeds", listener = listener)
        // Writing to the search index fails once.
        a.driver.execute(null, "DROP TABLE articles_fts", 0)

        val first = a.repo.sync()

        assertIs<Result.Err>(first)
        assertIs<CloudStorageException>(first.exception)
        assertTrue(a.db.hasFeed("fy"), "the merge itself committed")
        assertTrue(name.fileName in a.db.sync_bookkeepingQueries.appliedAll().executeAsList())
        assertTrue(feedsNotified, "the UI hears of a committed merge even when its indexing fails")
        a.driver.removeListener("feeds", listener = listener)
        assertEquals("1", a.state(SYNC_STATE_INDEX_PENDING))

        a.fts.createTable() // the index is writable again, and empty
        assertTrue(FtsSearch(a.driver).search("multiplatform", ArticleFilter.All).isEmpty())

        a.syncOk() // nothing left to merge: only the indexing is retried

        assertEquals(listOf("merged"), FtsSearch(a.driver).search("multiplatform", ArticleFilter.All).map { it.id })
        assertNull(a.state(SYNC_STATE_INDEX_PENDING))
    }

    // ---- Sending: interruptions at every step ----

    private suspend fun TestScope.crashingSend(point: SyncHookPoint): Pair<SyncDevice, FakeCloudStorage> {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        a.db.insertFeed("f2", now = 5)
        a.repo.testHook = { p, _ -> if (p == point) throw SimulatedCrash() }
        assertFailsWith<SimulatedCrash> { a.repo.sync() }
        a.restart()
        return a to cloud
    }

    private fun SyncDevice.pendingFile(n: Long) = File(dataDir, "pending-$n.db.gz")

    @Test
    fun aCrashBeforeTheUploadIsResumedUnderTheSameName() = runTest {
        val (a, cloud) = crashingSend(SyncHookPoint.BEFORE_SEND_UPLOAD)
        val attempt = SendAttempt.decode(a.state(SYNC_STATE_KS_ATTEMPT))!!
        assertTrue(a.pendingFile(2).exists())
        assertTrue(deltasOf(cloud).isEmpty())

        a.syncOk()

        assertEquals(listOf(attempt.name), deltasOf(cloud))
        assertNull(a.state(SYNC_STATE_KS_ATTEMPT))
        assertTrue(a.outbox().isEmpty())
        assertFalse(a.pendingFile(2).exists())
        val b = device("b", cloud)
        b.syncOk()
        assertTrue(b.db.hasFeed("f2"))
    }

    @Test
    fun aCrashAfterTheUploadIsCommittedOnResumeWithoutADuplicate() = runTest {
        val (a, cloud) = crashingSend(SyncHookPoint.AFTER_SEND_UPLOAD)
        val name = SendAttempt.decode(a.state(SYNC_STATE_KS_ATTEMPT))!!.name
        assertEquals(listOf(name), deltasOf(cloud))

        a.syncOk()

        assertEquals(listOf(name), deltasOf(cloud), "the same name means the same file: no second delta")
        assertEquals(1, cloud.written.count { it == name.fileName })
        assertNull(a.state(SYNC_STATE_KS_ATTEMPT))
        assertTrue(a.outbox().isEmpty())
        assertEquals("2", a.state("ks_last_seq"))
    }

    @Test
    fun aCrashAfterTheCommitLeavesOnlyAStaleFileThatIsCleanedUp() = runTest {
        val (a, cloud) = crashingSend(SyncHookPoint.AFTER_SEND_COMMIT)
        assertNull(a.state(SYNC_STATE_KS_ATTEMPT))
        assertTrue(a.outbox().isEmpty())
        assertTrue(a.pendingFile(2).exists())
        val deltas = deltasOf(cloud)

        a.syncOk()

        assertFalse(a.pendingFile(2).exists())
        assertEquals(deltas, deltasOf(cloud), "nothing is sent twice")
    }

    @Test
    fun aDamagedSavedSendTakesANewIdentityAndResendsTheChanges() = runTest {
        val (a, cloud) = crashingSend(SyncHookPoint.BEFORE_SEND_UPLOAD)
        val oldId = a.deviceId()
        a.pendingFile(2).writeBytes(byteArrayOf(1, 2, 3))

        a.syncOk()

        assertNotEquals(oldId, a.deviceId())
        assertTrue(deltasOf(cloud, oldId).isEmpty(), "a damaged file is never uploaded")
        assertEquals(1, deltasOf(cloud, a.deviceId()).size)
        val b = device("b", cloud)
        b.syncOk()
        assertTrue(b.db.hasFeed("f2"))
    }

    @Test
    fun aSendInterruptedLongerAgoThanTheResumeLimitIsResentUnderANewIdentity() = runTest {
        val (a, cloud) = crashingSend(SyncHookPoint.BEFORE_SEND_UPLOAD)
        val oldId = a.deviceId()
        val stale = SendAttempt.decode(a.state(SYNC_STATE_KS_ATTEMPT))!!.name
        now += KS_RESUME_MAX_AGE_MS + 1

        a.syncOk()

        assertFalse(stale.fileName in cloud.names(), "an old send is never uploaded under its old creation time")
        assertNotEquals(oldId, a.deviceId())
        val resent = deltasOf(cloud, a.deviceId()).single()
        assertEquals(now, resent.createdAt, "the changes are resent with a fresh creation time")
        assertNull(a.state(SYNC_STATE_KS_ATTEMPT))
        assertTrue(a.outbox().isEmpty())
        assertFalse(a.pendingFile(2).exists())
        val b = device("b", cloud)
        b.syncOk()
        assertTrue(b.db.hasFeed("f2"))
    }

    @Test
    fun aSendDatedInTheFutureIsNotResumed() = runTest {
        val (a, cloud) = crashingSend(SyncHookPoint.BEFORE_SEND_UPLOAD)
        val oldId = a.deviceId()
        val stale = SendAttempt.decode(a.state(SYNC_STATE_KS_ATTEMPT))!!.name
        now -= 1 // the clock was set back since

        a.syncOk()

        assertFalse(stale.fileName in cloud.names())
        assertNotEquals(oldId, a.deviceId())
        val b = device("b", cloud)
        b.syncOk()
        assertTrue(b.db.hasFeed("f2"))
    }

    @Test
    fun aFailedUploadKeepsTheAttemptForTheNextSync() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        a.db.insertFeed("f2", now = 5)
        cloud.queueCreate(Result.Err(CloudStorageException("offline")))

        assertIs<Result.Err>(a.repo.sync())
        val name = SendAttempt.decode(a.state(SYNC_STATE_KS_ATTEMPT))!!.name

        a.syncOk()
        assertEquals(listOf(name), deltasOf(cloud))
    }

    // ---- Clones ----

    private fun TestScope.cloneOf(a: SyncDevice, label: String): SyncDevice {
        val copy = File.createTempFile("keryx-clone-", ".db")
        Files.copy(a.file.toPath(), copy.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return device(label, a.cloud as FakeCloudStorage, existingDbFile = copy)
    }

    @Test
    fun aRestoredCopySeeingTheOriginalsLaterDeltaRejoinsBeforeSending() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val copy = cloneOf(a, "copy")
        a.db.insertFeed("fa", now = 5)
        a.syncOk()
        copy.db.insertFeed("fc", now = 6)

        copy.syncOk()

        assertNotEquals(a.deviceId(), copy.deviceId())
        assertEquals(1, deltasOf(cloud, a.deviceId()).size, "the copy never wrote under the shared identity")
        assertTrue(copy.db.hasFeed("fa"))
        val b = device("b", cloud)
        b.syncOk()
        assertTrue(b.db.hasFeed("fa"))
        assertTrue(b.db.hasFeed("fc"))
    }

    @Test
    fun twoCopiesWritingTheSameSeqBothRejoinAndNothingIsLost() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val copy = cloneOf(a, "copy")
        val shared = a.deviceId()
        a.db.insertFeed("fa", now = 5)
        copy.db.insertFeed("fc", now = 6)
        cloud.listingDelay = { 1_000 } // neither sees the other's delta yet
        a.syncOk()
        copy.syncOk()
        assertEquals(2, deltasOf(cloud, shared).size)
        cloud.listingDelay = { 0 }
        cloud.files.replaceAll { _, f -> FakeCloudStorage.StoredFile(f.bytes, f.rev, 0) }

        a.syncOk()
        copy.syncOk()

        assertNotEquals(shared, a.deviceId())
        assertNotEquals(shared, copy.deviceId())
        assertNotEquals(a.deviceId(), copy.deviceId())
        val b = device("b", cloud)
        b.syncOk()
        assertTrue(b.db.hasFeed("fa"))
        assertTrue(b.db.hasFeed("fc"))
    }

    /** A [CloudStorage] whose listing leaves out the names [hidden] accepts, as a lagging listing would. */
    private class LaggingListing(private val inner: FakeCloudStorage, private val hidden: (String) -> Boolean) : CloudStorage by inner {
        override suspend fun list(prefix: String): Result<List<CloudFileEntry>> = when (val r = inner.list(prefix)) {
            is Result.Ok -> Result.Ok(r.value.filterNot { hidden(it.name) })
            is Result.Err -> r
        }
    }

    @Test
    fun aCopysLateDuplicateOfAnImpliedSeqStaysReachableThroughGarbageCollection() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val shared = a.deviceId()
        val copyFile = File.createTempFile("keryx-clone-", ".db")
        Files.copy(a.file.toPath(), copyFile.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        a.churn("x", 3) // deltas 2..4 of the shared identity
        now += KS_DELTA_GRACE_MS + MILLIS_PER_DAY
        a.churn("y", 1) // a fold whose vector now implies 2..4; the old deltas are collected
        val implying = basesOf(cloud).single()
        val vector = SyncFiles.query(cloud.bytesOf(implying.fileName)!!, "SELECT device, seq FROM ks_vector").associate { it[0] to it[1]!!.toLong() }
        assertTrue(vector.getValue(shared) >= 2, "the vector implies the shared identity's delta 2: $vector")
        assertTrue(deltasOf(cloud, shared).none { it.number == 2L }, "the original delta 2 was collected")

        // The copy, whose listing does not show the new base yet, writes its own delta 2 under the shared identity.
        val unseen = cloud.names()
        val copy = SyncDevice(
            "copy", LaggingListing(cloud) { it in unseen }, clock, backgroundScope, ids,
            compactionAllowed = { false }, existingDbFile = copyFile,
        ).also(devices::add)
        copy.db.insertFeed("fc", now = now)
        copy.syncOk()
        val duplicate = deltasOf(cloud, shared).single { it.number == 2L }
        assertEquals(now, duplicate.createdAt)

        a.syncOk()
        assertTrue(duplicate in deltasOf(cloud), "a young duplicate is not covered by the vector, so it is not collected")
        now += KS_DELTA_GRACE_MS + MILLIS_PER_DAY
        a.syncOk()
        a.syncOk()

        val c = device("c", cloud)
        c.syncOk()
        assertTrue(c.db.hasFeed("x0"), "the original delta 2's change")
        assertTrue(c.db.hasFeed("fc"), "the copy's delta 2's change was folded before its file was collected")
    }

    // ---- Bases and garbage collection ----

    /** Sends one small change per sync from [a], [count] times, a second apart. */
    private suspend fun SyncDevice.churn(prefix: String, count: Int) {
        repeat(count) { i ->
            now += 1_000
            db.insertFeed("$prefix$i", now = now)
            syncOk()
        }
    }

    @Test
    fun foldingBuildsADominatingBaseAndGarbageCollectionKeepsEverythingReachable() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val firstBase = basesOf(cloud).single()

        a.churn("x", 5)

        assertFalse(firstBase in basesOf(cloud), "the first base is dominated by a fold of it and deleted")
        val deltasBefore = deltasOf(cloud)
        assertTrue(deltasBefore.isNotEmpty(), "covered deltas stay for the grace period")

        now += KS_DELTA_GRACE_MS + MILLIS_PER_DAY
        a.churn("y", 1)

        val remaining = deltasOf(cloud)
        assertTrue(remaining.none { it in deltasBefore }, "deltas older than the grace period and covered by a base are deleted")
        assertEquals(1, basesOf(cloud).size)

        val c = device("c", cloud)
        c.syncOk()
        assertEquals((0 until 5).map { "x$it" }.toSet() + "y0", feedIds(c.db).filter { it.startsWith("x") || it.startsWith("y") }.toSet())
    }

    @Test
    fun aNewBaseListsExplicitlyOnlyWhatItsVectorDoesNotImply() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        a.churn("x", 3)
        now += KS_DELTA_GRACE_MS + MILLIS_PER_DAY
        a.churn("y", 1)

        val base = basesOf(cloud).single()
        val bytes = cloud.bytesOf(base.fileName)!!
        val vector = SyncFiles.query(bytes, "SELECT device, seq FROM ks_vector").associate { it[0] to it[1]!!.toLong() }
        val covered = SyncFiles.query(bytes, "SELECT name FROM ks_covered").map { TransportFileName.parse(it[0]!!)!! }
        assertEquals(4L, vector.getValue(a.deviceId()), "deltas older than the grace period extend the vector, the fresh one does not: $vector")
        val y0 = cloud.written.mapNotNull(TransportFileName::parse)
            .single { it.kind == TransportFileKind.DELTA && it.device == a.deviceId() && it.number == 5L }
        assertTrue(y0 in covered, "the fresh delta y0 is listed by name, outside the vector: $covered")
        assertTrue(covered.all { it.number > vector.getValue(it.device) }, "implied names are not listed: $covered")
    }

    @Test
    fun foldingIsSkippedWhileTheNetworkDoesNotAllowIt() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud, compactionAllowed = { false })
        a.syncOk()
        a.churn("x", 5)
        assertEquals(1, basesOf(cloud).size)
        assertEquals(5, deltasOf(cloud).size)
    }

    @Test
    fun foldingStopsWhenTheNetworkStopsAllowingItMidFold() = runTest {
        val cloud = FakeCloudStorage()
        var allowed = false
        val a = device("a", cloud, compactionAllowed = { allowed })
        a.syncOk()
        a.churn("x", 5)
        val basesBefore = basesOf(cloud)
        val deltasBefore = deltasOf(cloud)
        assertEquals(1, basesBefore.size)
        assertEquals(5, deltasBefore.size)

        allowed = true
        val downloadsBefore = cloud.downloadCount
        cloud.downloadHook = { name -> if (TransportFileName.parse(name) != null) allowed = false }
        a.syncOk()
        cloud.downloadHook = null

        assertEquals(downloadsBefore + 1, cloud.downloadCount, "no input is downloaded once the fold is no longer allowed")
        assertEquals(basesBefore, basesOf(cloud), "no base is uploaded")
        assertEquals(deltasBefore, deltasOf(cloud))
    }

    @Test
    fun aDeviceAwayLongerThanTheGracePeriodFetchesTheBasesForWhatWasCollected() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val b = device("b", cloud)
        b.syncOk()
        a.churn("x", 2) // folded into a base; the deltas stay for now
        now += KS_DELTA_GRACE_MS + MILLIS_PER_DAY
        a.syncOk() // nothing new: only garbage collection
        assertTrue(deltasOf(cloud, a.deviceId()).isEmpty(), "every delta of a was collected")

        // b sees no delta of a above its cursor — nothing hints at a gap — yet it was away too long.
        b.syncOk()

        assertTrue(b.db.hasFeed("x0"))
        assertTrue(b.db.hasFeed("x1"))
    }

    @Test
    fun aDominatedBaseIsDeletedOnlyOnceItsDominatorIsConfirmed() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val firstBase = basesOf(cloud).single()
        var deletedFirstBase = false
        cloud.metadataHook = { path -> if (path.contains("-b-") && !path.contains(firstBase.fileName)) cloud.queueMetadata(Result.Ok(null)) }
        a.repo.testHook = { point, name -> if (point == SyncHookPoint.BEFORE_GC_DELETE && name == firstBase.fileName) deletedFirstBase = true }

        a.churn("x", 3)

        assertFalse(deletedFirstBase, "a dominator that metadata cannot find is not trusted")
        assertTrue(firstBase in basesOf(cloud))
    }

    @Test
    fun twoDevicesFoldingAtOnceNeverDeleteEachOthersBases() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val b = device("b", cloud)
        b.syncOk()
        a.churn("x", 2)
        b.syncOk()
        // b folds while a is about to upload its own fold of the same inputs.
        a.db.insertFeed("ya", now = now)
        b.db.insertFeed("yb", now = now)
        b.syncOk()
        a.repo.testHook = { point, _ ->
            if (point == SyncHookPoint.BEFORE_BASE_UPLOAD) {
                a.repo.testHook = null
                b.db.insertFeed("zb", now = now)
                b.syncOk()
            }
        }
        a.syncOk()
        repeat(2) {
            b.syncOk()
            a.syncOk()
        }
        assertTrue(basesOf(cloud).isNotEmpty())
        val c = device("c", cloud)
        c.syncOk()
        assertEquals(feedIds(a.db), feedIds(c.db))
        assertEquals(feedIds(b.db), feedIds(c.db))
        assertTrue(setOf("x0", "x1", "ya", "yb", "zb").all { it in feedIds(c.db) })
    }

    // ---- Reset ----

    @Test
    fun resetStartsANewGenerationThatEveryDeviceRejoins() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.db.insertFeed("fa", now = 1)
        a.syncOk()
        val b = device("b", cloud)
        b.db.insertFeed("fb", now = 1)
        b.syncOk()
        val oldGeneration = generationOf(cloud)
        val oldFiles = cloud.names() - "keryx-sync.json"

        assertIs<Result.Ok<Unit>>(a.repo.resetCloudData())

        val newGeneration = generationOf(cloud)
        assertNotEquals(oldGeneration, newGeneration)
        assertTrue(cloud.names().containsAll(oldFiles), "the old generation stays as the archive")
        assertEquals(newGeneration, basesOf(cloud).single { it.generation == newGeneration }.generation)

        b.syncOk()
        assertEquals(newGeneration, b.state(SYNC_STATE_KS_GENERATION))
        val c = device("c", cloud)
        c.syncOk()
        assertTrue(c.db.hasFeed("fa"))
        assertTrue(c.db.hasFeed("fb"))
    }

    @Test
    fun aResetLosingToAnotherDevicesResetJoinsThatGeneration() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val generation = generationOf(cloud)
        cloud.queueUpload(Result.Err(SyncConflictException()))

        assertIs<Result.Ok<Unit>>(a.repo.resetCloudData())

        assertEquals(generation, generationOf(cloud))
        assertEquals(generation, a.state(SYNC_STATE_KS_GENERATION))
    }

    @Test
    fun resetRecoversFromCorruptCloudDataAndResumesAutomaticSync() = runTest {
        val cloud = FakeCloudStorage()
        malformedPointer(cloud)
        val repo = newRepo(cloud)
        repo.sync(SyncTrigger.AUTOMATIC)
        assertTrue(repo.autoSyncSuspended.value)

        assertIs<Result.Ok<Unit>>(repo.resetCloudData())

        assertFalse(repo.autoSyncSuspended.value)
        assertNull(repo.lastSyncError.value)
        val checks = cloud.pointerChecks
        assertIs<Result.Ok<Unit>>(repo.sync(SyncTrigger.AUTOMATIC))
        assertEquals(checks + 1, cloud.pointerChecks, "automatic syncing runs again")
    }

    @Test
    fun syncPhaseReturnsToIdleAfterAFailedReset() = runTest {
        val cloud = FakeCloudStorage()
        cloud.queueCreate(Result.Err(CloudStorageException("quota exceeded")))
        val repo = newRepo(cloud)
        assertIs<Result.Err>(repo.resetCloudData())
        assertEquals(SyncPhase.IDLE, repo.syncPhase.value)
    }

    // ---- Disconnect ----

    @Test
    fun clearSyncFailureStateTurnsRecordingOffAndClearsTheConnectionsBookkeeping() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        a.db.insertFeed("f2", now = 5)
        a.repo.testHook = { p, _ -> if (p == SyncHookPoint.BEFORE_SEND_UPLOAD) throw SimulatedCrash() }
        assertFailsWith<SimulatedCrash> { a.repo.sync() }
        a.driver.execute(null, "INSERT INTO ks_search_pending (rowid) VALUES (77)", 0)
        // A raw folder version from the old connection's files would outrank the next provider's
        // equal-stamped version in the merge, so it must not survive the disconnect.
        a.driver.execute(null, "INSERT INTO ks_feed_folder_raw (feed_id, folder_updated_at, folder_id) VALUES ('f2', 500, 'dz')", 0)
        assertTrue(a.pendingFile(2).exists())

        a.repo.clearSyncFailureState()

        assertEquals(0L, a.capture().enabled)
        assertEquals(0L, a.capture().full_pending)
        val keys = a.driver.executeQuery(null, "SELECT key FROM sync_state", { c ->
            QueryResult.Value(buildList { while (c.next().value) add(c.getString(0)!!) })
        }, 0).value
        assertEquals(listOf(SYNC_STATE_LAST_SYNCED_AT), keys)
        assertTrue(a.outbox().isEmpty())
        assertTrue(a.db.sync_bookkeepingQueries.appliedAll().executeAsList().isEmpty())
        assertTrue(a.db.sync_bookkeepingQueries.cursorAll().executeAsList().isEmpty())
        assertFalse(a.pendingFile(2).exists())
        assertEquals(listOf(77L), a.db.sync_bookkeepingQueries.searchPendingAll().executeAsList())
        assertEquals(0L, a.driver.executeQuery(null, "SELECT COUNT(*) FROM ks_feed_folder_raw", { c -> c.next(); QueryResult.Value(c.getLong(0)!!) }, 0).value)

        a.db.insertFeed("f3", now = 6)
        assertTrue(a.outbox().isEmpty(), "nothing is recorded while local-only")
    }

    @Test
    fun clearSyncFailureStateRemovesTheFileACommittedSendLeftBehind() = runTest {
        val (a, _) = crashingSend(SyncHookPoint.AFTER_SEND_COMMIT)
        assertNull(a.state(SYNC_STATE_KS_ATTEMPT), "no record names the leftover file any more")
        assertTrue(a.pendingFile(2).exists())

        a.repo.clearSyncFailureState()

        assertTrue(a.dataDir.listFiles().orEmpty().none { it.name.matches(Regex("pending-\\d+\\.db\\.gz")) })
    }

    @Test
    fun clearSyncFailureStateCompletesWhenASavedSendFileCannotBeDeleted() = runTest {
        val (a, _) = crashingSend(SyncHookPoint.BEFORE_SEND_UPLOAD)
        assertTrue(a.pendingFile(2).exists())
        // A non-empty directory under a saved send's name: deleting it fails on every platform.
        val undeletable = File(a.dataDir, "pending-9.db.gz").apply { mkdirs() }
        File(undeletable, "inside").writeText("x")

        a.repo.clearSyncFailureState()

        assertEquals(0L, a.capture().enabled)
        assertNull(a.state(SYNC_STATE_KS_ATTEMPT))
        assertTrue(a.outbox().isEmpty())
        assertFalse(a.pendingFile(2).exists(), "the other files are still swept")
        assertTrue(undeletable.exists())
    }

    @Test
    fun anOrphanedSavedSendFileIsSweptOnJoining() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val b = device("b", cloud)
        b.syncOk()
        // No record names it, and its number is not this identity's last committed one.
        val orphan = a.pendingFile(7).apply { writeBytes(byteArrayOf(1, 2, 3)) }
        assertIs<Result.Ok<Unit>>(b.repo.resetCloudData())

        a.syncOk() // a new generation: a joins it

        assertFalse(orphan.exists())
    }

    @Test
    fun clearSyncFailureStateIsNotUndoneByAnInFlightSync() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val b = device("b", cloud)
        val gate = CompletableDeferred<Unit>()
        cloud.downloadGate = gate

        val syncing = launch { b.repo.sync() }
        runCurrent() // b is joining: suspended in a download, holding the mutex, recording on
        val clearing = launch { b.repo.clearSyncFailureState() }
        runCurrent()
        gate.complete(Unit)
        syncing.join()
        clearing.join()

        assertEquals(0L, b.capture().enabled, "a disconnect waits for the sync and then wins")
        assertNull(b.state(SYNC_STATE_KS_DEVICE_ID))
    }

    @Test
    fun clearSyncFailureStateIsNotUndoneByAFailingInFlightSync() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val b = device("b", cloud)
        b.db.insertFeed("fb", now = 1)
        cloud.queueCreate(Result.Err(CloudStorageException("boom")))
        val gate = CompletableDeferred<Unit>()
        cloud.downloadGate = gate

        val syncing = launch { b.repo.sync() }
        runCurrent()
        val clearing = launch { b.repo.clearSyncFailureState() }
        runCurrent()
        gate.complete(Unit)
        syncing.join()
        clearing.join()

        assertNull(b.repo.lastSyncError.value)
        assertFalse(b.repo.lastSyncAuthFailed.value)
    }

    @Test
    fun aDeviceReconnectingAfterADisconnectJoinsAfresh() = runTest {
        val cloud = FakeCloudStorage()
        val a = device("a", cloud)
        a.syncOk()
        val first = a.deviceId()
        a.repo.clearSyncFailureState()
        a.db.insertFeed("offline", now = 5)

        a.syncOk()

        assertNotEquals(first, a.deviceId())
        val b = device("b", cloud)
        b.syncOk()
        assertTrue(b.db.hasFeed("offline"), "the rejoin's full send carries what was written while local-only")
    }

    // ---- Failure state and notifications ----

    @Test
    fun aFailureToAuthenticateIsFlaggedAsOne() = runTest {
        val cloud = FakeCloudStorage()
        cloud.queueMetadata(Result.Err(CloudAuthException("no token")))
        val repo = newRepo(cloud)
        assertIs<Result.Err>(repo.sync())
        assertEquals(ErrorKind.CLOUD_AUTH, repo.lastSyncError.value)
        assertTrue(repo.lastSyncAuthFailed.value)
        val note = notificationCenter.items.value.single()
        assertEquals(AppNotificationLevel.ERROR, note.level)
        assertEquals(NotificationText.SyncFailed(ErrorKind.CLOUD_AUTH), note.text)
        assertEquals(AppNotificationAction.ShowSettingsTab("cloud_sync"), note.action)
    }

    @Test
    fun aFailureForAnyOtherReasonIsNotFlaggedAsAuthentication() = runTest {
        val cloud = FakeCloudStorage()
        cloud.queueMetadata(Result.Err(CloudStorageException("boom")))
        val repo = newRepo(cloud)
        assertIs<Result.Err>(repo.sync())
        assertNotNull(repo.lastSyncError.value)
        assertFalse(repo.lastSyncAuthFailed.value)
    }

    @Test
    fun aSucceedingSyncClearsTheReasonAndTheAuthenticationFlag() = runTest {
        val cloud = FakeCloudStorage()
        cloud.queueMetadata(Result.Err(CloudAuthException("no token")))
        val repo = newRepo(cloud)
        assertIs<Result.Err>(repo.sync())
        assertIs<Result.Ok<Unit>>(repo.sync())
        assertNull(repo.lastSyncError.value)
        assertFalse(repo.lastSyncAuthFailed.value)
    }

    @Test
    fun clearSyncFailureStateClearsTheReasonAndTheAuthenticationFlag() = runTest {
        val cloud = FakeCloudStorage()
        cloud.queueMetadata(Result.Err(CloudAuthException("no token")))
        val repo = newRepo(cloud)
        assertIs<Result.Err>(repo.sync())
        repo.clearSyncFailureState()
        assertNull(repo.lastSyncError.value)
        assertFalse(repo.lastSyncAuthFailed.value)
    }

    @Test
    fun incompatibleCloudDataOffersTheReset() = runTest {
        val cloud = FakeCloudStorage()
        malformedPointer(cloud)
        assertIs<Result.Err>(newRepo(cloud).sync())
        assertEquals(AppNotificationAction.ResetCloudData, notificationCenter.items.value.single().action)
    }

    @Test
    fun aNewerFormatPointsAtTheUpdatesTab() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(SYNC_POINTER_PATH, """{"format":9,"generation":"${ids()}"}""".encodeToByteArray())
        assertIs<Result.Err>(newRepo(cloud).sync())
        assertEquals(AppNotificationAction.ShowSettingsTab("updates"), notificationCenter.items.value.single().action)
    }

    @Test
    fun repeatedIdenticalFailuresCoalesceAndDifferentOnesDoNot() = runTest {
        val cloud = FakeCloudStorage()
        repeat(3) { cloud.queueMetadata(Result.Err(CloudAuthException("no token"))) }
        cloud.queueMetadata(Result.Err(CloudStorageException("network down")))
        val repo = newRepo(cloud)
        repeat(4) { assertIs<Result.Err>(repo.sync()) }
        assertEquals(
            setOf(NotificationText.SyncFailed(ErrorKind.CLOUD_AUTH), NotificationText.SyncFailed(ErrorKind.CLOUD_STORAGE)),
            notificationCenter.items.value.map { it.text }.toSet(),
        )
        assertEquals(2, notificationCenter.items.value.size)
    }

    @Test
    fun successfulAndNoProviderSyncsAddNoNotification() = runTest {
        assertIs<Result.Ok<Unit>>(newRepo(FakeCloudStorage()).sync())
        assertIs<Result.Ok<Unit>>(newRepo(FakeCloudStorage(), cloudProvider = { null }).sync())
        assertTrue(notificationCenter.items.value.isEmpty())
    }

    @Test
    fun lastSyncedAtIsRecordedAndParsed() = runTest {
        val repo = newRepo(FakeCloudStorage())
        assertNull(repo.lastSyncedAt())
        assertIs<Result.Ok<Unit>>(repo.sync())
        assertEquals(now, repo.lastSyncedAt())
        localDb.sync_stateQueries.upsert(SYNC_STATE_LAST_SYNCED_AT, "not-a-number")
        assertNull(repo.lastSyncedAt())
    }

    // ---- Automatic-sync suspension ----

    @Test
    fun automaticSyncIsSuspendedAfterIncompatibleDataAndManualSyncIsNot() = runTest {
        val cloud = FakeCloudStorage()
        malformedPointer(cloud)
        val repo = newRepo(cloud)

        assertIs<Result.Err>(repo.sync(SyncTrigger.AUTOMATIC))
        assertTrue(repo.autoSyncSuspended.value)
        assertEquals(1, cloud.downloadCount)

        assertIs<Result.Ok<Unit>>(repo.sync(SyncTrigger.AUTOMATIC))
        assertEquals(1, cloud.downloadCount, "skipped")
        assertEquals(1, notificationCenter.items.value.size)
        assertNotNull(repo.lastSyncError.value)
        assertEquals(SyncPhase.IDLE, repo.syncPhase.value)

        assertIs<Result.Err>(repo.sync(SyncTrigger.MANUAL))
        assertEquals(2, cloud.downloadCount, "a manual sync always runs")
    }

    @Test
    fun scheduledSyncsAreSuppressedWhileCloudDataIsUnusable() = runTest {
        val cloud = FakeCloudStorage()
        malformedPointer(cloud)
        val repo = newRepo(cloud)
        repo.sync(SyncTrigger.AUTOMATIC)
        repo.scheduleSync()
        repo.scheduleDeferredSync()
        advanceTimeBy(DEFERRED_SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        repo.flushPendingSync()
        assertEquals(1, cloud.pointerChecks)
    }

    @Test
    fun aSuccessfulManualSyncOrADisconnectResumesAutomaticSync() = runTest {
        val cloud = FakeCloudStorage()
        malformedPointer(cloud)
        val repo = newRepo(cloud)
        repo.sync(SyncTrigger.AUTOMATIC)
        assertTrue(repo.autoSyncSuspended.value)

        cloud.files.remove("keryx-sync.json") // the cloud data gets fixed
        assertIs<Result.Ok<Unit>>(repo.sync(SyncTrigger.MANUAL))
        assertFalse(repo.autoSyncSuspended.value)

        malformedPointer(cloud)
        repo.sync(SyncTrigger.AUTOMATIC)
        assertTrue(repo.autoSyncSuspended.value)
        repo.clearSyncFailureState()
        assertFalse(repo.autoSyncSuspended.value)
    }

    @Test
    fun schemaVersionExceptionDoesNotSuspendAutomaticSync() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(SYNC_POINTER_PATH, """{"format":4,"generation":"${ids()}"}""".encodeToByteArray())
        val repo = newRepo(cloud)
        assertIs<Result.Err>(repo.sync(SyncTrigger.AUTOMATIC))
        assertFalse(repo.autoSyncSuspended.value)
        assertIs<Result.Err>(repo.sync(SyncTrigger.AUTOMATIC))
        assertEquals(2, cloud.downloadCount)
    }

    // ---- Network gate ----

    @Test
    fun automaticSyncOnAMeteredNetworkIsDeferredWithoutTouchingTheCloud() = runTest {
        val cloud = FakeCloudStorage()
        val activityCenter = ActivityCenter()
        val repo = newRepo(cloud, activityCenter = activityCenter, automaticSyncAllowedFlow = networkGate(FakeNetworkConditionMonitor(METERED), MutableStateFlow(false)))

        assertIs<Result.Ok<Unit>>(repo.sync(SyncTrigger.AUTOMATIC))

        assertEquals(0, cloud.metadataCount)
        assertTrue(repo.syncDeferredForNetwork.value)
        assertEquals(SyncPhase.IDLE, repo.syncPhase.value)
        assertFalse(activityCenter.activity.value.syncing)
        assertTrue(notificationCenter.items.value.isEmpty())
        assertNull(repo.lastSyncError.value)
    }

    @Test
    fun automaticSyncOnAMeteredNetworkRunsWhenTheUserOptedInButDataSaverStillDefers() = runTest {
        val cloud = FakeCloudStorage()
        val repo = newRepo(cloud, automaticSyncAllowedFlow = networkGate(FakeNetworkConditionMonitor(METERED), MutableStateFlow(true)))
        assertIs<Result.Ok<Unit>>(repo.sync(SyncTrigger.AUTOMATIC))
        assertEquals(1, cloud.pointerChecks)

        val saver = FakeCloudStorage()
        val saving = newRepo(saver, automaticSyncAllowedFlow = networkGate(FakeNetworkConditionMonitor(NetworkCondition(metered = true, dataSaver = true)), MutableStateFlow(true)))
        saving.sync(SyncTrigger.AUTOMATIC)
        assertEquals(0, saver.metadataCount)
        assertTrue(saving.syncDeferredForNetwork.value)
    }

    @Test
    fun manualSyncOnAMeteredNetworkRunsAndClearsTheDeferral() = runTest {
        val cloud = FakeCloudStorage()
        val monitor = FakeNetworkConditionMonitor(METERED)
        val repo = newRepo(cloud, automaticSyncAllowedFlow = networkGate(monitor, MutableStateFlow(false)))
        repo.sync(SyncTrigger.AUTOMATIC)

        assertIs<Result.Ok<Unit>>(repo.sync(SyncTrigger.MANUAL))

        assertFalse(repo.syncDeferredForNetwork.value)
        val checks = cloud.pointerChecks
        monitor.current.value = NetworkCondition.UNRESTRICTED
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(checks, cloud.pointerChecks)
    }

    @Test
    fun aDeferredSyncRunsOnceWhenTheNetworkAllowsIt() = runTest {
        val cloud = FakeCloudStorage()
        val monitor = FakeNetworkConditionMonitor(METERED)
        val repo = newRepo(cloud, automaticSyncAllowedFlow = networkGate(monitor, MutableStateFlow(false)))
        repeat(3) { repo.sync(SyncTrigger.AUTOMATIC) }
        runCurrent()
        assertEquals(0, cloud.metadataCount)

        monitor.current.value = NetworkCondition.UNRESTRICTED
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()

        assertEquals(1, cloud.pointerChecks)
        assertFalse(repo.syncDeferredForNetwork.value)
    }

    @Test
    fun aDeferralEndsWhenTheNetworkAllowsEvenIfTheSyncThenFails() = runTest {
        val cloud = FakeCloudStorage()
        val monitor = FakeNetworkConditionMonitor(METERED)
        val repo = newRepo(cloud, automaticSyncAllowedFlow = networkGate(monitor, MutableStateFlow(false)))
        repo.sync(SyncTrigger.AUTOMATIC)
        cloud.queueMetadata(Result.Err(CloudStorageException("boom")))
        monitor.current.value = NetworkCondition.UNRESTRICTED
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertFalse(repo.syncDeferredForNetwork.value)
        assertNotNull(repo.lastSyncError.value)
    }

    @Test
    fun aSyncSucceedingDoesNotClearADeferralRaisedWhileItRan() = runTest {
        val cloud = FakeCloudStorage()
        val monitor = FakeNetworkConditionMonitor(NetworkCondition.UNRESTRICTED)
        val repo = newRepo(cloud, automaticSyncAllowedFlow = networkGate(monitor, MutableStateFlow(false)))
        val gate = CompletableDeferred<Unit>()
        cloud.metadataHook = { if (it == SYNC_POINTER_PATH) gate.await() }

        val running = launch { repo.sync(SyncTrigger.MANUAL) }
        runCurrent()
        monitor.current.value = METERED
        repo.sync(SyncTrigger.AUTOMATIC)
        assertTrue(repo.syncDeferredForNetwork.value)
        gate.complete(Unit)
        running.join()

        assertTrue(repo.syncDeferredForNetwork.value)
        cloud.metadataHook = null
        val checks = cloud.pointerChecks
        monitor.current.value = NetworkCondition.UNRESTRICTED
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertTrue(cloud.pointerChecks > checks)
        assertFalse(repo.syncDeferredForNetwork.value)
    }

    @Test
    fun suspendingAutomaticSyncOrDisconnectingDropsTheDeferral() = runTest {
        val cloud = FakeCloudStorage()
        malformedPointer(cloud)
        val monitor = FakeNetworkConditionMonitor(METERED)
        val repo = newRepo(cloud, automaticSyncAllowedFlow = networkGate(monitor, MutableStateFlow(false)))
        repo.sync(SyncTrigger.AUTOMATIC)
        assertTrue(repo.syncDeferredForNetwork.value)
        assertIs<Result.Err>(repo.sync(SyncTrigger.MANUAL))
        assertFalse(repo.syncDeferredForNetwork.value)

        val other = FakeCloudStorage()
        val repo2 = newRepo(other, automaticSyncAllowedFlow = networkGate(monitor, MutableStateFlow(false)))
        repo2.sync(SyncTrigger.AUTOMATIC)
        repo2.clearSyncFailureState()
        assertFalse(repo2.syncDeferredForNetwork.value)
        monitor.current.value = NetworkCondition.UNRESTRICTED
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(0, other.metadataCount)
    }

    @Test
    fun aDeferralRacingADisconnectDoesNotRaiseTheFlag() = runTest {
        val cloud = FakeCloudStorage()
        var providerCalls = 0
        val repo = newRepo(
            cloud,
            automaticSyncAllowedFlow = networkGate(FakeNetworkConditionMonitor(METERED), MutableStateFlow(false)),
            cloudProvider = { if (providerCalls++ == 0) cloud else null },
        )
        repo.sync(SyncTrigger.AUTOMATIC)
        assertFalse(repo.syncDeferredForNetwork.value)
    }

    @Test
    fun automaticSyncWithNoProviderIsNotDeferred() = runTest {
        val repo = newRepo(
            FakeCloudStorage(),
            automaticSyncAllowedFlow = networkGate(FakeNetworkConditionMonitor(METERED), MutableStateFlow(false)),
            cloudProvider = { null },
        )
        assertIs<Result.Ok<Unit>>(repo.sync(SyncTrigger.AUTOMATIC))
        assertFalse(repo.syncDeferredForNetwork.value)
    }

    // ---- Debounce, deferral and flushing ----
    //
    // Each test syncs once first and resets the counter, so from there on pointerChecks counts syncs.

    private suspend fun TestScope.syncedRepo(
        cloud: FakeCloudStorage,
        pendingLocalWrites: PendingLocalWrites = PendingLocalWrites(),
    ): SyncRepository =
        newRepo(cloud, pendingLocalWrites = pendingLocalWrites).also {
            assertIs<Result.Ok<Unit>>(it.sync())
            cloud.pointerChecks = 0
        }

    @Test
    fun scheduleSyncDebouncesAndCollapsesABurst() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)

        repo.scheduleSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS / 2)
        repeat(50) { repo.scheduleSync() }
        advanceTimeBy(SYNC_DEBOUNCE_MS / 2)
        runCurrent()
        assertEquals(0, cloud.pointerChecks)

        advanceTimeBy(SYNC_DEBOUNCE_MS)
        runCurrent()
        assertEquals(1, cloud.pointerChecks)

        repo.scheduleSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(2, cloud.pointerChecks, "the scheduler still works after the burst")
    }

    @Test
    fun aFailingPendingCheckDoesNotEndDebouncedSyncing() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)
        repo.scheduleSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS / 2)
        // A sync inside the window picks the change up, so the window's expiry has to ask the database.
        assertIs<Result.Ok<Unit>>(repo.sync())
        assertEquals(1, cloud.pointerChecks)
        localDriver.execute(null, "ALTER TABLE sync_capture RENAME TO sync_capture_broken", 0)

        advanceTimeBy(SYNC_DEBOUNCE_MS)
        runCurrent() // the pending check throws

        localDriver.execute(null, "ALTER TABLE sync_capture_broken RENAME TO sync_capture", 0)
        repo.scheduleSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(2, cloud.pointerChecks, "the debounce consumer survived the failure")
    }

    @Test
    fun scheduleDeferredSyncWaitsForTheLongWindowAndAStructuralChangeShortensIt() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)

        repo.scheduleDeferredSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(0, cloud.pointerChecks)
        advanceTimeBy(DEFERRED_SYNC_DEBOUNCE_MS)
        runCurrent()
        assertEquals(1, cloud.pointerChecks)

        repo.scheduleDeferredSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS)
        repo.scheduleSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(2, cloud.pointerChecks)
        advanceTimeBy(DEFERRED_SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(2, cloud.pointerChecks, "the deferred change rode along")
    }

    @Test
    fun deferredSyncGetsTheLongWindowAgainOnceTheShortOneHasExpired() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)
        repo.scheduleSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(1, cloud.pointerChecks)

        repo.scheduleDeferredSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(1, cloud.pointerChecks)
        advanceTimeBy(DEFERRED_SYNC_DEBOUNCE_MS)
        runCurrent()
        assertEquals(2, cloud.pointerChecks)
    }

    @Test
    fun flushPendingSyncSyncsOnlyWhenSomethingIsPending() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)

        repo.flushPendingSync()
        assertEquals(0, cloud.pointerChecks)

        repo.scheduleDeferredSync()
        repo.flushPendingSync()
        assertEquals(1, cloud.pointerChecks)
        advanceTimeBy(DEFERRED_SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(1, cloud.pointerChecks)
    }

    @Test
    fun flushPendingSyncSendsAnOutboxEntryLeftByAnEarlierProcess() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)
        localDb.insertFeed("f2", now = 5) // recorded, but no schedule call reached this process

        repo.flushPendingSync()

        assertEquals(1, cloud.pointerChecks)
        assertTrue(localDb.sync_bookkeepingQueries.outboxAll().executeAsList().isEmpty())
    }

    @Test
    fun flushPendingSyncWaitsForALocalWriteStillQueuedUpToItsBound() = runTest {
        val cloud = FakeCloudStorage()
        val pending = PendingLocalWrites()
        val repo = syncedRepo(cloud, pending)
        pending.begin()

        val flush = launch { repo.flushPendingSync() }
        runCurrent()
        assertFalse(flush.isCompleted)
        repo.scheduleSync()
        pending.end()
        flush.join()
        assertEquals(1, cloud.pointerChecks)

        pending.begin()
        repo.scheduleDeferredSync()
        val bounded = launch { repo.flushPendingSync() }
        advanceTimeBy(LOCAL_WRITES_FLUSH_WAIT_MS - 1)
        runCurrent()
        assertFalse(bounded.isCompleted)
        advanceTimeBy(2)
        bounded.join()
        assertEquals(2, cloud.pointerChecks)
    }

    @Test
    fun anySyncPicksUpPendingChangesAndAFailedOneKeepsThem() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)

        repo.scheduleDeferredSync()
        repo.sync(SyncTrigger.AUTOMATIC)
        repo.flushPendingSync()
        assertEquals(1, cloud.pointerChecks)

        repo.scheduleDeferredSync()
        cloud.queueMetadata(Result.Err(CloudStorageException("offline")))
        repo.flushPendingSync()
        repo.flushPendingSync()
        repo.flushPendingSync()
        assertEquals(3, cloud.pointerChecks, "retried once after the failure, then nothing is left")
    }

    @Test
    fun aSyncWhoseUploadFailsKeepsTheChangePending() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)
        localDb.insertFeed("f2", now = 5)
        repo.scheduleDeferredSync()
        cloud.queueCreate(Result.Err(CloudStorageException("offline")))

        repo.flushPendingSync()
        repo.flushPendingSync()

        assertEquals(2, cloud.pointerChecks)
        assertTrue(localDb.sync_bookkeepingQueries.outboxAll().executeAsList().isEmpty())
    }

    @Test
    fun aChangeMadeDuringASyncKeepsItPending() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)
        repo.scheduleDeferredSync()
        var injected = false
        cloud.metadataHook = {
            if (!injected) {
                injected = true
                repo.scheduleDeferredSync()
            }
        }
        repo.flushPendingSync()
        repo.flushPendingSync()
        assertEquals(2, cloud.pointerChecks)
    }

    @Test
    fun aSyncThatThrowsOrIsCancelledKeepsChangesPending() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)

        repo.scheduleDeferredSync()
        cloud.metadataHook = { throw IllegalStateException("boom") }
        assertFailsWith<IllegalStateException> { repo.flushPendingSync() }
        cloud.metadataHook = null
        repo.flushPendingSync()
        assertEquals(2, cloud.pointerChecks)

        val gate = CompletableDeferred<Unit>()
        cloud.metadataHook = { gate.await() }
        repo.scheduleDeferredSync()
        val running = launch { repo.flushPendingSync() }
        runCurrent()
        running.cancelAndJoin()
        cloud.metadataHook = null
        repo.flushPendingSync()
        assertEquals(4, cloud.pointerChecks)
    }

    @Test
    fun flushPendingSyncWaitsForASyncInFlightAndSendsWhatItLeftBehind() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)
        val gate = CompletableDeferred<Unit>()
        cloud.metadataHook = { gate.await() }

        val inFlight = launch { repo.sync(SyncTrigger.AUTOMATIC) }
        runCurrent()
        repo.scheduleDeferredSync()
        var flushed = false
        val flush = launch { repo.flushPendingSync(); flushed = true }
        runCurrent()
        assertFalse(flushed)

        cloud.metadataHook = null
        gate.complete(Unit)
        inFlight.join()
        flush.join()
        assertEquals(2, cloud.pointerChecks)
    }

    @Test
    fun flushOnLeavingForegroundSkipsTheExitCausedByTheAppsOwnLaunch() = runTest {
        val cloud = FakeCloudStorage()
        val repo = syncedRepo(cloud)
        ExternalAppLaunch.clear()
        repo.flushOnLeavingForeground()
        assertEquals(0, cloud.pointerChecks)

        repo.scheduleDeferredSync()
        ExternalAppLaunch.markLaunched()
        repo.flushOnLeavingForeground()
        assertEquals(0, cloud.pointerChecks)
        repo.flushOnLeavingForeground()
        assertEquals(1, cloud.pointerChecks)
    }

    // ---- Progress ----

    @Test
    fun syncingAndThePhaseFollowTheSyncInFlight() = runTest {
        val cloud = FakeCloudStorage()
        val (a, gen) = seeded(cloud)
        val (name, bytes) = SyncFiles.transportFile(TransportMeta(gen, TransportFileKind.DELTA, ids(), 1, now), feedRow("fy"))
        cloud.put(name.fileName, bytes)
        val gate = CompletableDeferred<Unit>()
        cloud.downloadGate = gate
        assertEquals(SyncPhase.IDLE, a.repo.syncPhase.value)

        val syncing = launch { a.repo.sync() }
        runCurrent()
        assertEquals(SyncPhase.DOWNLOADING, a.repo.syncPhase.value)
        assertTrue(a.activityCenter.activity.value.syncing)

        gate.complete(Unit)
        syncing.join()
        assertEquals(SyncPhase.IDLE, a.repo.syncPhase.value)
        assertFalse(a.activityCenter.activity.value.syncing)
    }

    @Test
    fun syncPhaseReturnsToIdleAfterAFailedSync() = runTest {
        val cloud = FakeCloudStorage()
        cloud.queueMetadata(Result.Err(CloudStorageException("boom")))
        val repo = newRepo(cloud)
        assertIs<Result.Err>(repo.sync())
        assertEquals(SyncPhase.IDLE, repo.syncPhase.value)
    }
}

private val METERED = NetworkCondition(metered = true, dataSaver = false)
