package works.merc.keryx.app.di

import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.ui.home.HomeLayoutViewModel
import works.merc.keryx.app.ui.home.NotificationCenterViewModel
import works.merc.keryx.app.ui.i18n.ComposeNotificationMessages
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.ui.navigation.AddFeedRequests
import works.merc.keryx.app.ui.navigation.SettingsOpenRequests
import works.merc.keryx.app.ui.settings.SettingsViewModel

/** Platform-specific bindings (HTTP client, token storage, cloud session, update installer). */
expect val platformModule: Module

/**
 * The Compose app's bindings: :shared's [sharedModule], [updateModule] and [presentationModule],
 * plus what only this UI provides — the Compose Resources [NotificationMessages], the menu bus, and the ViewModels.
 * Platform bindings come from [platformModule].
 */
val appModule: Module = module {
    includes(sharedModule(), updateModule(), presentationModule())

    single { MenuController() }
    single { SettingsOpenRequests() }
    single { AddFeedRequests() }
    single<NotificationMessages> { ComposeNotificationMessages() }

    // ViewModels are app-scoped for this single-window desktop app.
    single { NotificationCenterViewModel(get(), get()) }
    single { HomeLayoutViewModel(get()) }
    single { SettingsViewModel(get(), get(), get(), get()) }
}
