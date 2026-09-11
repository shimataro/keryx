package works.merc.keryx.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.data.cloud.FileTokenStorage
import works.merc.keryx.app.data.cloud.KeystoreTokenStorage
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.domain.OsNotificationSink
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.platform.AndroidAppContext
import works.merc.keryx.app.platform.AndroidNotificationSink
import works.merc.keryx.app.platform.update.AndroidUpdateInstaller

/**
 * One secure-store instance per provider (never shared — [KeystoreTokenStorage] holds a
 * provider-scoped Keystore key alias and file). The account name is derived from
 * [CloudStorageType.id], matching desktop's own per-provider naming so a Dropbox-linked device's
 * `id` scheme lines up with the desktop actual (`providerTokenStorage` in
 * `PlatformModule.desktop.kt`) even though the encryption mechanism differs.
 */
private fun providerTokenStorage(type: CloudStorageType): TokenStorage =
    KeystoreTokenStorage(fallback = FileTokenStorage(fileName = ".${type.id}_tokens.json"), account = type.id)

/**
 * Android `platformModule`. Dropbox and OneDrive are wired the same way as desktop (PKCE public
 * client, `CustomUriRedirectTransport` over the shared `keryx://oauth2/callback` scheme — see
 * `.claude/rules/cloud-oauth-transport.md` and `di/CloudPlatformModule.kt`'s `cloudSessionSingles`);
 * the OS delivers the redirect to `MainActivity` (`AndroidManifest.xml`'s
 * `keryx://oauth2/callback` intent-filter), which forwards it into the shared
 * `MutableSharedFlow<OAuthCallbackParams>` via `dispatchOAuthCallbackIfPresent`.
 *
 * Google Drive has no provider entry here — see `core/CloudStorageAvailability.android.kt`'s own
 * KDoc for why it is out of scope on this platform. `DatabaseMerger`/`DatabaseSnapshot` are fully
 * implemented (Android actuals), unlike the earlier phases' stubs.
 */
actual val platformModule: Module = module {
    single<OsNotificationSink> { AndroidNotificationSink(AndroidAppContext.application) }

    single<UpdateInstaller> { AndroidUpdateInstaller() }

    single { keryxHttpClient(OkHttp) }

    cloudSessionSingles(
        tokenStorage = ::providerTokenStorage,
        // Google Drive has no provider entry on Android — see
        // core/CloudStorageAvailability.android.kt's own KDoc for why it is out of scope here.
        extraProviders = { emptyMap() },
    )
}
