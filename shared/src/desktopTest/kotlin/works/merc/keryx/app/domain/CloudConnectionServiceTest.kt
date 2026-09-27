package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_STATE_CLOUD_FILE_REV
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
    private fun settingsRepository(): SettingsRepository =
        SettingsRepository(db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, Clock { 0L }, writeDispatcher = Dispatchers.Unconfined)

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
        // Leave behind what a failing connection accumulates: an auth failure and a stale revision.
        assertIs<Result.Err>(sync.sync())
        assertTrue(sync.lastSyncAuthFailed.value)
        assertNotNull(sync.lastSyncError.value)
        db.sync_stateQueries.upsert(SYNC_STATE_CLOUD_FILE_REV, "rev-1")

        service.tearDown(CloudStorageType.DROPBOX)

        assertNull(tokenStorage.load())
        assertNull(session.connectedType())
        assertFalse(sync.lastSyncAuthFailed.value)
        assertNull(sync.lastSyncError.value)
        assertEquals("", db.sync_stateQueries.get(SYNC_STATE_CLOUD_FILE_REV).executeAsOneOrNull())
        assertNull(settings.getLocalSettings().cloudStorageType)
    }
}
