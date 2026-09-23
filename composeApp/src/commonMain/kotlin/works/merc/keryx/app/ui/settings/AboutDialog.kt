package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.AppInfo
import works.merc.keryx.app.platform.BrowserOpener
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.app_icon
import works.merc.keryx.app.resources.app_name
import works.merc.keryx.app.resources.common_ok
import works.merc.keryx.app.resources.contact_email
import works.merc.keryx.app.resources.privacy_policy_url
import works.merc.keryx.app.resources.settings_contact
import works.merc.keryx.app.resources.settings_licenses
import works.merc.keryx.app.resources.settings_privacy_policy
import works.merc.keryx.app.resources.settings_project_page
import works.merc.keryx.app.resources.settings_terms
import works.merc.keryx.app.resources.settings_version
import works.merc.keryx.app.resources.settings_website
import works.merc.keryx.app.resources.terms_url
import works.merc.keryx.app.resources.website_url
import works.merc.keryx.app.ui.common.KeryxAlertDialog

/**
 * Minimal "About" dialog: shown from the native application menu's "About Keryx" item on desktop,
 * and from the General settings tab's own entry point on Android, which has no application menu
 * (see `hasNativeAppMenu`). Open-source licenses are a link within [AboutDialogContent] itself,
 * opened in the external browser — there is no separate "About section" elsewhere to hold them.
 */
@Composable
fun AboutDialog(onDismiss: () -> Unit) {
    KeryxAlertDialog(
        onDismissRequest = onDismiss,
        confirmText = stringResource(Res.string.common_ok),
        onConfirm = onDismiss,
        title = stringResource(Res.string.app_name),
        text = { AboutDialogContent() },
        // The one dialog that deliberately doesn't use the flat surfaceContainerLow every other
        // KeryxAlertDialog call site shares — a plain surface reads better behind this dialog's
        // own icon + version + link rows than the tonal container the confirm/destructive
        // dialogs use.
        containerColor = MaterialTheme.colorScheme.surface,
        modal = false,
    )
}

/**
 * The dialog body — separated so it can be rendered in a UI test without the DialogWindow.
 *
 * Below the icon and version, links are grouped by how often they're wanted — first the product and
 * support links (website, project page, contact), then the legal documents (terms, privacy policy,
 * licenses) — with a divider before each group. On a touch-primary platform the link rows omit their
 * URL line ([LinkRow]'s `showUrlInline`) so the grouping isn't buried under long URLs; the contact
 * row keeps its address, which is information in its own right.
 *
 * @param openUrl Opens a link row's URL on click — a seam for tests; defaults to [BrowserOpener.open].
 */
@Composable
internal fun AboutDialogContent(openUrl: (String) -> Unit = BrowserOpener::open) {
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

        AboutGroupDivider()
        LinkRow(
            label = stringResource(Res.string.settings_website),
            url = stringResource(Res.string.website_url),
            openUrl = openUrl,
            showUrlInline = false,
        )
        Spacer(Modifier.height(4.dp))
        LinkRow(
            label = stringResource(Res.string.settings_project_page),
            url = PROJECT_URL,
            openUrl = openUrl,
            showUrlInline = false,
        )
        Spacer(Modifier.height(4.dp))
        EmailLinkRow(
            label = stringResource(Res.string.settings_contact),
            address = stringResource(Res.string.contact_email),
            openUrl = openUrl,
        )

        AboutGroupDivider()
        LinkRow(
            label = stringResource(Res.string.settings_terms),
            url = stringResource(Res.string.terms_url),
            openUrl = openUrl,
            showUrlInline = false,
        )
        Spacer(Modifier.height(4.dp))
        LinkRow(
            label = stringResource(Res.string.settings_privacy_policy),
            url = stringResource(Res.string.privacy_policy_url),
            openUrl = openUrl,
            showUrlInline = false,
        )
        Spacer(Modifier.height(4.dp))
        LinkRow(
            label = stringResource(Res.string.settings_licenses),
            url = LICENSES_URL,
            openUrl = openUrl,
            showUrlInline = false,
        )
        // Extra breathing room before the shared button row so the OK button doesn't feel crammed.
        Spacer(Modifier.height(8.dp))
    }
}

/** A semantic break between [AboutDialogContent]'s groups, in the same tone as [Section]'s divider. */
@Composable
private fun AboutGroupDivider() {
    HorizontalDivider(
        Modifier.padding(vertical = 12.dp),
        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f),
    )
}
