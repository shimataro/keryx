package works.merc.keryx.app.di

import io.ktor.client.engine.darwin.Darwin
import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.data.cloud.KeychainTokenStorage
import works.merc.keryx.app.domain.NotificationMessages
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
        tokenStorage = { type -> KeychainTokenStorage(account = type.id) },
        extraProviders = { emptyMap() },
    )
}
