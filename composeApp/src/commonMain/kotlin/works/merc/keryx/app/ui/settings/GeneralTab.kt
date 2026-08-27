package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject
import works.merc.keryx.app.platform.hasNativeAppMenu
import works.merc.keryx.app.platform.hasSystemTray
import works.merc.keryx.app.ui.common.SegmentedControl
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.menu_help_about
import works.merc.keryx.app.resources.settings_font_large
import works.merc.keryx.app.resources.settings_font_medium
import works.merc.keryx.app.resources.settings_font_size
import works.merc.keryx.app.resources.settings_font_small
import works.merc.keryx.app.resources.settings_font_xlarge
import works.merc.keryx.app.resources.settings_refresh_hour1
import works.merc.keryx.app.resources.settings_refresh_hour3
import works.merc.keryx.app.resources.settings_refresh_interval
import works.merc.keryx.app.resources.settings_refresh_manual
import works.merc.keryx.app.resources.settings_refresh_min15
import works.merc.keryx.app.resources.settings_refresh_min30
import works.merc.keryx.app.resources.settings_start_minimized
import works.merc.keryx.app.resources.settings_theme
import works.merc.keryx.app.resources.settings_theme_dark
import works.merc.keryx.app.resources.settings_theme_light
import works.merc.keryx.app.resources.settings_theme_system

/**
 * General tab: theme / font size / refresh interval / start-minimized / (Android only) an About
 * entry point — desktop reaches About through the native application menu bar instead (see
 * `platform/PlatformOs.kt`'s `hasNativeAppMenu`).
 *
 * @param vm The view model that provides current settings and handles setting changes.
 */
@Composable
internal fun GeneralTabContent(vm: SettingsViewModel) {
    val settings by vm.localSettings.collectAsState()
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Section(stringResource(Res.string.settings_theme)) {
            SegmentedControl(
                options = listOf(
                    "system" to stringResource(Res.string.settings_theme_system),
                    "light" to stringResource(Res.string.settings_theme_light),
                    "dark" to stringResource(Res.string.settings_theme_dark),
                ),
                selected = settings.themeMode,
                onSelect = { vm.setThemeMode(it) },
            )
        }

        Section(stringResource(Res.string.settings_font_size)) {
            SegmentedControl(
                options = listOf(
                    0.85 to stringResource(Res.string.settings_font_small),
                    1.0 to stringResource(Res.string.settings_font_medium),
                    1.2 to stringResource(Res.string.settings_font_large),
                    1.4 to stringResource(Res.string.settings_font_xlarge),
                ),
                selected = settings.fontSizeScale,
                onSelect = { vm.setFontScale(it) },
            )
        }

        Section(stringResource(Res.string.settings_refresh_interval)) {
            SegmentedControl(
                options = listOf(
                    15 to stringResource(Res.string.settings_refresh_min15),
                    30 to stringResource(Res.string.settings_refresh_min30),
                    60 to stringResource(Res.string.settings_refresh_hour1),
                    180 to stringResource(Res.string.settings_refresh_hour3),
                    0 to stringResource(Res.string.settings_refresh_manual),
                ),
                selected = settings.refreshIntervalMinutes,
                onSelect = { vm.setRefreshIntervalMinutes(it) },
            )
        }

        if (hasSystemTray) {
            Spacer(Modifier.height(8.dp))
            SettingsCard {
                SwitchRow(
                    label = stringResource(Res.string.settings_start_minimized),
                    checked = settings.startMinimized,
                    onChange = { vm.setStartMinimized(it) },
                )
            }
        }

        if (!hasNativeAppMenu) {
            val menuController = koinInject<MenuController>()
            Spacer(Modifier.height(16.dp))
            ActionLinkRow(
                label = stringResource(Res.string.menu_help_about),
                onClick = { menuController.send(MenuCommand.About) },
            )
        }
    }
}
