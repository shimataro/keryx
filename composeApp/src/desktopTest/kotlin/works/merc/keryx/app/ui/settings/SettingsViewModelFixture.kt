package works.merc.keryx.app.ui.settings

import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.plugins.HttpTimeout
import io.ktor.http.HttpStatusCode
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.job
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.withTimeout
import org.jetbrains.compose.resources.getString
import works.merc.keryx.app.FakeCloudConnectFlow
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.HoldingDispatcher
import works.merc.keryx.app.InMemoryFaviconMissStore
import works.merc.keryx.app.awaitConditionBlocking
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudStorageAvailability
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.opml.OpmlCodec
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.data.remote.UpdateDownloader
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.CloudConnectFlow
import works.merc.keryx.app.domain.CloudConnectionService
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.InstallLaunchResult
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.UpdateRepository
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.ftsManagerIndexed
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFeed
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.FileSelector
import works.merc.keryx.app.platform.OpenFileRequest
import works.merc.keryx.app.platform.PathPickedFile
import works.merc.keryx.app.platform.PickedFile
import works.merc.keryx.app.platform.SaveFileRequest
import works.merc.keryx.app.presentation.settings.CloudSyncController
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import works.merc.keryx.app.presentation.settings.OpmlOperation
import works.merc.keryx.app.presentation.settings.OpmlRequest
import works.merc.keryx.app.presentation.settings.OpmlResult
import works.merc.keryx.app.presentation.settings.OpmlTransfer
import works.merc.keryx.app.presentation.settings.OpmlTransferController
import works.merc.keryx.app.presentation.settings.PreferencesController
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.settings_export_opml
import works.merc.keryx.app.singleProviderCloudSession
import kotlin.coroutines.CoroutineContext
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

/**
 * Builds a [SettingsViewModel] over a real in-memory DB and inert fakes. Shared by the settings tests;
 * call [cancelWork] before closing the driver (the ViewModels' scopes are not tied to the test's scope)
 * and [closeStorage] last.
 */
@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SettingsViewModelFixture {

    val driver: SqlDriver
    val db: KeryxDatabase
    val dir = FileIO.join(AppDirs.tempDir(), "settings-vm-test-${Random.nextInt()}")

    // ViewModels created via newViewModel(). Their viewModelScope is not tied to runTest's scope,
    // so it must be cancelled explicitly before driver.close() — otherwise in-flight coroutines
    // outlive the test and can throw against the closed driver, surfacing (flakily, on another
    // test) as kotlinx.coroutines.test.UncaughtExceptionsBeforeTest. The wrapped CloudSyncController
    // has its own separate viewModelScope and must be cancelled too.
    private val createdViewModels = mutableListOf<SettingsViewModel>()
    private val createdCloudSyncControllers = mutableListOf<CloudSyncController>()
    private val createdSyncScopes = mutableListOf<CoroutineScope>()

    /** The [OpmlTransferController] behind the last [newViewModel] — what the File menu would call. */
    lateinit var lastOpmlController: OpmlTransferController

    /** The app scope behind [lastOpmlController], where its imports run. */
    lateinit var lastOpmlScope: CoroutineScope

    init {
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    /** Cancels every scope the fixture handed out. Call before [closeStorage]. */
    fun cancelWork() {
        runBlocking {
            createdViewModels.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            createdCloudSyncControllers.forEach { it.viewModelScope.coroutineContext.job.cancelAndJoin() }
            createdSyncScopes.forEach { it.coroutineContext.job.cancelAndJoin() }
        }
        createdViewModels.clear()
        createdCloudSyncControllers.clear()
        createdSyncScopes.clear()
    }

    fun closeStorage() {
        driver.close()
        File(dir).deleteRecursively()
    }

    fun failingFetcher(): FeedFetcher {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    /** A [FeedFetcher] that answers every request with a minimal valid feed, for OPML import tests. */
    fun succeedingFetcher(): FeedFetcher {
        val rss = """<?xml version="1.0"?><rss version="2.0"><channel>
            <title>Feed</title><link>https://ex.com</link>
            <item><title>Post</title><link>https://ex.com/1</link><guid>g1</guid></item>
            </channel></rss>"""
        val client = HttpClient(MockEngine { respond(rss, HttpStatusCode.OK) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    /**
     * [succeedingFetcher], with every response held until [gate] completes; [started] completes once
     * a request has reached it.
     */
    fun gatedSucceedingFetcher(gate: CompletableDeferred<Unit>, started: CompletableDeferred<Unit>? = null): FeedFetcher {
        val rss = """<?xml version="1.0"?><rss version="2.0"><channel>
            <title>Feed</title><link>https://ex.com</link>
            </channel></rss>"""
        val client = HttpClient(
            MockEngine {
                started?.complete(Unit)
                gate.await()
                respond(rss, HttpStatusCode.OK)
            },
        ) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FeedFetcher(client)
    }

    fun missingFaviconResolver(): FaviconResolver {
        val client = HttpClient(MockEngine { respond("", HttpStatusCode.NotFound) }) {
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout)
        }
        return FaviconResolver(client)
    }

    fun updateCheckerReturning(tagName: String): UpdateChecker {
        // currentVersion 1.0.0 is stable, so the checker queries releases/latest (a single object).
        val client = HttpClient(
            MockEngine { respond("""{"tag_name":"$tagName","html_url":"https://ex.com/releases/$tagName","prerelease":false,"draft":false}""", HttpStatusCode.OK) },
        ) { expectSuccess = false }
        return UpdateChecker(client, currentVersion = "1.0.0", repoSlug = "owner/repo")
    }

    /**
     * Wraps [checker] in a real [UpdateRepository] so [SettingsViewModel.checkForUpdate] exercises
     * the same code path production does — only [downloader]/[installer] are inert stand-ins,
     * since these tests only exercise the check/result path, never an actual download or install.
     */
    fun fakeUpdateRepository(checker: UpdateChecker): UpdateRepository {
        val unusedClient = HttpClient(MockEngine { respondError(HttpStatusCode.NotImplemented) }) { expectSuccess = false }
        val installer = object : UpdateInstaller {
            override fun canInstall(plan: UpdatePlan) = false
            override suspend fun install(filePath: String, update: AvailableUpdate) =
                InstallLaunchResult.Failed("not used in this test")
        }
        return UpdateRepository(
            checker = checker,
            downloader = UpdateDownloader(unusedClient),
            installer = installer,
            notificationCenter = NotificationCenter(),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { createdSyncScopes += it },
        )
    }

    fun newViewModel(
        updateRepository: UpdateRepository = fakeUpdateRepository(updateCheckerReturning("1.0.0")),
        // Only the OPML import tests need subscribeFeed to actually succeed.
        feedFetcher: FeedFetcher = failingFetcher(),
        // Default cancels every OPML pick — a test exercising import/export supplies its own.
        fileSelector: FileSelector = FakeFileSelector(),
        // Passed to the VM's own `dispatcher` (blocking OPML build/write/import work). Default
        // matches the rest of newViewModel's Unconfined setup; a test can supply a CountingDispatcher
        // to assert it was actually used.
        dispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        // Where the OpmlTransferController runs an import; a test can supply a HoldingDispatcher to
        // keep a cancelled import from stopping until it says so.
        opmlImportDispatcher: CoroutineDispatcher = Dispatchers.Unconfined,
        // The one provider the cloud session offers (Dropbox unless a test needs another row).
        cloudType: CloudStorageType = CloudStorageType.DROPBOX,
        // What connecting that provider does; the default completes with a token at once.
        connectFlow: CloudConnectFlow = FakeCloudConnectFlow(Result.Ok(OAuthTokens("AT"))),
        // The storage syncs run against; null (the default) means no provider, so a sync is a no-op.
        cloudStorage: CloudStorage? = null,
        // The providers the Cloud Sync tab lists; defaults to whatever this build configures.
        availableCloudTypes: List<CloudStorageType> = CloudStorageAvailability.available,
    ): SettingsViewModel {
        val clock = Clock { 0L }
        val syncScheduler = SyncScheduler {}
        val articleRepository = ArticleRepository(db, FtsSearch(driver), syncScheduler, clock, Dispatchers.Unconfined)
        // Mirror startup: ensureIndexed() creates articles_fts so subscribeFeed's indexMissing() works.
        val ftsManager = ftsManagerIndexed(driver)
        val feedRepository = FeedRepository(
            db, feedFetcher, missingFaviconResolver(), articleRepository, ftsManager, syncScheduler,
            NotificationCenter(), clock, Dispatchers.Unconfined,
            faviconMisses = InMemoryFaviconMissStore(),
            driver = driver,
        )
        val folderRepository = FolderRepository(db, feedRepository, syncScheduler, clock, Dispatchers.Unconfined)
        val tagRepository = TagRepository(db, syncScheduler, clock, Dispatchers.Unconfined)
        val opmlImporter = OpmlImporter(feedRepository, folderRepository, tagRepository)
        val opmlTransfer = OpmlTransfer(feedRepository, folderRepository, tagRepository, opmlImporter)
        // Stands in for the app scope the controller runs imports on.
        val opmlScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also {
            createdSyncScopes += it
            lastOpmlScope = it
        }
        val opmlController = OpmlTransferController(opmlTransfer, opmlScope, opmlImportDispatcher).also { lastOpmlController = it }
        // Unconfined write dispatcher so saveLocalSettings persists inline.
        val settingsRepository =
            SettingsRepository(db, LocalSettingsStore(dirOverride = dir), syncScheduler, clock, writeDispatcher = Dispatchers.Unconfined)
        val preferencesController = PreferencesController(settingsRepository)
        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        createdSyncScopes += syncScope
        val syncRepository = SyncRepository(
            driver = driver,
            db = db,
            ftsManager = FtsManager(driver),
            cloudProvider = { cloudStorage },
            clock = clock,
            scope = syncScope,
            activityCenter = ActivityCenter(),
            notificationCenter = NotificationCenter(),
            localDbPath = "unused",
            tempDir = "unused",
        )
        val authClient = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
        val authManager = DropboxAuthManager(authClient, clock = clock)
        val cloudSession = singleProviderCloudSession(
            client = authClient,
            tokenStorage = FakeTokenStorage(),
            authManager = authManager,
            clock = clock,
            type = cloudType,
            connectFlow = connectFlow,
        )
        val cloudSyncController = CloudSyncController(
            cloudSession, syncRepository, CloudConnectionService(cloudSession, settingsRepository, syncRepository), ActivityCenter(),
            settingsRepository,
            // Unconfined so connectDelegatesToCloudSyncController's testScheduler.advanceUntilIdle()
            // actually observes the token-save/sync hop, rather than it landing on a real thread.
            Dispatchers.Unconfined,
            availableCloudTypes = availableCloudTypes,
        ).also { createdCloudSyncControllers += it }
        return SettingsViewModel(
            cloudSyncController, preferencesController, opmlController, updateRepository, dispatcher, fileSelector,
        ).also { createdViewModels += it }
    }
}

/**
 * A [FileSelector] fake: hands back a handle to a fixed path (or null, i.e. "cancelled") and records
 * what it was asked for. The handle is the production [PathPickedFile], so reads and writes still go
 * to a real file on disk — which is what these tests assert on.
 */
internal class FakeFileSelector(
    private val openPath: String? = null,
    private val savePath: String? = null,
) : FileSelector {
    var lastOpenRequest: OpenFileRequest? = null
        private set
    var lastSaveRequest: SaveFileRequest? = null
        private set

    var openCount = 0
        private set

    override suspend fun pickOpenFile(request: OpenFileRequest): PickedFile? {
        lastOpenRequest = request
        openCount++
        return openPath?.let(::PathPickedFile)
    }

    override suspend fun pickSaveFile(request: SaveFileRequest): PickedFile? {
        lastSaveRequest = request
        return savePath?.let(::PathPickedFile)
    }
}
