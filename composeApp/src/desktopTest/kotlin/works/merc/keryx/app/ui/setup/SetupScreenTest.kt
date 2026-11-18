package works.merc.keryx.app.ui.setup

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToLog
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import androidx.lifecycle.viewModelScope
import app.cash.sqldelight.db.SqlDriver
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.koin.compose.KoinApplication
import org.koin.dsl.koinConfiguration
import org.koin.dsl.module
import works.merc.keryx.app.FakeCloudConnectFlow
import works.merc.keryx.app.FakeTokenStorage
import works.merc.keryx.app.SuspendingCloudConnectFlow
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.CloudConnectionService
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.ui.home.testMenuController
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.presentation.setup.SetupController
import works.merc.keryx.app.presentation.setup.SetupPhase
import works.merc.keryx.app.singleProviderCloudSession
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals

/** [SetupScreen]'s idle state: the local-only card, and the cloud card when this build configures a provider. */
@OptIn(ExperimentalTestApi::class, kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class SetupScreenTest {

    private lateinit var driver: SqlDriver
    private lateinit var db: KeryxDatabase
    private val dir = FileIO.join(AppDirs.tempDir(), "setup-screen-test-${Random.nextInt()}")
    private val scopes = mutableListOf<CoroutineScope>()
    private val controllers = mutableListOf<SetupController>()

    @BeforeTest
    fun setUp() {
        val (d, database) = inMemoryDb()
        driver = d
        db = database
    }

    @AfterTest
    fun tearDown() {
        controllers.forEach { it.viewModelScope.cancel() }
        scopes.forEach { it.cancel() }
        driver.close()
        FileIO.delete(FileIO.join(dir, "local_settings.json"))
    }

    private fun newController(suspendConnect: Boolean = false): SetupController {
        val clock = Clock { 0L }
        val settingsRepository = SettingsRepository(
            db, LocalSettingsStore(dirOverride = dir), SyncScheduler {}, clock, writeDispatcher = Dispatchers.Unconfined,
        )
        val syncScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        val syncRepository = SyncRepository(
            driver = driver, db = db, ftsManager = FtsManager(driver), cloudProvider = { null }, clock = clock,
            scope = syncScope, activityCenter = ActivityCenter(), notificationCenter = NotificationCenter(),
            localDbPath = "unused", tempDir = "unused",
        )
        val client = HttpClient(MockEngine { respond("{}", HttpStatusCode.OK) }) { expectSuccess = false }
        val cloudSession = singleProviderCloudSession(
            client = client,
            tokenStorage = FakeTokenStorage(),
            authManager = DropboxAuthManager(client, clock = clock),
            clock = clock,
            connectFlow = if (suspendConnect) SuspendingCloudConnectFlow() else FakeCloudConnectFlow(),
        )
        return SetupController(
            settingsRepository, cloudSession, syncRepository,
            CloudConnectionService(cloudSession, settingsRepository, syncRepository), Dispatchers.Unconfined,
        ).also { controllers += it }
    }

    @Test
    fun idleScreenOffersLocalOnlyAndTheConfiguredCloudProviders() = runDesktopComposeUiTest {
        val controller = newController()
        setContent {
            // MenuController too: other tests' native popups look it up in the global Koin that the
            // last KoinApplication left behind, and this one would otherwise replace it without it.
            KoinApplication(
                configuration = koinConfiguration { modules(module { single { controller }; single { testMenuController } }) },
            ) {
                MaterialTheme { SetupScreen(onComplete = {}) }
            }
        }
        waitForIdle()
        onNodeWithText("ローカルのみで使う").assertIsDisplayed()
        onNodeWithText("開始").assertIsEnabled()
        // The cloud card is shown exactly when this build configures at least one provider.
        assertEquals(controller.availableCloudTypes.isNotEmpty(), onAllNodesWithText("クラウドストレージで同期する").fetchSemanticsNodes().isNotEmpty())
        onAllNodesWithText("中止").assertCountEquals(0)
        assertEquals(SetupPhase.IDLE, controller.phase.value)
    }
}
