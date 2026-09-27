package works.merc.keryx.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.darwin.Darwin
import kotlinx.coroutines.flow.MutableSharedFlow
import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.AppleBuildConfig
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.data.cloud.CloudAuthManager
import works.merc.keryx.app.data.cloud.GoogleDriveAuthManager
import works.merc.keryx.app.data.cloud.GoogleDriveStorage
import works.merc.keryx.app.data.cloud.KeychainTokenStorage
import works.merc.keryx.app.data.cloud.appleKeychainAccount
import works.merc.keryx.app.data.cloud.googleIosClientRedirectUri
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.CustomUriRedirectTransport
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.domain.OAuthConnectFlow
import works.merc.keryx.app.domain.OsNotificationSink

/**
 * The Apple app's platform bindings — the counterpart of the Compose app's `platformModule` and
 * `appModule` together: the Darwin HTTP engine, Keychain token storage, and the two text sources
 * [sharedModule] expects from its UI. No [updateModule]: the App Store or Sparkle updates this app.
 *
 * @param notificationMessages The new-articles OS-notification text, localized by the Swift app.
 * @param osNotificationSink Posts that notification; the Swift app decides how (UserNotifications).
 */
fun applePlatformModule(
    notificationMessages: NotificationMessages,
    osNotificationSink: OsNotificationSink,
): Module = module {
    single { keryxHttpClient(Darwin) }
    single<NotificationMessages> { notificationMessages }
    single<OsNotificationSink> { osNotificationSink }
    cloudSessionSingles(
        tokenStorage = { type -> KeychainTokenStorage(account = appleKeychainAccount(type)) },
        extraProviders = { client, callbackFlow ->
            if (AppleBuildConfig.GOOGLE_DRIVE_CLIENT_ID.isNotEmpty()) {
                mapOf(CloudStorageType.GOOGLE_DRIVE to appleGoogleDriveProvider(client, callbackFlow))
            } else {
                emptyMap()
            }
        },
    )
}

/**
 * Google Drive on the Apple app: an "iOS"-type OAuth client (no client secret — see
 * [GoogleDriveAuthManager]'s KDoc), authorized through the same custom-URI redirect mechanism as
 * Dropbox/OneDrive (see `.claude/rules/cloud-oauth-transport.md`), but on the client's own
 * `com.googleusercontent.apps.<id>:/oauth2redirect` scheme rather than the shared `keryx://` one —
 * Google issues that scheme per client and does not accept an arbitrary custom scheme. Tokens go
 * through [KeychainTokenStorage] like every other Apple provider.
 */
private fun appleGoogleDriveProvider(
    client: HttpClient,
    callbackFlow: MutableSharedFlow<OAuthCallbackParams>,
): CloudSession.Provider {
    val clientId = AppleBuildConfig.GOOGLE_DRIVE_CLIENT_ID
    val driveAuth: CloudAuthManager = GoogleDriveAuthManager(client, clientSecret = null)
    return CloudSession.Provider(
        clientId = clientId,
        tokenStorage = KeychainTokenStorage(account = appleKeychainAccount(CloudStorageType.GOOGLE_DRIVE)),
        authManager = driveAuth,
        connectFlow = OAuthConnectFlow(
            authManager = driveAuth,
            clientId = clientId,
            transport = CustomUriRedirectTransport(callbackFlow, redirectUri = googleIosClientRedirectUri(clientId)),
        ),
        createStorage = { tokenProvider -> GoogleDriveStorage(client, tokenProvider) },
    )
}
