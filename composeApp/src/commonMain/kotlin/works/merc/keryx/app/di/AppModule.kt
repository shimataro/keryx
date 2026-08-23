package works.merc.keryx.app.di

import app.cash.sqldelight.db.SqlDriver
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.core.AppInfo
import works.merc.keryx.app.core.Clock
import works.merc.keryx.app.core.SystemClock
import works.merc.keryx.app.data.local.DatabaseDriverFactory
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.data.local.FtsSearch
import works.merc.keryx.app.data.local.LocalSettingsStore
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.data.remote.FaviconResolver
import works.merc.keryx.app.data.remote.FeedFetcher
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.SyncScheduler
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.platform.SelfUpdateCheckSupport
import works.merc.keryx.app.platform.selfUpdateCheckSupported
import works.merc.keryx.app.ui.home.HomeViewModel
import works.merc.keryx.app.ui.home.NotificationCenterViewModel
import works.merc.keryx.app.ui.i18n.ComposeNotificationMessages
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.ui.settings.SettingsViewModel
import works.merc.keryx.app.ui.setup.SetupViewModel

/** Platform-specific bindings (HTTP client, token storage, cloud session). */
expect val platformModule: Module

/**
 * Shared bindings. Platform bindings ([platformModule]) provide the SQL driver
 * inputs, [io.ktor.client.HttpClient], token storage, [CloudSession], and the
 * Dropbox connect flow. ViewModel bindings live in [viewModelModule].
 */
val appModule: Module = module {
    single<SqlDriver> { DatabaseDriverFactory().create() }
    single { KeryxDatabase(get()) }
    single { FtsManager(get<SqlDriver>()) }
    single { FtsSearch(get<SqlDriver>()) }
    single { LocalSettingsStore() }
    single<Clock> { SystemClock }
    single { NotificationCenter() }
    single { ActivityCenter() }
    single { MenuController() }
    single { NewArticleNotifier(get()) }
    single<NotificationMessages> { ComposeNotificationMessages() }

    // Long-lived scope for debounced sync + background work.
    single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }

    single {
        SyncRepository(
            driver = get(),
            db = get(),
            ftsManager = get(),
            cloudProvider = { get<CloudSession>().current() },
            clock = get(),
            scope = get(),
            activityCenter = get(),
            notificationCenter = get(),
            notificationMessages = get(),
        )
    }
    single<SyncScheduler> { get<SyncRepository>() }

    single { FeedFetcher(get()) { get<SettingsRepository>().getReadTimeoutSeconds() } }
    single { FaviconResolver(get()) }
    single { UpdateChecker(client = get(), currentVersion = AppInfo.version, repoSlug = AppInfo.updateRepo) }
    single<SelfUpdateCheckSupport> { SelfUpdateCheckSupport { selfUpdateCheckSupported } }

    single { SettingsRepository(get(), get(), get(), get()) }
    single { ArticleRepository(get(), get(), get(), get()) }
    single { TagRepository(get(), get(), get()) }
    single { FeedRepository(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { FolderRepository(get(), get(), get(), get()) }
    single { OpmlImporter(get(), get(), get()) }

    // ViewModels are app-scoped for this single-window desktop app.
    single { NotificationCenterViewModel(get()) }
    single { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SetupViewModel(get(), get(), get()) }
}
