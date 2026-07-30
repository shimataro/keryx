package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.CLOUD_DB_PATH
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.CloudStorageException
import works.merc.keryx.app.core.KeryxException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_DEBOUNCE_MS
import works.merc.keryx.app.core.SYNC_MAX_RETRY
import works.merc.keryx.app.core.SYNC_STATE_CLOUD_FILE_REV
import works.merc.keryx.app.core.SYNC_STATE_LAST_SYNCED_AT
import works.merc.keryx.app.core.SchemaVersionException
import works.merc.keryx.app.core.SyncConflictException
import works.merc.keryx.app.data.cloud.CloudFile
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.fileDb
import works.merc.keryx.app.insertFeed
import java.io.File
import java.nio.file.Files
import java.sql.DriverManager
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A hand-rolled [CloudStorage] fake backed by an in-memory map, with one-shot
 * result overrides so individual test cases can force conflicts / failures.
 */
private class FakeCloudStorage : CloudStorage {
    val files = mutableMapOf<String, Pair<ByteArray, String>>()
    private var revCounter = 0

    var existsCount = 0
    var downloadCount = 0
    var uploadCount = 0
    var createCount = 0
    var deleteCount = 0

    /** When set, [download] suspends on this gate before returning, so a test can observe an in-flight sync. */
    var downloadGate: CompletableDeferred<Unit>? = null

    private val existsQueue = ArrayDeque<Result<Boolean>>()
    private val downloadQueue = ArrayDeque<Result<CloudFile>>()
    private val uploadQueue = ArrayDeque<Result<Unit>>()
    private val createQueue = ArrayDeque<Result<Unit>>()
    private val deleteQueue = ArrayDeque<Result<Unit>>()

    fun queueExists(r: Result<Boolean>) = existsQueue.addLast(r)
    fun queueDownload(r: Result<CloudFile>) = downloadQueue.addLast(r)
    fun queueUpload(r: Result<Unit>) = uploadQueue.addLast(r)
    fun queueCreate(r: Result<Unit>) = createQueue.addLast(r)
    fun queueDelete(r: Result<Unit>) = deleteQueue.addLast(r)

    fun put(path: String, data: ByteArray, rev: String) {
        files[path] = data to rev
    }

    override suspend fun authenticate(): Result<Unit> = Result.Ok(Unit)

    override suspend fun exists(path: String): Result<Boolean> {
        existsCount++
        existsQueue.removeFirstOrNull()?.let { return it }
        return Result.Ok(files.containsKey(path))
    }

    override suspend fun download(path: String): Result<CloudFile> {
        downloadCount++
        downloadGate?.await()
        downloadQueue.removeFirstOrNull()?.let { return it }
        val f = files[path] ?: return Result.Err(CloudStorageException("not found: $path"))
        return Result.Ok(CloudFile(f.first, f.second))
    }

    override suspend fun upload(path: String, data: ByteArray, expectedRev: String?): Result<Unit> {
        uploadCount++
        uploadQueue.removeFirstOrNull()?.let { return it }
        revCounter++
        files[path] = data to "r$revCounter"
        return Result.Ok(Unit)
    }

    override suspend fun create(path: String, data: ByteArray): Result<Unit> {
        createCount++
        createQueue.removeFirstOrNull()?.let { return it }
        // Create-only: refuse to overwrite an existing file, as the real backends do.
        if (files.containsKey(path)) return Result.Err(SyncConflictException())
        revCounter++
        files[path] = data to "r$revCounter"
        return Result.Ok(Unit)
    }

    override suspend fun delete(path: String): Result<Unit> {
        deleteCount++
        deleteQueue.removeFirstOrNull()?.let { return it }
        files.remove(path) // idempotent: succeeds whether or not it existed
        return Result.Ok(Unit)
    }
}

/** A [NotificationMessages] fake for sync tests: encodes the failing exception type into the message. */
private object FakeSyncNotificationMessages : NotificationMessages {
    override suspend fun feedGone(feedTitle: String): String = "gone:$feedTitle"
    override suspend fun feedUrlChanged(feedTitle: String): String = "urlChanged:$feedTitle"
    override suspend fun newArticles(count: Int): String = "new:$count"
    override suspend fun syncFailed(exception: KeryxException): String = "syncFailed:${exception::class.simpleName}"
    override suspend fun opmlImported(added: Int, failed: Int): String = "opmlImported:$added/$failed"
}

@OptIn(ExperimentalCoroutinesApi::class)
class SyncRepositoryTest {

    private lateinit var localFile: File
    private lateinit var localDriver: SqlDriver
    private lateinit var localDb: KeryxDatabase
    private lateinit var ftsManager: FtsManager
    private lateinit var tempDir: File

    @BeforeTest
    fun setUp() {
        val (f, d, db) = fileDb()
        localFile = f
        localDriver = d
        localDb = db
        localDb.insertFeed("f1", now = 0)
        ftsManager = FtsManager(localDriver)
        ftsManager.ensureIndexed()
        tempDir = Files.createTempDirectory("keryx-sync-test").toFile()
        notificationCenter = NotificationCenter()
    }

    @AfterTest
    fun tearDown() {
        localDriver.close()
        tempDir.deleteRecursively()
        localFile.delete()
    }

    private lateinit var notificationCenter: NotificationCenter

    private fun TestScope.newRepo(
        cloud: CloudStorage,
        clockMillis: Long = 1_000L,
        activityCenter: ActivityCenter = ActivityCenter(backgroundScope),
    ): SyncRepository =
        SyncRepository(
            driver = localDriver,
            db = localDb,
            ftsManager = ftsManager,
            cloudProvider = { cloud },
            clock = Clock { clockMillis },
            scope = this,
            activityCenter = activityCenter,
            notificationCenter = notificationCenter,
            notificationMessages = FakeSyncNotificationMessages,
            localDbPath = localFile.absolutePath,
            tempDir = tempDir.absolutePath,
        )

    /** Builds a standalone, closed cloud DB file's bytes (safe to hand to the fake as "downloaded" data). */
    private fun cloudDbBytes(userVersion: Long? = null): ByteArray {
        val (file, driver, db) = fileDb()
        db.insertFeed("f1", now = 0)
        if (userVersion != null) {
            driver.execute(null, "PRAGMA user_version = $userVersion;", 0)
        }
        driver.close()
        val bytes = file.readBytes()
        file.delete()
        return bytes
    }

    /** A valid SQLite file whose schema is NOT the keryx schema (user_version matches local). */
    private fun foreignSchemaDbBytes(): ByteArray {
        val file = File(tempDir, "foreign.db")
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA user_version = ${KeryxDatabase.Schema.version}")
                st.execute("CREATE TABLE unrelated (x INTEGER)")
            }
        }
        val bytes = file.readBytes()
        file.delete()
        return bytes
    }

    private fun tempCloudDbFile(): File = File(tempDir, "cloud_keryx.db")

    @Test
    fun firstSyncEverCreatesWithoutDownloadOrOverwrite() = runTest {
        val cloud = FakeCloudStorage()
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(0, cloud.downloadCount)
        // First-ever sync uses create-only (never an unconditional overwrite).
        assertEquals(1, cloud.createCount)
        assertEquals(0, cloud.uploadCount)
        assertTrue(cloud.files.containsKey(CLOUD_DB_PATH))
        assertEquals(1_000L, repo.lastSyncedAt())
    }

    @Test
    fun createConflictFallsBackToDownloadMergeInsteadOfOverwriting() = runTest {
        // exists() wrongly reports "absent", but the cloud file actually exists (another device's
        // data). create() must 409, and sync must fall through to download→merge→update rather than
        // clobber the existing data with a fresh upload.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueExists(Result.Ok(false))
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(1, cloud.createCount)
        assertEquals(1, cloud.downloadCount)
        assertEquals(1, cloud.uploadCount)
        assertFalse(tempCloudDbFile().exists())
    }

    @Test
    fun existsErrorIsPropagatedWithoutTouchingFts() = runTest {
        val cloud = FakeCloudStorage()
        cloud.queueExists(Result.Err(CloudAuthException("no token")))
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
        assertEquals(0, cloud.downloadCount)
        assertEquals(0, cloud.uploadCount)
        // FTS was never dropped, so it must still be present.
        assertTrue(ftsManager.exists())
    }

    @Test
    fun happyPathDownloadsMergesAndUploads() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        val repo = newRepo(cloud, clockMillis = 42_000L)

        val result = repo.sync()

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(1, cloud.downloadCount)
        assertEquals(1, cloud.uploadCount)
        assertEquals("r1", localDb.sync_stateQueries.get(SYNC_STATE_CLOUD_FILE_REV).executeAsOne())
        assertEquals("42000", localDb.sync_stateQueries.get(SYNC_STATE_LAST_SYNCED_AT).executeAsOne())
        assertEquals(42_000L, repo.lastSyncedAt())
        // The live FTS index is never dropped (it's excluded on a snapshot copy), so it's still present.
        assertTrue(ftsManager.exists())
        assertFalse(tempCloudDbFile().exists())
    }

    @Test
    fun uploadedFileExcludesFtsButKeepsUserVersionAndLiveIndexIsUntouched() = runTest {
        // Mirror production: the live DB carries the current schema version (fileDb() leaves it 0).
        localDriver.execute(null, "PRAGMA user_version = ${KeryxDatabase.Schema.version};", 0)
        val cloud = FakeCloudStorage()
        val repo = newRepo(cloud)

        assertIs<Result.Ok<Unit>>(repo.sync())

        // The live index is untouched...
        assertTrue(ftsManager.exists())

        // ...but the uploaded snapshot excludes articles_fts and preserves user_version, so a
        // receiving device's DatabaseMerger still fires SchemaVersionException for an out-of-date app.
        val uploaded = cloud.files.getValue(CLOUD_DB_PATH).first
        val check = File(tempDir, "uploaded-check.db").apply { writeBytes(uploaded) }
        DriverManager.getConnection("jdbc:sqlite:${check.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                val ftsCount = st.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='articles_fts'",
                ).use { rs -> rs.next(); rs.getInt(1) }
                assertEquals(0, ftsCount)
                val userVersion = st.executeQuery("PRAGMA user_version")
                    .use { rs -> rs.next(); rs.getLong(1) }
                assertEquals(KeryxDatabase.Schema.version, userVersion)
            }
        }
    }

    @Test
    fun mergeNotifiesQueryListenersSoUiRefreshesWithoutRestart() = runTest {
        // The merge writes through DatabaseMerger's own JDBC connection (bypassing SQLDelight), so
        // watchAll() flows must be poked explicitly. Registering on "folders" checks the cross-table
        // case the plain feeds write wouldn't cover.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        val repo = newRepo(cloud)

        var foldersNotified = false
        val listener = app.cash.sqldelight.Query.Listener { foldersNotified = true }
        localDriver.addListener("folders", listener = listener)

        val result = repo.sync()

        assertIs<Result.Ok<Unit>>(result)
        assertTrue(foldersNotified, "merge must notify folder query listeners so the sidebar refreshes")
        localDriver.removeListener("folders", listener = listener)
    }

    @Test
    fun syncingIsTrueWhileRunningAndFalseAfter() = runTest {
        // Unconfined scope so the ActivityCenter's stateIn reflects counter changes inline.
        val activityScope = CoroutineScope(UnconfinedTestDispatcher(testScheduler))
        val activityCenter = ActivityCenter(activityScope)
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        val gate = CompletableDeferred<Unit>()
        cloud.downloadGate = gate
        val repo = newRepo(cloud, activityCenter = activityCenter)

        assertFalse(activityCenter.syncing.value)

        val job = launch { repo.sync() }
        runCurrent() // advance until sync() suspends inside the gated download
        assertTrue(activityCenter.syncing.value)

        gate.complete(Unit)
        job.join()
        assertFalse(activityCenter.syncing.value)
        activityScope.cancel()
    }

    @Test
    fun conflictRetriesUpToMaxThenFailsWithCloudStorageException() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        repeat(SYNC_MAX_RETRY) { cloud.queueUpload(Result.Err(SyncConflictException())) }
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudStorageException>(result.exception)
        assertEquals(SYNC_MAX_RETRY, cloud.downloadCount)
        assertEquals(SYNC_MAX_RETRY, cloud.uploadCount)
        // The finally-block rebuild still runs even though the overall sync failed.
        assertTrue(ftsManager.exists())
        assertFalse(tempCloudDbFile().exists())
    }

    @Test
    fun nonConflictUploadErrorShortCircuitsWithoutRetry() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueUpload(Result.Err(CloudAuthException("revoked")))
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
        assertEquals(1, cloud.downloadCount)
        assertEquals(1, cloud.uploadCount)
        assertTrue(ftsManager.exists())
    }

    @Test
    fun schemaVersionExceptionFromMergeIsPropagatedUnchanged() = runTest {
        val cloud = FakeCloudStorage()
        // Cloud schema is newer than the local schema version (1).
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(userVersion = 999L), "r1")
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<SchemaVersionException>(result.exception)
        assertEquals(0, cloud.uploadCount)
        assertFalse(tempCloudDbFile().exists())
        // The merge-abort is user-visible via the notification center (the only signal for this path).
        val notes = notificationCenter.items.value
        assertEquals(1, notes.size)
        assertEquals(AppNotificationLevel.ERROR, notes.first().level)
        assertEquals("syncFailed:SchemaVersionException", notes.first().message)
        // The app is out of date; the fix lives on the updates tab.
        assertEquals(AppNotificationAction.ShowSettingsTab("updates"), notes.first().action)
    }

    @Test
    fun corruptCloudDbIsReportedAsIncompatible() = runTest {
        val cloud = FakeCloudStorage()
        // Not a valid SQLite file: opening it throws "file is not a database" — a permanent,
        // non-retryable condition, reported as CloudDataIncompatibleException (not a transient error).
        cloud.put(CLOUD_DB_PATH, byteArrayOf(1, 2, 3, 4), "r1")
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudDataIncompatibleException>(result.exception)
        assertEquals(0, cloud.uploadCount)
        assertFalse(tempCloudDbFile().exists())
        // Surfaced to the notification center, with a reset action offered.
        val notes = notificationCenter.items.value
        assertEquals(1, notes.size)
        assertEquals(AppNotificationLevel.ERROR, notes.first().level)
        assertEquals("syncFailed:CloudDataIncompatibleException", notes.first().message)
        assertEquals(AppNotificationAction.ResetCloudData, notes.first().action)
    }

    @Test
    fun incompatibleSchemaCloudDbIsReportedAsIncompatible() = runTest {
        // A valid SQLite file (user_version matches local) but a foreign schema: the merge statements
        // hit "no such table: cloud.folders", which must be classified as incompatible, not transient.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, foreignSchemaDbBytes(), "r1")
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudDataIncompatibleException>(result.exception)
        assertEquals(0, cloud.uploadCount)
        assertFalse(tempCloudDbFile().exists())
    }

    @Test
    fun mergeFailureWithCompatibleSchemaIsReportedAsStorageException() = runTest {
        // The cloud DB has a valid schema, but a local table is missing, forcing a structural
        // merge failure. Because the cloud DB itself is schema-compatible, this is an app bug
        // (not incompatible data) and must NOT offer the destructive reset-cloud-data action.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        // Remove a local table so merge fails structurally while the cloud DB is valid.
        localDriver.execute(null, "DROP TABLE global_settings", 0)

        val repo = newRepo(cloud)
        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudStorageException>(result.exception)
        assertEquals(0, cloud.uploadCount)
        assertFalse(tempCloudDbFile().exists())

        val notes = notificationCenter.items.value
        assertEquals(1, notes.size)
        assertEquals(AppNotificationLevel.ERROR, notes.first().level)
        assertEquals("syncFailed:CloudStorageException", notes.first().message)
        // A transient / app-bug error must not offer the destructive reset; it points at the
        // cloud-sync tab (reconnect / disconnect / reset all live there) instead.
        assertEquals(AppNotificationAction.ShowSettingsTab("cloud_sync"), notes.first().action)
    }

    @Test
    fun syncFailureIsAddedToNotificationCenter() = runTest {
        val cloud = FakeCloudStorage()
        cloud.queueExists(Result.Err(CloudAuthException("no token")))
        val repo = newRepo(cloud)

        assertIs<Result.Err>(repo.sync())

        val notes = notificationCenter.items.value
        assertEquals(1, notes.size)
        assertEquals(AppNotificationLevel.ERROR, notes.first().level)
        assertEquals("syncFailed:CloudAuthException", notes.first().message)
        // Re-authorizing is done on the cloud-sync tab, so that's where acting on it leads.
        assertEquals(AppNotificationAction.ShowSettingsTab("cloud_sync"), notes.first().action)
    }

    @Test
    fun lastSyncErrorIsSetOnFailureAndClearedOnSuccess() = runTest {
        // The cloud-sync settings tab reads this to show why sync is currently broken, so it must
        // outlive the (dismissible) notification and go away once sync works again.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueDownload(Result.Err(CloudAuthException("no token")))
        val repo = newRepo(cloud)

        assertNull(repo.lastSyncError.value)

        assertIs<Result.Err>(repo.sync())
        assertEquals("syncFailed:CloudAuthException", repo.lastSyncError.value)

        assertIs<Result.Ok<Unit>>(repo.sync())
        assertNull(repo.lastSyncError.value)
    }

    @Test
    fun clearLastSyncErrorResetsToNull() = runTest {
        // Used when the connection that produced the error is torn down, so a subsequently-connected
        // provider does not inherit it (see SettingsViewModel.disconnect()/switchTo()).
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueDownload(Result.Err(CloudAuthException("no token")))
        val repo = newRepo(cloud)

        assertIs<Result.Err>(repo.sync())
        assertEquals("syncFailed:CloudAuthException", repo.lastSyncError.value)

        repo.clearLastSyncError()

        assertNull(repo.lastSyncError.value)
    }

    @Test
    fun lastSyncErrorIsLeftAloneByAnInternallyHandledConflict() = runTest {
        // A rev conflict is retried internally and raises no notification, so it must not overwrite
        // (or clear) the reason shown in the settings tab either. Here every retry conflicts, so the
        // run ends as a CloudStorageException — the reason the user should see.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        repeat(SYNC_MAX_RETRY) { cloud.queueUpload(Result.Err(SyncConflictException())) }
        val repo = newRepo(cloud)

        assertIs<Result.Err>(repo.sync())

        assertEquals("syncFailed:CloudStorageException", repo.lastSyncError.value)
    }

    @Test
    fun repeatedIdenticalSyncFailuresCoalesceIntoOneNotification() = runTest {
        // The background loop / debounced scheduler call sync() repeatedly; an identical recurring
        // failure (e.g. a persistent auth error) must collapse to a single notification rather than
        // piling up one entry per cycle.
        val cloud = FakeCloudStorage()
        cloud.queueExists(Result.Err(CloudAuthException("no token")))
        cloud.queueExists(Result.Err(CloudAuthException("no token")))
        cloud.queueExists(Result.Err(CloudAuthException("no token")))
        val repo = newRepo(cloud)

        repeat(3) { assertIs<Result.Err>(repo.sync()) }

        val notes = notificationCenter.items.value
        assertEquals(1, notes.size)
        assertEquals("syncFailed:CloudAuthException", notes.first().message)
    }

    @Test
    fun differentSyncFailureTypesRemainSeparateNotifications() = runTest {
        // Distinct failure kinds carry distinct messages and must not coalesce into one entry.
        val cloud = FakeCloudStorage()
        cloud.queueExists(Result.Err(CloudAuthException("no token")))
        cloud.queueExists(Result.Err(CloudStorageException("network down")))
        val repo = newRepo(cloud)

        repeat(2) { assertIs<Result.Err>(repo.sync()) }

        val messages = notificationCenter.items.value.map { it.message }.toSet()
        assertEquals(
            setOf("syncFailed:CloudAuthException", "syncFailed:CloudStorageException"),
            messages,
        )
    }

    @Test
    fun successfulSyncAddsNoNotification() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        val repo = newRepo(cloud)

        assertIs<Result.Ok<Unit>>(repo.sync())
        assertTrue(notificationCenter.items.value.isEmpty())
    }

    @Test
    fun noProviderNoOpAddsNoNotification() = runTest {
        val repo = SyncRepository(
            driver = localDriver,
            db = localDb,
            ftsManager = ftsManager,
            cloudProvider = { null },
            clock = Clock { 1_000L },
            scope = this,
            activityCenter = ActivityCenter(backgroundScope),
            notificationCenter = notificationCenter,
            notificationMessages = FakeSyncNotificationMessages,
            localDbPath = localFile.absolutePath,
            tempDir = tempDir.absolutePath,
        )

        assertIs<Result.Ok<Unit>>(repo.sync())
        assertTrue(notificationCenter.items.value.isEmpty())
    }

    @Test
    fun resetCloudDataDeletesThenRecreates() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1") // a pre-existing (e.g. incompatible) cloud file
        val repo = newRepo(cloud, clockMillis = 55_000L)

        val result = repo.resetCloudData()

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(1, cloud.deleteCount)
        assertEquals(1, cloud.createCount)
        assertTrue(cloud.files.containsKey(CLOUD_DB_PATH))
        assertEquals(55_000L, repo.lastSyncedAt())
        assertTrue(notificationCenter.items.value.isEmpty())
    }

    @Test
    fun resetCloudDataDeleteFailureSurfacesErrorAndDoesNotCreate() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueDelete(Result.Err(CloudAuthException("revoked")))
        val repo = newRepo(cloud)

        val result = repo.resetCloudData()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
        assertEquals(1, cloud.deleteCount)
        assertEquals(0, cloud.createCount)
        val notes = notificationCenter.items.value
        assertEquals(1, notes.size)
        assertEquals(AppNotificationLevel.ERROR, notes.first().level)
    }

    @Test
    fun scheduleSyncDebouncesRapidCalls() = runTest {
        val cloud = FakeCloudStorage()
        val repo = newRepo(cloud)

        repo.scheduleSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS / 2)
        repo.scheduleSync() // resets the debounce window
        advanceTimeBy(SYNC_DEBOUNCE_MS / 2)
        runCurrent()
        // Only half of the (reset) debounce window has elapsed since the 2nd call.
        assertEquals(0, cloud.existsCount)

        advanceTimeBy(SYNC_DEBOUNCE_MS)
        runCurrent()
        assertEquals(1, cloud.existsCount)
    }

    @Test
    fun lastSyncedAtParsing() = runTest {
        val repo = newRepo(FakeCloudStorage())

        assertNull(repo.lastSyncedAt())

        localDb.sync_stateQueries.upsert(SYNC_STATE_LAST_SYNCED_AT, "123456")
        assertEquals(123_456L, repo.lastSyncedAt())

        localDb.sync_stateQueries.upsert(SYNC_STATE_LAST_SYNCED_AT, "not-a-number")
        assertNull(repo.lastSyncedAt())
    }
}
