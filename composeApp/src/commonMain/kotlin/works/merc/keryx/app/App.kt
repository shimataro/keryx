package works.merc.keryx.app

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import org.koin.compose.koinInject
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.platform.rememberNotificationPermissionRequester
import works.merc.keryx.app.ui.home.HomeScreen
import works.merc.keryx.app.ui.home.NotificationCenterViewModel
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.ui.navigation.Screen
import works.merc.keryx.app.ui.navigation.rememberNavigator
import works.merc.keryx.app.ui.settings.AboutDialog
import works.merc.keryx.app.ui.settings.SettingsDialog
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
        val navigator = rememberNavigator(if (setupComplete) Screen.Home else Screen.Setup)

        // Keep the menu bar's screen-gating (see AppMenuBar) in sync with the active destination.
        LaunchedEffect(navigator.current) { menuController.currentScreen.value = navigator.current }

        // Requests Android's POST_NOTIFICATIONS once Home is reached, if the user's own
        // notification setting is already on — a no-op on desktop and on an Android version/state
        // with nothing to request (see the expect's KDoc). NotificationsTab.kt requests it again
        // when the user flips the toggle on, covering the case where it starts off.
        // Keyed on navigator.current (live navigation state), not setupComplete: that's a
        // remember{} snapshot taken once at first composition specifically to pick the *initial*
        // screen, so it never flips to true within the same session — a user who completes setup
        // and lands on Home right now would otherwise never trigger this until the next cold start.
        val requestNotificationPermission = rememberNotificationPermissionRequester()
        LaunchedEffect(navigator.current, settings.notificationEnabled) {
            if (navigator.current == Screen.Home && settings.notificationEnabled) requestNotificationPermission()
        }

        // Menu commands whose target lives in App's own composition (the About/Settings dialogs).
        // Both dialogs are modeless windows shown over Home, tracked by boolean state here.
        var showAbout by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        // Which tab the settings dialog opens on. Normally "general"; a notification's
        // ShowSettingsTab action points it at the tab where that problem is fixable.
        var settingsInitialTab by remember { mutableStateOf("general") }
        // Bumped on every explicit tab-navigation request so the dialog re-navigates even when
        // settingsInitialTab is reassigned the same value it already holds (see SettingsDialog's
        // rememberSelectedTabId, which keys off this instead of the tab id's value).
        var settingsTabRequestToken by remember { mutableStateOf(0) }

        // A notification's "open this settings tab" action is resolved here, because the settings
        // dialog lives in this composition (HomeScreen resolves the actions targeting its own panes).
        val notifVm = koinInject<NotificationCenterViewModel>()
        LaunchedEffect(notifVm.pendingAction) {
            val action = notifVm.pendingAction?.action
            if (action is AppNotificationAction.ShowSettingsTab) {
                settingsInitialTab = action.tabId
                settingsTabRequestToken++
                showSettings = true
                notifVm.clearPendingAction()
            }
        }

        LaunchedEffect(Unit) {
            menuController.commands.collect { command ->
                when (command) {
                    MenuCommand.About -> showAbout = true
                    // Home-gated, matching the menu item's enabled state; the native macOS
                    // "Settings…" item is always enabled but is a no-op away from Home.
                    MenuCommand.OpenSettings ->
                        if (navigator.current == Screen.Home) {
                            // Opened by the user, not by a notification: always start on the first tab.
                            settingsInitialTab = "general"
                            settingsTabRequestToken++
                            showSettings = true
                        }
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
