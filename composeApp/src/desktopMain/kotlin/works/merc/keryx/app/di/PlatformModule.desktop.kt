package works.merc.keryx.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import org.jetbrains.compose.resources.getString
import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.DesktopBuildConfig
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.data.cloud.CloudAuthManager
import works.merc.keryx.app.data.cloud.FileTokenStorage
import works.merc.keryx.app.data.cloud.GoogleDriveAuthManager
import works.merc.keryx.app.data.cloud.GoogleDriveStorage
import works.merc.keryx.app.data.cloud.KeyringTokenStorage
import works.merc.keryx.app.data.cloud.LibSecretTokenStorage
import works.merc.keryx.app.data.cloud.SecurityCliTokenStorage
import works.merc.keryx.app.data.cloud.TokenStorage
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.LoopbackRedirectTransport
import works.merc.keryx.app.domain.OAuthConnectFlow
import works.merc.keryx.app.domain.OsNotificationSink
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.platform.isSnap
import works.merc.keryx.app.platform.update.DesktopUpdateInstaller
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.oauth_loopback_success

/**
 * One secure-store instance per provider (never shared — some impls cache the
 * loaded value). The account / file name is derived from [CloudStorageType.id];
 * Dropbox's values (`dropbox`) match the pre-multi-provider hardcoded ones, so no
 * migration is needed for existing users' stored tokens. macOS Keychain writes
 * fail from the shared JVM via java-keyring, so delegate to the Apple-signed
 * `security` CLI there; Windows/Linux normally keep the java-keyring backend, but
 * inside the snap [LibSecretTokenStorage] is used instead — `password-manager-service`
 * is not auto-connected by snapd policy (and Snapcraft reviewers decline that
 * request for this interface on principle, see `docs/build.md`), so java-keyring's
 * direct Secret Service access would silently fall back to the plaintext file for
 * every Store-installed user. Deliberately not applied outside the snap: it would
 * make existing deb/rpm users' Secret Service tokens unreadable (different item
 * schema/attributes), forcing a needless re-authorization.
 *
 * **No migration the other way either**: a user who manually ran
 * `snap connect keryx:password-manager-service` before this class existed has tokens sitting in a
 * `KeyringTokenStorage`/java-keyring Secret Service item that this class's different schema simply
 * cannot see. They see "not connected" once and need to reconnect (and may want to manually remove
 * the orphaned Secret Service item themselves, e.g. via `seahorse`/`secret-tool`) — accepted because
 * no tagged release has ever shipped the Snap package (it was only ever built manually or via
 * `release.yml`'s untagged `package-snap` job), so the affected population is at most a handful of
 * pre-release testers, not the general Store install base.
 *
 * [snap] and [macOs] are parameters (rather than reading [works.merc.keryx.app.platform.isSnap] /
 * [works.merc.keryx.app.platform.isMacOs] directly) so a test can pin all three branches without
 * depending on the real OS/environment this test JVM happens to run under.
 */
internal fun providerTokenStorage(type: CloudStorageType, macOs: Boolean, snap: Boolean): TokenStorage {
    val fallback = FileTokenStorage(fileName = ".${type.id}_tokens.json")
    return when {
        macOs -> SecurityCliTokenStorage(fallback = fallback, account = type.id)
        snap -> LibSecretTokenStorage(fallback = fallback, account = type.id)
        else -> KeyringTokenStorage(fallback = fallback, account = type.id)
    }
}

actual val platformModule: Module = module {
    // No-op: main.kt collects NewArticleNotifier.trayEvents itself for the whole process
    // lifetime and posts tray notifications from there (see that flow's own KDoc). Posting again
    // through this sink would show every new-article notification twice.
    single<OsNotificationSink> { OsNotificationSink { _, _ -> } }

    single<UpdateInstaller> { DesktopUpdateInstaller(get()) }

    single { keryxHttpClient(CIO) }

    cloudSessionSingles(
        tokenStorage = { type -> providerTokenStorage(type, isMacOs, isSnap) },
        extraProviders = { client -> mapOf(CloudStorageType.GOOGLE_DRIVE to googleDriveProvider(client)) },
    )
}

/**
 * Google Drive: loopback redirect (Google rejects arbitrary custom schemes). Desktop-only, and
 * deliberately kept here rather than in the shared `jvmCommonMain` module: its client id/secret
 * come from [DesktopBuildConfig], generated into a directory attached only to `desktopMain` (see
 * `composeApp/build.gradle.kts`'s `generatedDesktopBuildConfigDir` wiring), and
 * [LoopbackRedirectTransport] is itself a `desktopMain`-only class.
 */
private fun googleDriveProvider(client: HttpClient): CloudSession.Provider {
    val driveAuth: CloudAuthManager = GoogleDriveAuthManager(client, DesktopBuildConfig.GOOGLE_DRIVE_CLIENT_SECRET)
    return CloudSession.Provider(
        clientId = DesktopBuildConfig.GOOGLE_DRIVE_CLIENT_ID,
        tokenStorage = providerTokenStorage(CloudStorageType.GOOGLE_DRIVE, isMacOs, isSnap),
        authManager = driveAuth,
        connectFlow = OAuthConnectFlow(
            authManager = driveAuth,
            clientId = DesktopBuildConfig.GOOGLE_DRIVE_CLIENT_ID,
            transport = LoopbackRedirectTransport(
                successMessageProvider = { getString(Res.string.oauth_loopback_success) },
            ),
        ),
        createStorage = { tokenProvider -> GoogleDriveStorage(client, tokenProvider) },
    )
}
