package works.merc.keryx.app

import io.ktor.client.HttpClient
import kotlinx.coroutines.awaitCancellation
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.CloudAuthException
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.cloud.CloudAuthManager
import works.merc.keryx.app.data.cloud.CloudStorage
import works.merc.keryx.app.data.cloud.DropboxAuthManager
import works.merc.keryx.app.data.cloud.DropboxStorage
import works.merc.keryx.app.data.cloud.GoogleDriveAuthManager
import works.merc.keryx.app.data.cloud.GoogleDriveStorage
import works.merc.keryx.app.data.cloud.OAuthTokens
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.domain.CloudConnectFlow
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.FakeNotificationMessages
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages

/**
 * In-memory [TokenStorage] fake for tests. [secure] is what [save] reports back — set it to false
 * to model a storage that could only reach the plaintext fallback file.
 */
class FakeTokenStorage(initial: OAuthTokens? = null, private val secure: Boolean = true) : TokenStorage {
    var stored: OAuthTokens? = initial
        private set

    override fun save(tokens: OAuthTokens): Boolean {
        stored = tokens
        return secure
    }

    override fun load(): OAuthTokens? = stored

    override fun clear() {
        stored = null
    }
}

/** A [CloudConnectFlow] fake that returns a canned result. */
class FakeCloudConnectFlow(
    private val result: Result<OAuthTokens> = Result.Err(CloudAuthException("no connect flow")),
) : CloudConnectFlow {
    override suspend fun connect(): Result<OAuthTokens> = result
}

/** A [CloudConnectFlow] fake that suspends until cancelled — models an in-flight OAuth wait. */
class SuspendingCloudConnectFlow : CloudConnectFlow {
    override suspend fun connect(): Result<OAuthTokens> = awaitCancellation()
}

/**
 * Builds a [CloudSession] with a single provider ([type], default Dropbox) selected, for tests.
 * Pass an empty [clientId] to model a provider not configured in the build.
 */
fun singleProviderCloudSession(
    client: HttpClient,
    tokenStorage: TokenStorage,
    authManager: CloudAuthManager,
    clientId: String = "APPKEY",
    type: CloudStorageType = CloudStorageType.DROPBOX,
    clock: Clock = Clock { 0L },
    connectFlow: CloudConnectFlow = FakeCloudConnectFlow(),
    createStorage: (suspend () -> String?) -> CloudStorage = { tokenProvider -> DropboxStorage(client, tokenProvider) },
    selectedType: () -> CloudStorageType? = { type },
    notificationCenter: NotificationCenter = NotificationCenter(),
    notificationMessages: NotificationMessages = FakeNotificationMessages(),
): CloudSession = CloudSession(
    providers = mapOf(
        type to CloudSession.Provider(
            clientId = clientId,
            tokenStorage = tokenStorage,
            authManager = authManager,
            connectFlow = connectFlow,
            createStorage = createStorage,
        ),
    ),
    selectedType = selectedType,
    clock = clock,
    notificationCenter = notificationCenter,
    notificationMessages = notificationMessages,
)

/**
 * Builds a [CloudSession] with both [CloudStorageType.DROPBOX] and [CloudStorageType.GOOGLE_DRIVE]
 * registered simultaneously, for tests that exercise switching between providers (e.g.
 * `SettingsViewModel.switchTo`). Each provider gets its own token storage and connect flow so a
 * test can independently seed/observe them. [client] backs both providers' real auth managers
 * (revoke/refresh), mirroring the production wiring in `PlatformModule.desktop.kt`.
 */
fun multiProviderCloudSession(
    client: HttpClient,
    dropboxTokenStorage: TokenStorage,
    googleDriveTokenStorage: TokenStorage,
    dropboxClientId: String = "APPKEY",
    googleDriveClientId: String = "APPKEY2",
    clock: Clock = Clock { 0L },
    dropboxConnectFlow: CloudConnectFlow = FakeCloudConnectFlow(),
    googleDriveConnectFlow: CloudConnectFlow = FakeCloudConnectFlow(),
    selectedType: () -> CloudStorageType? = { CloudStorageType.DROPBOX },
    notificationCenter: NotificationCenter = NotificationCenter(),
    notificationMessages: NotificationMessages = FakeNotificationMessages(),
): CloudSession = CloudSession(
    providers = mapOf(
        CloudStorageType.DROPBOX to CloudSession.Provider(
            clientId = dropboxClientId,
            tokenStorage = dropboxTokenStorage,
            authManager = DropboxAuthManager(client, clock = clock),
            connectFlow = dropboxConnectFlow,
            createStorage = { tokenProvider -> DropboxStorage(client, tokenProvider) },
        ),
        CloudStorageType.GOOGLE_DRIVE to CloudSession.Provider(
            clientId = googleDriveClientId,
            tokenStorage = googleDriveTokenStorage,
            authManager = GoogleDriveAuthManager(client, clientSecret = "SECRET", clock = clock),
            connectFlow = googleDriveConnectFlow,
            createStorage = { tokenProvider -> GoogleDriveStorage(client, tokenProvider) },
        ),
    ),
    selectedType = selectedType,
    clock = clock,
    notificationCenter = notificationCenter,
    notificationMessages = notificationMessages,
)
