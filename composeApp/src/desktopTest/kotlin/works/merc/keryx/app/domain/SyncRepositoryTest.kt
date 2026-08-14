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
import kotlinx.coroutines.runBlocking
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
import works.merc.keryx.app.data.cloud.CloudFileMeta
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A hand-rolled [CloudStorage] fake backed by an in-memory map, with one-shot
 * result overrides so individual test cases can force conflicts / failures.
 */
private class FakeCloudStorage : CloudStorage {
    val files = mutableMapOf<String, Pair<ByteArray, String>>()
    private var revCounter = 0
    private var conflictCounter = 0

    var existsCount = 0
    var downloadCount = 0
    var uploadCount = 0
    var createCount = 0
    var deleteCount = 0
    var renameCount = 0

    /** Payload bytes moved in each direction, so a test can assert a sync transferred nothing. */
    var downloadedBytes = 0L
    var uploadedBytes = 0L

    /** The destination of the last [rename] call, so a test can assert the archive name. */
    var lastRenameTo: String? = null

    /** When set, [download] suspends on this gate before returning, so a test can observe an in-flight sync. */
    var downloadGate: CompletableDeferred<Unit>? = null

    private val existsQueue = ArrayDeque<Result<CloudFileMeta?>>()
    private val downloadQueue = ArrayDeque<Result<CloudFileMeta>>()
    private val uploadQueue = ArrayDeque<Result<CloudFileMeta>>()
    private val createQueue = ArrayDeque<Result<CloudFileMeta>>()
    private val deleteQueue = ArrayDeque<Result<Unit>>()
    private val renameQueue = ArrayDeque<Result<Unit>>()

    fun queueExists(r: Result<CloudFileMeta?>) = existsQueue.addLast(r)
    fun queueDownload(r: Result<CloudFileMeta>) = downloadQueue.addLast(r)
    fun queueUpload(r: Result<CloudFileMeta>) = uploadQueue.addLast(r)
    fun queueCreate(r: Result<CloudFileMeta>) = createQueue.addLast(r)
    fun queueDelete(r: Result<Unit>) = deleteQueue.addLast(r)
    fun queueRename(r: Result<Unit>) = renameQueue.addLast(r)

    fun put(path: String, data: ByteArray, rev: String) {
        files[path] = data to rev
    }

    /** The revision currently stored at [path], or null when absent. */
    fun revOf(path: String): String? = files[path]?.second

    override suspend fun authenticate(): Result<Unit> = Result.Ok(Unit)

    override suspend fun metadata(path: String): Result<CloudFileMeta?> {
        existsCount++
        existsQueue.removeFirstOrNull()?.let { return it }
        return Result.Ok(files[path]?.let { CloudFileMeta(it.second) })
    }

    override suspend fun download(path: String, destPath: String): Result<CloudFileMeta> {
        downloadCount++
        downloadGate?.await()
        downloadQueue.removeFirstOrNull()?.let { return it }
        val f = files[path] ?: return Result.Err(CloudStorageException("not found: $path"))
        downloadedBytes += f.first.size
        // Write to destPath exactly as a real provider streams the body there, so the merge that
        // follows reads a real file rather than bytes the fake handed back.
        File(destPath).writeBytes(f.first)
        return Result.Ok(CloudFileMeta(f.second))
    }

    override suspend fun upload(path: String, sourcePath: String, expectedRev: String?): Result<CloudFileMeta> {
        val data = File(sourcePath).readBytes()
        uploadCount++
        uploadQueue.removeFirstOrNull()?.let { queued ->
            // A rev-guarded write is only rejected because another writer got there first, so a
            // queued conflict advances the stored revision the way a real backend would. Without
            // that, the retry would see an unchanged rev and (correctly) skip re-downloading,
            // which is not the situation a conflict actually represents.
            if (queued is Result.Err && queued.exception is SyncConflictException) {
                files[path]?.let { (bytes, _) ->
                    // A distinct prefix, so the simulated competing revision can never coincide
                    // with a rev a test seeded via put() (which does not advance revCounter).
                    conflictCounter++
                    files[path] = bytes to "conflicted$conflictCounter"
                }
            }
            return queued
        }
        revCounter++
        uploadedBytes += data.size
        val rev = "r$revCounter"
        files[path] = data to rev
        return Result.Ok(CloudFileMeta(rev))
    }

    override suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta> {
        val data = File(sourcePath).readBytes()
        createCount++
        createQueue.removeFirstOrNull()?.let { return it }
        // Create-only: refuse to overwrite an existing file, as the real backends do.
        if (files.containsKey(path)) return Result.Err(SyncConflictException())
        revCounter++
        uploadedBytes += data.size
        val rev = "r$revCounter"
        files[path] = data to rev
        return Result.Ok(CloudFileMeta(rev))
    }

    override suspend fun delete(path: String): Result<Unit> {
        deleteCount++
        deleteQueue.removeFirstOrNull()?.let { return it }
        files.remove(path) // idempotent: succeeds whether or not it existed
        return Result.Ok(Unit)
    }

    override suspend fun rename(from: String, to: String): Result<Unit> {
        renameCount++
        lastRenameTo = to
        renameQueue.removeFirstOrNull()?.let { return it }
        val f = files.remove(from) ?: return Result.Ok(Unit) // idempotent: absent source is a no-op
        if (files.containsKey(to)) {
            files[from] = f // put it back — the real backends never overwrite the destination
            return Result.Err(CloudStorageException("destination exists: $to"))
        }
        files[to] = f
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
        runBlocking { ftsManager.ensureIndexed() }
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
            scope = backgroundScope,
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

    /**
     * Builds a structurally-valid keryx cloud DB (all six tables, with the columns
     * `DatabaseMerger.validateSchema` expects) whose `feeds` table is [feedsTableSql] instead of
     * the real DDL — typically the same columns minus one constraint — then populated via
     * [insertSql]. Used to reproduce "the cloud's own row set violates a constraint this app's
     * schema requires": `MergeSql`'s `NOT EXISTS`/`EXISTS` guards only rule out collisions against
     * *main*'s rows, so a violation entirely inside the cloud DB — impossible to construct through
     * this app, since its own schema forbids it — reaches the merge INSERT unguarded.
     */
    private fun cloudDbWithRelaxedFeedsSchema(feedsTableSql: String, insertSql: List<String>): ByteArray {
        val file = File.createTempFile("relaxed-feeds-", ".db", tempDir)
        DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA user_version = ${KeryxDatabase.Schema.version}")
                st.execute(
                    """
                    CREATE TABLE folders (
                        id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL UNIQUE,
                        sort_order INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER,
                        updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(feedsTableSql)
                st.execute(
                    """
                    CREATE TABLE tags (
                        id TEXT NOT NULL PRIMARY KEY, name TEXT NOT NULL UNIQUE, color TEXT,
                        sort_order INTEGER NOT NULL DEFAULT 0, deleted_at INTEGER,
                        updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE articles (
                        id TEXT NOT NULL PRIMARY KEY, feed_id TEXT NOT NULL, guid TEXT NOT NULL,
                        url TEXT NOT NULL, title TEXT NOT NULL, summary TEXT, content TEXT, author TEXT,
                        published_at INTEGER, thumbnail_url TEXT, is_read INTEGER NOT NULL DEFAULT 0,
                        read_at INTEGER, is_starred INTEGER NOT NULL DEFAULT 0, starred_at INTEGER,
                        cached_at INTEGER NOT NULL, search_text TEXT NOT NULL DEFAULT '',
                        updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL,
                        deleted_at INTEGER, deleted_updated_at INTEGER,
                        UNIQUE (feed_id, guid)
                    )
                    """.trimIndent(),
                )
                st.execute(
                    """
                    CREATE TABLE feed_tags (
                        feed_id TEXT NOT NULL, tag_id TEXT NOT NULL, deleted_at INTEGER,
                        updated_at INTEGER NOT NULL, PRIMARY KEY (feed_id, tag_id)
                    )
                    """.trimIndent(),
                )
                st.execute(
                    "CREATE TABLE global_settings (key TEXT NOT NULL PRIMARY KEY, value TEXT NOT NULL, updated_at INTEGER NOT NULL)",
                )
                for (sql in insertSql) st.execute(sql)
            }
        }
        val bytes = file.readBytes()
        file.delete()
        return bytes
    }

    /** Two cloud `feeds` rows sharing a `url` — invalid only because the cloud DB's own `feeds`
     *  table (unlike this app's) carries no `UNIQUE(url)`, so both rows coexist there. */
    private fun cloudDbWithDuplicateFeedUrls(): ByteArray = cloudDbWithRelaxedFeedsSchema(
        feedsTableSql = """
            CREATE TABLE feeds (
                id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL, site_url TEXT, title TEXT NOT NULL,
                description TEXT, favicon_url TEXT, etag TEXT, last_modified TEXT,
                error_count INTEGER NOT NULL DEFAULT 0, last_error TEXT, custom_title TEXT, folder_id TEXT,
                deleted_at INTEGER, updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0, folder_updated_at INTEGER,
                sort_order_updated_at INTEGER, custom_title_updated_at INTEGER, deleted_updated_at INTEGER
            )
        """.trimIndent(),
        insertSql = listOf(
            "INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f-dup-1', 'https://dup.example/feed', 'Dup 1', 0, 0)",
            "INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f-dup-2', 'https://dup.example/feed', 'Dup 2', 0, 0)",
        ),
    )

    /** A cloud `feeds` row with a NULL `title` — invalid only against *this app's* `NOT NULL`
     *  constraint on `feeds.title` (the cloud DB's own `feeds.title` column allows NULL). */
    private fun cloudDbWithNotNullViolationOnFeedTitle(): ByteArray = cloudDbWithRelaxedFeedsSchema(
        feedsTableSql = """
            CREATE TABLE feeds (
                id TEXT NOT NULL PRIMARY KEY, url TEXT NOT NULL UNIQUE, site_url TEXT, title TEXT,
                description TEXT, favicon_url TEXT, etag TEXT, last_modified TEXT,
                error_count INTEGER NOT NULL DEFAULT 0, last_error TEXT, custom_title TEXT, folder_id TEXT,
                deleted_at INTEGER, updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL,
                sort_order INTEGER NOT NULL DEFAULT 0, folder_updated_at INTEGER,
                sort_order_updated_at INTEGER, custom_title_updated_at INTEGER, deleted_updated_at INTEGER
            )
        """.trimIndent(),
        insertSql = listOf(
            "INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f-null-title', 'https://null-title.example/feed', NULL, 0, 0)",
        ),
    )

    private fun tempCloudDbFile(): File = File(tempDir, "cloud_keryx.db")

    @Test
    fun secondSyncWithNothingChangedTransfersNothing() = runTest {
        // The background loop syncs on a timer, so the overwhelmingly common case is "neither side
        // changed". That must cost one metadata request and zero payload bytes, not a full
        // download + merge + upload of the entire database.
        val cloud = FakeCloudStorage()
        val repo = newRepo(cloud)
        assertIs<Result.Ok<Unit>>(repo.sync()) // first sync creates the cloud file
        val bytesAfterFirst = cloud.downloadedBytes + cloud.uploadedBytes
        assertTrue(bytesAfterFirst > 0)
        val downloadsAfterFirst = cloud.downloadCount
        val uploadsAfterFirst = cloud.uploadCount

        assertIs<Result.Ok<Unit>>(repo.sync())

        assertEquals(downloadsAfterFirst, cloud.downloadCount)
        assertEquals(uploadsAfterFirst, cloud.uploadCount)
        assertEquals(bytesAfterFirst, cloud.downloadedBytes + cloud.uploadedBytes)
        assertEquals(2, cloud.existsCount) // one metadata request per sync, and nothing else
    }

    @Test
    fun localChangeAfterAnUnchangedSyncIsStillUploaded() = runTest {
        // The counterpart to the skip: the digest must only match when the data really is the
        // same, so a local edit made after a skipped sync still reaches the cloud.
        val cloud = FakeCloudStorage()
        val repo = newRepo(cloud)
        assertIs<Result.Ok<Unit>>(repo.sync())
        assertIs<Result.Ok<Unit>>(repo.sync()) // skipped
        val uploadsBefore = cloud.uploadCount

        localDb.insertFeed("f2", now = 5)
        assertIs<Result.Ok<Unit>>(repo.sync())

        assertEquals(uploadsBefore + 1, cloud.uploadCount)
    }

    @Test
    fun anUnchangedSyncStillDoesNotDownloadAfterOwnUpload() = runTest {
        // The revision recorded after a successful upload must be the one that upload produced,
        // not the pre-upload revision — otherwise this device would download its own writes back
        // on the very next sync.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        val repo = newRepo(cloud)
        assertIs<Result.Ok<Unit>>(repo.sync()) // downloads r1, merges, uploads
        assertEquals(1, cloud.downloadCount)

        assertIs<Result.Ok<Unit>>(repo.sync())

        assertEquals(1, cloud.downloadCount)
        assertEquals(cloud.revOf(CLOUD_DB_PATH), localDb.sync_stateQueries.get(SYNC_STATE_CLOUD_FILE_REV).executeAsOneOrNull())
    }

    @Test
    fun aRemoteChangeIsStillDownloadedAndMerged() = runTest {
        // The skip is keyed on the revision, so another device's write must still be picked up.
        val cloud = FakeCloudStorage()
        val repo = newRepo(cloud)
        assertIs<Result.Ok<Unit>>(repo.sync())
        assertIs<Result.Ok<Unit>>(repo.sync()) // skipped
        assertEquals(0, cloud.downloadCount)

        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r-other-device")
        assertIs<Result.Ok<Unit>>(repo.sync())

        assertEquals(1, cloud.downloadCount)
        // A merge can leave local rows the cloud lacks, so it always re-uploads afterwards.
        assertEquals(1, cloud.uploadCount)
    }

    @Test
    fun clearSyncFailureStateForcesTheNextSyncToTransferAgain() = runTest {
        // Disconnecting tears down everything describing the current connection, the skip markers
        // included: they describe one provider's file, and carrying them into the next connection
        // could skip a download that was never merged.
        val cloud = FakeCloudStorage()
        val repo = newRepo(cloud)
        assertIs<Result.Ok<Unit>>(repo.sync())
        assertIs<Result.Ok<Unit>>(repo.sync()) // skipped: nothing changed
        val uploadsBefore = cloud.uploadCount
        val downloadsBefore = cloud.downloadCount

        repo.clearSyncFailureState()
        assertIs<Result.Ok<Unit>>(repo.sync())

        assertEquals(downloadsBefore + 1, cloud.downloadCount)
        assertEquals(uploadsBefore + 1, cloud.uploadCount)
    }

    @Test
    fun clearSyncFailureStateIsNotUndoneByAnInFlightSync() = runTest {
        // Disconnecting while a sync is already past its upload: without the shared mutex, that
        // sync's own setSyncState calls land *after* the clear and hand the next connection the old
        // provider's revision — which is exactly what makes a sync skip a download it never merged.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        val gate = CompletableDeferred<Unit>()
        cloud.downloadGate = gate
        val repo = newRepo(cloud)

        val syncing = launch { repo.sync() }
        runCurrent() // advance until sync() suspends inside the gated download, holding the mutex
        val clearing = launch { repo.clearSyncFailureState() }
        runCurrent() // the clear is now blocked on the mutex the sync holds
        gate.complete(Unit)
        syncing.join()
        clearing.join()

        cloud.downloadGate = null
        val downloadsBefore = cloud.downloadCount
        val uploadsBefore = cloud.uploadCount
        assertIs<Result.Ok<Unit>>(repo.sync())

        assertEquals(downloadsBefore + 1, cloud.downloadCount, "cleared revision must force a download")
        assertEquals(uploadsBefore + 1, cloud.uploadCount, "cleared digest must force an upload")
    }

    @Test
    fun clearSyncFailureStateIsNotUndoneByAFailingInFlightSync() = runTest {
        // The same race for the two StateFlows: emitErrorNotification() writes lastSyncError, so it
        // has to run inside the lock too, or a sync failing right after a disconnect leaves the
        // settings screen showing a failure reason for a provider that is no longer connected.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueUpload(Result.Err(CloudStorageException("boom")))
        val gate = CompletableDeferred<Unit>()
        cloud.downloadGate = gate
        val repo = newRepo(cloud)

        val syncing = launch { repo.sync() }
        runCurrent()
        val clearing = launch { repo.clearSyncFailureState() }
        runCurrent()
        gate.complete(Unit)
        syncing.join()
        clearing.join()

        assertNull(repo.lastSyncError.value, "a sync failing after the clear must not restore the reason")
    }

    @Test
    fun uploadSnapshotExcludesSyncStateSoItDoesNotDriftOnItsOwn() = runTest {
        // last_synced_at is rewritten on every successful sync. If sync_state rode along in the
        // uploaded snapshot, the bytes would differ every cycle and the skip could never fire.
        val cloud = FakeCloudStorage()
        val repo = newRepo(cloud)
        assertIs<Result.Ok<Unit>>(repo.sync())

        val uploaded = File(tempDir, "uploaded.db")
        uploaded.writeBytes(cloud.files.getValue(CLOUD_DB_PATH).first)
        DriverManager.getConnection("jdbc:sqlite:${uploaded.absolutePath}").use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='sync_state'",
                ).use { rs ->
                    rs.next()
                    assertEquals(0, rs.getInt(1))
                }
                // The synced tables are still there — only the device-local one was dropped.
                st.executeQuery(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type='table' AND name='feeds'",
                ).use { rs ->
                    rs.next()
                    assertEquals(1, rs.getInt(1))
                }
            }
        }
    }

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
        cloud.queueExists(Result.Ok(null))
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
    fun emptyCloudBytesAreRejectedBeforeTouchingTheMerger() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, byteArrayOf(), "r1")
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudDataIncompatibleException>(result.exception)
        assertEquals(0, cloud.uploadCount)
        assertFalse(tempCloudDbFile().exists())
    }

    @Test
    fun truncatedSqliteHeaderIsRejectedBeforeTouchingTheMerger() = runTest {
        val cloud = FakeCloudStorage()
        // The real 16-byte SQLite magic minus its last byte — one byte short is still not a match.
        val truncated = "SQLite format 3".encodeToByteArray()
        cloud.put(CLOUD_DB_PATH, truncated, "r1")
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudDataIncompatibleException>(result.exception)
        assertEquals(0, cloud.uploadCount)
        assertFalse(tempCloudDbFile().exists())
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
    fun duplicateUrlsInCloudFeedsIsReportedAsIncompatible() = runTest {
        // Two cloud feeds rows share a url — only possible because the cloud DB's own feeds table
        // (unlike main's) has no UNIQUE(url). Merging both into main's UNIQUE(url) column must be
        // classified as incompatible cloud data, not a transient/app-side failure.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbWithDuplicateFeedUrls(), "r1")
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudDataIncompatibleException>(result.exception)
        assertEquals(0, cloud.uploadCount)
        assertFalse(tempCloudDbFile().exists())
        val notes = notificationCenter.items.value
        assertEquals(1, notes.size)
        assertEquals(AppNotificationAction.ResetCloudData, notes.first().action)
    }

    @Test
    fun notNullViolationInCloudFeedsIsReportedAsIncompatible() = runTest {
        // A cloud feeds row has a NULL title — only possible because the cloud DB's own feeds.title
        // column (unlike main's) allows NULL. Merging it into main's NOT NULL column must be
        // classified as incompatible cloud data, not a transient/app-side failure.
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbWithNotNullViolationOnFeedTitle(), "r1")
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudDataIncompatibleException>(result.exception)
        assertEquals(0, cloud.uploadCount)
        assertFalse(tempCloudDbFile().exists())
        val notes = notificationCenter.items.value
        assertEquals(1, notes.size)
        assertEquals(AppNotificationAction.ResetCloudData, notes.first().action)
    }

    @Test
    fun postMergeIndexFailureIsNotClassifiedAsCloudDataIncompatible() = runTest {
        // ftsManager.indexMissing() runs *after* DatabaseMerger.merge() has already committed, so
        // a failure there is never the cloud's fault — it must fall to CloudStorageException, not
        // be (mis)classified as CloudDataIncompatibleException, even though its underlying SQLite
        // error code (SQLITE_ERROR, "no such table") is the same ambiguous code a broken cloud
        // schema would produce. FtsManager is a final class (cannot be faked), so dropping its
        // table locally is the only way to reach this path.
        localDriver.execute(null, "DROP TABLE articles_fts", 0)
        // A distinct feed id ("f2", not setUp()'s "f1") so a successful merge is observable.
        val (cloudFile, cloudDriver, cloudDb) = fileDb()
        cloudDb.insertFeed("f2", now = 0)
        cloudDriver.close()
        val cloudBytes = cloudFile.readBytes()
        cloudFile.delete()
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudBytes, "r1")
        val repo = newRepo(cloud)

        val result = repo.sync()

        assertIs<Result.Err>(result)
        assertIs<CloudStorageException>(result.exception)
        // The merge itself committed before indexMissing() failed: the cloud feed is now in main.
        assertNotNull(localDb.feedsQueries.getById("f2").executeAsOneOrNull())
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
    fun clearSyncFailureStateResetsLastSyncErrorToNull() = runTest {
        // Used when the connection that produced the error is torn down, so a subsequently-connected
        // provider does not inherit it (see SettingsViewModel.disconnect()/switchTo()).
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueDownload(Result.Err(CloudAuthException("no token")))
        val repo = newRepo(cloud)

        assertIs<Result.Err>(repo.sync())
        assertEquals("syncFailed:CloudAuthException", repo.lastSyncError.value)

        repo.clearSyncFailureState()

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
            scope = backgroundScope,
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
    fun resetCloudDataArchivesInsteadOfDeleting() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1") // a pre-existing (e.g. incompatible) cloud file
        val repo = newRepo(cloud, clockMillis = 55_000L)

        val result = repo.resetCloudData()

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(1, cloud.renameCount)
        assertEquals(0, cloud.deleteCount)
        assertEquals(1, cloud.createCount)
        assertEquals("/keryx-19700101-000055.db.bak", cloud.lastRenameTo)
        assertTrue(cloud.files.containsKey("/keryx-19700101-000055.db.bak"))
        assertTrue(cloud.files.containsKey(CLOUD_DB_PATH))
        assertEquals(55_000L, repo.lastSyncedAt())
        assertTrue(notificationCenter.items.value.isEmpty())
    }

    @Test
    fun resetCloudDataFallsBackToDeleteWhenArchiveFails() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueRename(Result.Err(CloudStorageException("destination exists")))
        val repo = newRepo(cloud)

        val result = repo.resetCloudData()

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(1, cloud.renameCount)
        assertEquals(1, cloud.deleteCount)
        assertEquals(1, cloud.createCount)
        assertTrue(notificationCenter.items.value.isEmpty())
    }

    @Test
    fun resetCloudDataSkipsTheDeleteFallbackOnAuthFailure() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueRename(Result.Err(CloudAuthException("revoked")))
        val repo = newRepo(cloud)

        val result = repo.resetCloudData()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
        assertEquals(1, cloud.renameCount)
        assertEquals(0, cloud.deleteCount)
        assertEquals(0, cloud.createCount)
        val notes = notificationCenter.items.value
        assertEquals(1, notes.size)
        assertEquals(AppNotificationLevel.ERROR, notes.first().level)
    }

    @Test
    fun resetCloudDataFailsWhenArchiveAndDeleteBothFail() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueRename(Result.Err(CloudStorageException("destination exists")))
        cloud.queueDelete(Result.Err(CloudAuthException("revoked")))
        val repo = newRepo(cloud)

        val result = repo.resetCloudData()

        assertIs<Result.Err>(result)
        assertIs<CloudAuthException>(result.exception)
        assertEquals(0, cloud.createCount)
        val notes = notificationCenter.items.value
        assertEquals(1, notes.size)
        assertEquals(AppNotificationLevel.ERROR, notes.first().level)
    }

    @Test
    fun resetCloudDataOnAnAbsentCloudFileStillRecreates() = runTest {
        val cloud = FakeCloudStorage() // no pre-existing cloud file
        val repo = newRepo(cloud, clockMillis = 55_000L)

        val result = repo.resetCloudData()

        assertIs<Result.Ok<Unit>>(result)
        assertEquals(1, cloud.renameCount) // idempotent no-op on an absent source
        assertEquals(1, cloud.createCount)
        assertTrue(cloud.files.containsKey(CLOUD_DB_PATH))
    }

    @Test
    fun resetCloudDataSurfacesErrorWhenCreateFreshFailsAfterSuccessfulArchive() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r1")
        cloud.queueCreate(Result.Err(CloudStorageException("quota exceeded")))
        val repo = newRepo(cloud, clockMillis = 55_000L)

        val result = repo.resetCloudData()

        assertIs<Result.Err>(result)
        // The archive succeeded (no data lost) even though create-fresh then failed. The absent
        // CLOUD_DB_PATH means the next ordinary sync's createFresh fallback (syncLocked) recovers.
        assertTrue(cloud.files.containsKey("/keryx-19700101-000055.db.bak"))
        assertFalse(cloud.files.containsKey(CLOUD_DB_PATH))
    }

    @Test
    fun automaticSyncIsSuspendedAfterCloudDataIncompatible() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, byteArrayOf(1, 2, 3, 4), "r1") // not a SQLite file
        val repo = newRepo(cloud)

        val first = repo.sync(SyncTrigger.AUTOMATIC)
        assertIs<Result.Err>(first)
        assertIs<CloudDataIncompatibleException>(first.exception)
        assertEquals(1, cloud.downloadCount)
        assertTrue(repo.autoSyncSuspended.value)

        val second = repo.sync(SyncTrigger.AUTOMATIC)
        assertIs<Result.Ok<Unit>>(second)
        assertEquals(1, cloud.downloadCount) // no second download attempt
        // Neither the notification nor the settings-tab failure reason is disturbed by the skip.
        assertEquals(1, notificationCenter.items.value.size)
        assertNotNull(repo.lastSyncError.value)
    }

    @Test
    fun manualSyncIsNeverSuspended() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, byteArrayOf(1, 2, 3, 4), "r1")
        val repo = newRepo(cloud)
        repo.sync(SyncTrigger.AUTOMATIC)
        assertTrue(repo.autoSyncSuspended.value)

        val manual = repo.sync(SyncTrigger.MANUAL)

        assertIs<Result.Err>(manual)
        assertEquals(2, cloud.downloadCount) // a real second attempt, not skipped
    }

    @Test
    fun scheduleSyncIsSuppressedWhileCloudDataIsUnusable() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, byteArrayOf(1, 2, 3, 4), "r1")
        val repo = newRepo(cloud)
        repo.sync(SyncTrigger.AUTOMATIC)
        assertTrue(repo.autoSyncSuspended.value)

        repo.scheduleSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()

        assertEquals(1, cloud.existsCount) // only the initial sync() call's own exists() check
    }

    @Test
    fun resetCloudDataResumesAutomaticSync() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, byteArrayOf(1, 2, 3, 4), "r1")
        val repo = newRepo(cloud, clockMillis = 55_000L)
        repo.sync(SyncTrigger.AUTOMATIC)
        assertTrue(repo.autoSyncSuspended.value)

        assertIs<Result.Ok<Unit>>(repo.resetCloudData())
        assertFalse(repo.autoSyncSuspended.value)

        // Another device writes, so the resumed sync has a genuine reason to download: an
        // unchanged revision would (correctly) be skipped and prove nothing about the gate.
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r99")
        val after = repo.sync(SyncTrigger.AUTOMATIC)
        assertIs<Result.Ok<Unit>>(after) // ran for real (fresh cloud data merges cleanly)
        assertEquals(2, cloud.downloadCount)
    }

    @Test
    fun successfulManualSyncResumesAutomaticSync() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, byteArrayOf(1, 2, 3, 4), "r1")
        val repo = newRepo(cloud)
        repo.sync(SyncTrigger.AUTOMATIC)
        assertTrue(repo.autoSyncSuspended.value)

        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r2") // the cloud data gets fixed
        assertIs<Result.Ok<Unit>>(repo.sync(SyncTrigger.MANUAL))
        assertFalse(repo.autoSyncSuspended.value)

        // Another device writes, so the resumed sync has a genuine reason to download: an
        // unchanged revision would (correctly) be skipped and prove nothing about the gate.
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r99")
        val after = repo.sync(SyncTrigger.AUTOMATIC)
        assertIs<Result.Ok<Unit>>(after)
        assertEquals(3, cloud.downloadCount)
    }

    @Test
    fun clearSyncFailureStateResumesAutomaticSync() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, byteArrayOf(1, 2, 3, 4), "r1")
        val repo = newRepo(cloud)
        repo.sync(SyncTrigger.AUTOMATIC)
        assertTrue(repo.autoSyncSuspended.value)

        repo.clearSyncFailureState()
        assertFalse(repo.autoSyncSuspended.value)

        cloud.put(CLOUD_DB_PATH, cloudDbBytes(), "r2")
        val after = repo.sync(SyncTrigger.AUTOMATIC)
        assertIs<Result.Ok<Unit>>(after) // gate no longer blocks it
        assertEquals(2, cloud.downloadCount)
    }

    @Test
    fun schemaVersionExceptionDoesNotSuspendAutomaticSync() = runTest {
        val cloud = FakeCloudStorage()
        cloud.put(CLOUD_DB_PATH, cloudDbBytes(userVersion = 999L), "r1")
        val repo = newRepo(cloud)

        val first = repo.sync(SyncTrigger.AUTOMATIC)
        assertIs<Result.Err>(first)
        assertIs<SchemaVersionException>(first.exception)
        assertFalse(repo.autoSyncSuspended.value)

        val second = repo.sync(SyncTrigger.AUTOMATIC)
        assertIs<Result.Err>(second)
        assertEquals(2, cloud.downloadCount) // both attempts ran for real, never gated
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

    /**
     * A write burst — what a feed refresh produces, one `scheduleSync()` per feed, from a different
     * dispatcher than the UI edits and mark-as-read writes that also call it — must still collapse to
     * a single sync. The scheduler holds no cancellable `Job` field for those callers to race over.
     */
    @Test
    fun scheduleSyncCollapsesAWriteBurstIntoOneSync() = runTest {
        val cloud = FakeCloudStorage()
        val repo = newRepo(cloud)

        repeat(50) { repo.scheduleSync() }
        runCurrent()
        assertEquals(0, cloud.existsCount, "nothing should run before the debounce window elapses")

        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(1, cloud.existsCount, "50 scheduleSync calls must produce exactly one sync")

        // And the scheduler still works after the burst has drained.
        repo.scheduleSync()
        advanceTimeBy(SYNC_DEBOUNCE_MS * 2)
        runCurrent()
        assertEquals(2, cloud.existsCount)
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
