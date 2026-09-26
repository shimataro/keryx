package works.merc.keryx.app.di

import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.presentation.home.HomeViewModel
import works.merc.keryx.app.ui.home.HomeLayoutViewModel
import works.merc.keryx.app.ui.home.NotificationCenterViewModel
import works.merc.keryx.app.ui.i18n.ComposeNotificationMessages
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.ui.settings.SettingsViewModel
import works.merc.keryx.app.ui.setup.SetupViewModel

/** Platform-specific bindings (HTTP client, token storage, cloud session, update installer). */
expect val platformModule: Module

/**
 * The Compose app's bindings: :shared's [sharedModule] and [updateModule], plus what only this
 * UI provides — the Compose Resources [NotificationMessages], the menu bus, and the ViewModels.
 * Platform bindings come from [platformModule].
 */
val appModule: Module = module {
    includes(sharedModule, updateModule)

    single { MenuController() }
    single<NotificationMessages> { ComposeNotificationMessages() }

    // ViewModels are app-scoped for this single-window desktop app.
    single { NotificationCenterViewModel(get()) }
    single { HomeViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { HomeLayoutViewModel(get()) }
    single { SettingsViewModel(get(), get(), get(), get(), get(), get(), get(), get(), get()) }
    single { SetupViewModel(get(), get(), get()) }
}
