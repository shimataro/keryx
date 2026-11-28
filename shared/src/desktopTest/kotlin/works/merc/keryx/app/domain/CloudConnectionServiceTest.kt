package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_STATE_KS_DEVICE_ID
import works.merc.keryx.app.core.SYNC_STATE_KS_GENERATION
import works.merc.keryx.app.core.SYNC_STATE_KS_LAST_SEQ
import works.merc.keryx.app.data.cloud.CloudFileEntry
import works.merc.keryx.app.data.cloud.CloudFileMeta
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.LocalSettings
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.singleProviderCloudSession
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** A [CloudStorage] whose every call fails authentication — drives a sync into its auth-failed state. */
private class AuthFailingCloudStorage : CloudStorage {
    private fun fail(): Result.Err = Result.Err(CloudAuthException("token rejected"))
    override suspend fun authenticate(): Result<Unit> = fail()
    override suspend fun download(path: String, destPath: String): Result<CloudFileMeta> = fail()
    override suspend fun upload(path: String, sourcePath: String, expectedRev: String?): Result<CloudFileMeta> = fail()
    override suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta> = fail()
    override suspend fun delete(path: String): Result<Unit> = fail()
    override suspend fun rename(from: String, to: String): Result<Unit> = fail()
    override suspend fun metadata(path: String): Result<CloudFileMeta?> = fail()
    override suspend fun list(prefix: String): Result<List<CloudFileEntry>> = fail()
}

/** A [CloudStorage] whose first call suspends until [gate] completes, holding the sync's lock meanwhile. */
private class GatedCloudStorage(private val delegate: CloudStorage) : CloudStorage by delegate {
    val gate = CompletableDeferred<Unit>()
    val entered = CompletableDeferred<Unit>()
    private suspend fun hold() {
        entered.complete(Unit)
        gate.await()
    }
    override suspend fun authenticate(): Result<Unit> { hold(); return delegate.authenticate() }
    override suspend fun metadata(path: String): Result<CloudFileMeta?> { hold(); return delegate.metadata(path) }
}

class CloudConnectionServiceTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "cloud-connection-test-${Random.nextInt()}")
    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)

    @BeforeTest
    fun setUp() {
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    @AfterTest
    fun tearDown() {
        syncScope.cancel()
        driver.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    // Unconfined write dispatcher so flush() writes inline and store.load() reflects it at once.
    private fun settingsRepository(database: KeryxDatabase = db): SettingsRepository =
        SettingsRepository(database, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined)

    private fun syncRepository(cloud: CloudStorage?): SyncRepository = SyncRepository(
        driver = driver,
        db = db,
        ftsManager = FtsManager(driver),
        cloudProvider = { cloud },
        clock = Clock { 0L },
        scope = syncScope,
        activityCenter = ActivityCenter(),
        notificationCenter = NotificationCenter(),
        localDbPath = "unused",
        tempDir = "unused",
    )

    private fun cloudSession(tokenStorage: FakeTokenStorage, settings: SettingsRepository): CloudSession {
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
        return singleProviderCloudSession(
            client = client,
            tokenStorage = tokenStorage,
            authManager = DropboxAuthManager(client, clock = Clock { 0L }),
            // Mirrors production: the selected provider is whatever local settings say.
            selectedType = { CloudStorageType.fromId(settings.getLocalSettings().cloudStorageType) },
        )
    }

    @Test
    fun completeConnectSavesTokensAndFlushesTheProviderSelectionToDisk() = runTest {
        val tokenStorage = FakeTokenStorage()
        val settings = settingsRepository()
        val session = cloudSession(tokenStorage, settings)
        val service = CloudConnectionService(session, settings, syncRepository(null))
        // Stop the coalesced background writer, so only an explicit flush() can reach the disk:
        // the file below then proves completeConnect flushed rather than left it to that writer.
        settings.close()
        val store = LocalSettingsStore(dirOverride = dir)
        assertNull(store.load().cloudStorageType)

        service.completeConnect(CloudStorageType.DROPBOX, OAuthTokens("AT", "RT"))

        assertEquals(OAuthTokens("AT", "RT"), tokenStorage.load())
        assertEquals("dropbox", settings.getLocalSettings().cloudStorageType)
        assertEquals("dropbox", store.load().cloudStorageType, "provider selection must be on disk on return")
        assertEquals(CloudStorageType.DROPBOX, session.connectedType())
    }

    @Test
    fun tearDownDisconnectsClearsTheSyncFailureStateAndTheProviderSelection() = runTest {
        val tokenStorage = FakeTokenStorage(OAuthTokens("AT", "RT"))
        LocalSettingsStore(dirOverride = dir).save(LocalSettings(cloudStorageType = "dropbox"))
        val settings = settingsRepository()
        val session = cloudSession(tokenStorage, settings)
        val sync = syncRepository(AuthFailingCloudStorage())
        val service = CloudConnectionService(session, settings, sync)
        // Leave behind what a failing connection accumulates: an auth failure and a sync identity.
        assertIs<Result.Err>(sync.sync())
        assertTrue(sync.lastSyncAuthFailed.value)
        assertNotNull(sync.lastSyncError.value)
        db.sync_stateQueries.upsert(SYNC_STATE_KS_DEVICE_ID, "0f0f0f0f-0000-4000-8000-000000000001")
        db.sync_bookkeepingQueries.setCapture(enabled = 1, fullPending = 1)

        service.tearDown(CloudStorageType.DROPBOX)

        assertNull(tokenStorage.load())
        assertNull(session.connectedType())
        assertFalse(sync.lastSyncAuthFailed.value)
        assertNull(sync.lastSyncError.value)
        assertNull(db.sync_stateQueries.get(SYNC_STATE_KS_DEVICE_ID).executeAsOneOrNull())
        assertEquals(0L, db.sync_bookkeepingQueries.captureState().executeAsOne().enabled)
        assertNull(settings.getLocalSettings().cloudStorageType)
    }

    /**
     * A connect after a teardown that never finished (or with no teardown at all) must not inherit
     * the old generation: with `ks_generation` and `ks_last_seq >= 1` left behind, the first sync
     * against an empty folder would only create a pointer to that generation and never send a base.
     */
    @Test
    fun completeConnectClearsLeftoverSyncStateSoTheFirstSyncSendsAFullBase() = runTest {
        val cloud = FakeCloudStorage()
        val device = SyncDevice("a", cloud, Clock { 1_000L }, backgroundScope, idSource(Random(7)))
        try {
            val oldGeneration = "0f0f0f0f-0000-4000-8000-0000000000aa"
            device.db.sync_stateQueries.upsert(SYNC_STATE_KS_DEVICE_ID, "0f0f0f0f-0000-4000-8000-0000000000bb")
            device.db.sync_stateQueries.upsert(SYNC_STATE_KS_GENERATION, oldGeneration)
            device.db.sync_stateQueries.upsert(SYNC_STATE_KS_LAST_SEQ, "1")
            device.db.sync_bookkeepingQueries.setCapture(enabled = 1, fullPending = 0)
            device.driver.execute(null, "INSERT INTO feeds (id, url, title, updated_at, created_at) VALUES ('f1', 'https://ex.com/f1', 'F1', 1, 1)", 0)
            val settings = settingsRepository(device.db)
            val service = CloudConnectionService(cloudSession(FakeTokenStorage(), settings), settings, device.repo)

            service.completeConnect(CloudStorageType.DROPBOX, OAuthTokens("AT", "RT"))
            assertIs<Result.Ok<Unit>>(device.repo.sync())

            val names = cloud.transportNames()
            val newGeneration = device.state(SYNC_STATE_KS_GENERATION)
            assertNotNull(newGeneration)
            assertTrue(newGeneration != oldGeneration, "must join a fresh generation, not point at the old one")
            assertTrue(names.none { it.generation == oldGeneration }, "nothing may be sent into the old generation: $names")
            assertTrue(
                names.any { it.kind == TransportFileKind.BASE && it.generation == newGeneration },
                "the first sync must send a full base: $names",
            )
        } finally {
            device.close()
        }
    }

    /**
     * Forced interleaving: a sync holds the sync lock (its cloud call is parked on a gate), the
     * teardown is cancelled while it waits for that lock, and only then is the sync released. The
     * teardown must still clear the connection state and the provider selection.
     */
    @Test
    fun tearDownCancelledWhileWaitingForTheSyncLockStillFinishes() = runTest {
        val tokenStorage = FakeTokenStorage(OAuthTokens("AT", "RT"))
        LocalSettingsStore(dirOverride = dir).save(LocalSettings(cloudStorageType = "dropbox"))
        val settings = settingsRepository()
        val session = cloudSession(tokenStorage, settings)
        val cloud = GatedCloudStorage(FakeCloudStorage())
        val sync = syncRepository(cloud)
        val service = CloudConnectionService(session, settings, sync)
        db.sync_stateQueries.upsert(SYNC_STATE_KS_DEVICE_ID, "0f0f0f0f-0000-4000-8000-000000000001")
        db.sync_bookkeepingQueries.setCapture(enabled = 1, fullPending = 1)

        val running = syncScope.async { sync.sync() }
        withContext(Dispatchers.Default) { withTimeout(5_000) { cloud.entered.await() } }

        val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val teardown = teardownScope.launch { service.tearDown(CloudStorageType.DROPBOX) }
        // The teardown deletes the tokens first, then waits for the lock the parked sync holds.
        withContext(Dispatchers.Default) { withTimeout(5_000) { while (tokenStorage.load() != null) delay(1) } }
        teardown.cancel()
        assertFalse(teardown.isCompleted, "the teardown cannot finish while the sync holds the lock")

        cloud.gate.complete(Unit)
        withContext(Dispatchers.Default) {
            withTimeout(10_000) {
                running.await()
                teardown.join()
            }
        }
        teardownScope.cancel()

        assertNull(tokenStorage.load())
        assertNull(db.sync_stateQueries.get(SYNC_STATE_KS_DEVICE_ID).executeAsOneOrNull())
        assertEquals(0L, db.sync_bookkeepingQueries.captureState().executeAsOne().enabled)
        assertNull(settings.getLocalSettings().cloudStorageType)
        assertNull(session.connectedType())
    }
}
