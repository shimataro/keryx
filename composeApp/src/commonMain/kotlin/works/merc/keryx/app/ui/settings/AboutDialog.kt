package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.AppInfo
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.app_icon
import works.merc.keryx.app.resources.app_name
import works.merc.keryx.app.resources.common_ok
import works.merc.keryx.app.resources.settings_licenses
import works.merc.keryx.app.resources.settings_project_page
import works.merc.keryx.app.resources.settings_version
import works.merc.keryx.app.resources.settings_website
import works.merc.keryx.app.resources.website_url
import works.merc.keryx.app.ui.common.KeryxAlertDialog

/**
 * Minimal macOS-style "About" dialog shown from the native application menu's
 * "About Keryx" item. Detailed open-source licenses live in the Settings screen's
 * About section, not here.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    KeryxAlertDialog(
        onDismissRequest = onDismiss,
        confirmText = stringResource(Res.string.common_ok),
        onConfirm = onDismiss,
        title = stringResource(Res.string.app_name),
        text = { AboutDialogContent() },
        modal = false,
    )
}

/** The dialog body — separated so it can be rendered in a UI test without the DialogWindow. */
@Composable
internal fun AboutDialogContent() {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            painter = painterResource(Res.drawable.app_icon),
            contentDescription = null,
            modifier = Modifier.size(72.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            stringResource(Res.string.settings_version, AppInfo.version),
            style = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(20.dp))
        LinkRow(
            label = stringResource(Res.string.settings_website),
            url = stringResource(Res.string.website_url),
        )
        Spacer(Modifier.height(4.dp))
        LinkRow(
            label = stringResource(Res.string.settings_project_page),
            url = PROJECT_URL,
        )
        Spacer(Modifier.height(4.dp))
        LinkRow(
            label = stringResource(Res.string.settings_licenses),
            url = LICENSES_URL,
        )
        // Extra breathing room before the shared button row so the OK button doesn't feel crammed.
        Spacer(Modifier.height(8.dp))
    }
}
