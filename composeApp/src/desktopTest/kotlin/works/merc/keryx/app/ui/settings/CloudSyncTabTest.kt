package works.merc.keryx.app.ui.settings

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runDesktopComposeUiTest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.CloudCredentials
import works.merc.keryx.app.data.cloud.CloudFileEntry
import works.merc.keryx.app.data.cloud.CloudFileMeta
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.domain.CloudConnectFlow
import works.merc.keryx.app.domain.CloudConnectRequest

/** Completes a connect at once and counts how many times the provider's flow was started. */
private class CountingConnectFlow : CloudConnectFlow {
    val calls = AtomicInteger()
    override suspend fun connect(request: CloudConnectRequest): Result<CloudCredentials> {
        calls.incrementAndGet()
        return Result.Ok(OAuthTokens("AT"))
    }
}

/** A [CloudStorage] whose every operation fails with an auth error, so a sync leaves `lastSyncAuthFailed` set. */
private class AuthFailingCloudStorage : CloudStorage {
    private fun <T> fail(): Result<T> = Result.Err(CloudAuthException("no token"))
    override suspend fun authenticate(): Result<Unit> = fail()
    override suspend fun download(path: String, destPath: String): Result<CloudFileMeta> = fail()
    override suspend fun upload(path: String, sourcePath: String, expectedRev: String?): Result<CloudFileMeta> = fail()
    override suspend fun create(path: String, sourcePath: String): Result<CloudFileMeta> = fail()
    override suspend fun delete(path: String): Result<Unit> = fail()
    override suspend fun rename(from: String, to: String): Result<Unit> = fail()
    override suspend fun metadata(path: String): Result<CloudFileMeta?> = fail()
    override suspend fun list(prefix: String): Result<List<CloudFileEntry>> = fail()
}

/**
 * [CloudSyncTabContent]'s routing from a row's buttons to the [SettingsViewModel]. Only paths that
 * open no dialog are covered: a native `KeryxAlertDialog` / `DialogWindow` times out on CI (see
 * `FeedListDragTest`). That rules out disconnect, reset, switch and the server-address (Nextcloud)
 * routes; what is left is a fresh connect and the reconnect of an auth-failed connection.
 *
 * Each test pins the listed providers rather than taking the build's own, which depend on its OAuth
 * keys (CI builds with none, leaving only Nextcloud, which needs a server address), and drives the
 * Dropbox row through an injected connect flow that needs no real credentials.
 */
@OptIn(ExperimentalTestApi::class)
class CloudSyncTabTest {

    private lateinit var fx: SettingsViewModelFixture

    // Every provider's connect/disconnect/reset button reads the same in each locale, so a row is
    // told apart by its position, which follows availableCloudTypes.
    private val connectLabel = "連携する"
    private val disconnectLabel = "連携を解除"
    private val resetLabel = "同期データをリセット"
    private val reconnectLabel = "連携し直す"
    private val syncNowLabel = "今すぐ同期"
    private val meteredLabel = "モバイルデータ通信でも自動で同期する"

    @BeforeTest
    fun setUp() {
        fx = SettingsViewModelFixture()
    }

    @AfterTest
    fun tearDown() {
        fx.cancelWork()
        fx.closeStorage()
    }

    /** The provider the tests drive. */
    private val type = CloudStorageType.DROPBOX

    /** The providers the tab lists: the driven one, plus one that needs a server address. */
    private val providers = listOf(CloudStorageType.DROPBOX, CloudStorageType.NEXTCLOUD)

    @Test
    fun connectButtonOfAnIdleRowStartsTheConnectAndShowsItsActions() = runDesktopComposeUiTest {
        val flow = CountingConnectFlow()
        val vm = fx.newViewModel(cloudType = type, connectFlow = flow, availableCloudTypes = providers)
        setContent { MaterialTheme { CloudSyncTabContent(vm) } }
        waitForIdle()
        assertNull(vm.connectedType.value)
        onAllNodesWithText(disconnectLabel).assertCountEquals(0)

        onAllNodesWithText(connectLabel)[vm.availableCloudTypes.indexOf(type)].performClick()
        waitUntil(timeoutMillis = 5_000) { vm.connectedType.value == type }
        waitForIdle()

        assertEquals(1, flow.calls.get())
        // The connected row now offers its own actions in place of "connect".
        onNodeWithText(disconnectLabel).assertIsEnabled()
        onNodeWithText(resetLabel).assertIsEnabled()
        onAllNodesWithText(reconnectLabel).assertCountEquals(0)
    }

    @Test
    fun syncNowIsDisabledUntilAProviderIsConnected() = runDesktopComposeUiTest {
        val flow = CountingConnectFlow()
        val vm = fx.newViewModel(cloudType = type, connectFlow = flow, availableCloudTypes = providers)
        setContent { MaterialTheme { CloudSyncTabContent(vm) } }
        waitForIdle()

        onNodeWithText(syncNowLabel).assertIsNotEnabled()

        onAllNodesWithText(connectLabel)[vm.availableCloudTypes.indexOf(type)].performClick()
        waitUntil(timeoutMillis = 5_000) { vm.canSyncNow.value }
        waitForIdle()

        onNodeWithText(syncNowLabel).assertIsEnabled()
    }

    @Test
    fun meteredNetworkSettingIsNotOfferedOnDesktop() = runDesktopComposeUiTest {
        val vm = fx.newViewModel(cloudType = type, connectFlow = CountingConnectFlow(), availableCloudTypes = providers)
        setContent { MaterialTheme { CloudSyncTabContent(vm) } }
        onAllNodesWithText(connectLabel)[vm.availableCloudTypes.indexOf(type)].performClick()
        waitUntil(timeoutMillis = 5_000) { vm.connectedType.value == type }
        waitForIdle()

        assertTrue(!vm.meteredSettingSupported)
        onAllNodesWithText(meteredLabel).assertCountEquals(0)
    }

    @Test
    fun authFailureOffersReconnectInPlaceOfResetAndReconnectRunsTheConnectFlowAgain() = runDesktopComposeUiTest {
        val flow = CountingConnectFlow()
        // The initial sync a fresh connect starts fails with an auth error, which is what flips the row.
        val vm = fx.newViewModel(cloudType = type, connectFlow = flow, cloudStorage = AuthFailingCloudStorage(), availableCloudTypes = providers)
        setContent { MaterialTheme { CloudSyncTabContent(vm) } }
        waitForIdle()

        onAllNodesWithText(connectLabel)[vm.availableCloudTypes.indexOf(type)].performClick()
        waitUntil(timeoutMillis = 5_000) { vm.lastSyncAuthFailed.value }
        waitForIdle()

        assertEquals(1, flow.calls.get())
        onNodeWithText(reconnectLabel).assertIsEnabled()
        onAllNodesWithText(resetLabel).assertCountEquals(0)
        // The manual sync is blocked until the row's own reconnect has repaired the authorization.
        onNodeWithText(syncNowLabel).assertIsNotEnabled()

        onNodeWithText(reconnectLabel).performClick()
        waitUntil(timeoutMillis = 5_000) { flow.calls.get() == 2 }
        waitUntil(timeoutMillis = 5_000) { vm.connectingType.value == null && vm.initialSyncingType.value == null }
        waitForIdle()

        // Reconnect tears the connection down and connects the same provider again.
        assertEquals(2, flow.calls.get())
        assertEquals(type, vm.connectedType.value)
    }
}
