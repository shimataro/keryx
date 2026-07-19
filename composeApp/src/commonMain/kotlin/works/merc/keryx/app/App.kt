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
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.ui.home.HomeScreen
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.ui.navigation.Screen
import works.merc.keryx.app.ui.navigation.rememberNavigator
import works.merc.keryx.app.ui.settings.AboutDialog
import works.merc.keryx.app.ui.settings.SettingsDialog
import works.merc.keryx.app.ui.setup.SetupScreen
import works.merc.keryx.app.ui.theme.KeryxTheme

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

        // Menu commands whose target lives in App's own composition (the About/Settings dialogs).
        // Both dialogs are modeless windows shown over Home, tracked by boolean state here.
        var showAbout by remember { mutableStateOf(false) }
        var showSettings by remember { mutableStateOf(false) }
        LaunchedEffect(Unit) {
            menuController.commands.collect { command ->
                when (command) {
                    MenuCommand.About -> showAbout = true
                    // Home-gated, matching the menu item's enabled state; the native macOS
                    // "Settings…" item is always enabled but is a no-op away from Home.
                    MenuCommand.OpenSettings ->
                        if (navigator.current == Screen.Home) showSettings = true
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
            if (showSettings) SettingsDialog(onDismiss = { showSettings = false })
        }
    }
}
