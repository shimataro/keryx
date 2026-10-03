package works.merc.keryx.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.koin.compose.koinInject
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.CloudStorageAvailability
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.presentation.settings.OpmlTransferController
import works.merc.keryx.app.presentation.settings.PreferencesController
import works.merc.keryx.app.presentation.settings.shouldPresentOpmlRequest
import works.merc.keryx.app.ui.home.HomeScreen
import works.merc.keryx.app.ui.home.NotificationCenterViewModel
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.ui.navigation.Screen
import works.merc.keryx.app.ui.navigation.SettingsOpenRequests
import works.merc.keryx.app.ui.navigation.rememberNavigator
import works.merc.keryx.app.ui.settings.AboutDialog
import works.merc.keryx.app.ui.settings.NotificationPermissionDialog
import works.merc.keryx.app.ui.settings.SettingsDialog
import works.merc.keryx.app.ui.settings.rememberNotificationPermissionPrompt
import works.merc.keryx.app.ui.setup.SetupScreen
import works.merc.keryx.app.ui.theme.KeryxTheme

/**
 * Renders the application UI, including setup or home content and modeless About and Settings dialogs.
 *
 * The initial screen is selected based on whether setup is complete. Settings can open on a specified
 * tab when requested by a notification.
 */
@Composable
fun App() {
    val settingsRepository = koinInject<SettingsRepository>()
    val menuController = koinInject<MenuController>()
    val settings by settingsRepository.localSettings.collectAsState()

    KeryxTheme(themeMode = settings.themeMode, fontScale = settings.fontSizeScale.toFloat()) {
        val setupComplete = remember { settingsRepository.isSetupComplete() }
        val hasCloudOptions = remember { CloudStorageAvailability.available.isNotEmpty() }
        val startScreen = if (setupComplete || !hasCloudOptions) Screen.Home else Screen.Setup
        val navigator = rememberNavigator(startScreen)

        if (!setupComplete && !hasCloudOptions) {
            // No cloud providers available — persist local-only settings so the next launch
            // also skips setup, then land directly on Home.
            LaunchedEffect(Unit) {
                settingsRepository.mutateLocalSettings { it.copy(cloudStorageType = null) }
                settingsRepository.flush()
            }
        }

        // Keep the menu bar's screen-gating (see AppMenuBar) in sync with the active destination.
        LaunchedEffect(navigator.current) { menuController.currentScreen.value = navigator.current }

        // Asks once, in context, for Android 13+'s notification permission when Home is first shown
        // with the user's own notification setting on but the permission not granted: an in-app
        // explanation first, then the system request on "Allow"; "Not now" or a denial turns the
        // setting off so it matches reality (see NotificationPermissionFlow). Never shows on desktop
        // or below Android 13, where the permission always reads as granted. Turning the setting on
        // later from Settings ▸ Notifications goes through the same flow there, without the
        // explanation. Keyed on navigator.current (live navigation state), not setupComplete: that's
        // a remember{} snapshot taken once to pick the *initial* screen, so a user who completes
        // setup right now would otherwise not be asked until the next cold start.
        val preferences = koinInject<PreferencesController>()
        val notificationPermissionPrompt = rememberNotificationPermissionPrompt(preferences::setNotificationEnabled)
        LaunchedEffect(navigator.current) {
            if (navigator.current == Screen.Home) notificationPermissionPrompt.onHomeShown(settings.notificationEnabled)
        }
        if (navigator.current == Screen.Home && notificationPermissionPrompt.explanationVisible) {
            NotificationPermissionDialog(
                onAllow = notificationPermissionPrompt::onAllow,
                onNotNow = notificationPermissionPrompt::onNotNow,
            )
        }

        // Menu commands whose target lives in App's own composition (the About/Settings dialogs).
        // Both dialogs are modeless windows shown over Home, tracked by boolean state here. Saveable
        // (like the tab state below) so an open dialog survives an Android configuration change.
        var showAbout by rememberSaveable { mutableStateOf(false) }
        var showSettings by rememberSaveable { mutableStateOf(false) }
        // Which tab the settings dialog opens on, from the last released SettingsOpenRequest.
        var settingsInitialTab by rememberSaveable { mutableStateOf("general") }
        // Bumped on every explicit tab-navigation request so the dialog re-navigates even when
        // settingsInitialTab is reassigned the same value it already holds (see SettingsDialog's
        // rememberSelectedTabId, which keys off this instead of the tab id's value).
        var settingsTabRequestToken by rememberSaveable { mutableStateOf(0) }

        // Every route that opens Settings goes through the one SettingsOpenRequests router (see its
        // KDoc), which holds a request made over Setup until Home is showing. This is its single
        // consumer: it re-runs whenever a request arrives or the destination changes.
        val settingsOpenRequests = koinInject<SettingsOpenRequests>()
        val settingsRequest by settingsOpenRequests.pending.collectAsState()
        LaunchedEffect(settingsRequest, navigator.current) {
            val released = settingsOpenRequests.release(navigator.current) ?: return@LaunchedEffect
            settingsInitialTab = released.tabId
            settingsTabRequestToken++
            showSettings = true
        }

        // An OPML import/export asked for outside Settings (the File menu, an opened .opml file) is
        // carried out by Settings ▸ Data, so a waiting request opens it there. Keyed on busy too: a
        // request made while an operation runs is shown once that one finishes.
        val opmlController = koinInject<OpmlTransferController>()
        val pendingOpmlRequest by opmlController.pendingRequest.collectAsState()
        val opmlBusy by opmlController.busy.collectAsState()
        LaunchedEffect(pendingOpmlRequest, opmlBusy) {
            if (shouldPresentOpmlRequest(pendingOpmlRequest, opmlBusy)) settingsOpenRequests.request("data")
        }

        // A notification's "open this settings tab" action is forwarded to the router (HomeScreen
        // resolves the actions targeting its own panes).
        val notifVm = koinInject<NotificationCenterViewModel>()
        val pendingAction by notifVm.pendingAction.collectAsState()
        LaunchedEffect(pendingAction) {
            val action = pendingAction?.action
            if (action is AppNotificationAction.ShowSettingsTab) {
                settingsOpenRequests.request(action.tabId)
                notifVm.clearPendingAction()
            }
        }

        LaunchedEffect(Unit) {
            menuController.commands.collect { command ->
                when (command) {
                    MenuCommand.About -> showAbout = true
                    // Dropped (not held) away from Home, matching the menu item's enabled state; the
                    // native macOS "Settings…" item is always enabled but is a no-op away from Home.
                    // Opened by the user, not by a notification: always start on the first tab.
                    MenuCommand.OpenSettings -> settingsOpenRequests.requestIfReachable("general", navigator.current)
                    else -> {}
                }
            }
        }

        Surface(Modifier.fillMaxSize()) {
            when (navigator.current) {
                Screen.Setup -> SetupScreen(onComplete = { navigator.replace(Screen.Home) })
                Screen.Home -> HomeScreen()
            }
            if (showAbout) AboutDialog(onDismiss = { showAbout = false })
            if (showSettings) {
                SettingsDialog(
                    onDismiss = { showSettings = false },
                    initialTabId = settingsInitialTab,
                    tabRequestToken = settingsTabRequestToken,
                )
            }
        }
    }
}
