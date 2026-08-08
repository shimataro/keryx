package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.ui.common.FlatTonalButton
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.SegmentedControl
import works.merc.keryx.app.ui.common.SmallSpinner
import works.merc.keryx.app.ui.i18n.opmlImportedText
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.settings_cache
import works.merc.keryx.app.resources.settings_data_management
import works.merc.keryx.app.resources.settings_days30
import works.merc.keryx.app.resources.settings_days7
import works.merc.keryx.app.resources.settings_days90
import works.merc.keryx.app.resources.settings_export_error
import works.merc.keryx.app.resources.settings_export_opml
import works.merc.keryx.app.resources.settings_export_success
import works.merc.keryx.app.resources.settings_import_error
import works.merc.keryx.app.resources.settings_import_opml
import works.merc.keryx.app.resources.settings_read_timeout
import works.merc.keryx.app.resources.settings_seconds10
import works.merc.keryx.app.resources.settings_seconds30
import works.merc.keryx.app.resources.settings_seconds60
import works.merc.keryx.app.resources.settings_unlimited

/** How long the inline OPML import/export status stays before auto-clearing. */
private const val OPML_STATUS_MS = 4000L

/**
 * Displays cache retention, read timeout, and OPML import/export settings.
 *
 * @param vm The view model that provides setting values and handles updates and OPML operations.
 */
@Composable
internal fun DataTabContent(vm: SettingsViewModel) {
    // Inline status shown right under the OPML import/export buttons (macOS-style transient text).
    var opmlStatus by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(vm.opmlResult) {
        opmlStatus = when (val r = vm.opmlResult) {
            is OpmlResult.Exported -> getString(Res.string.settings_export_success)
            is OpmlResult.Imported -> opmlImportedText(r.added, r.failed)
            OpmlResult.ExportFailed -> getString(Res.string.settings_export_error)
            OpmlResult.ImportFailed -> getString(Res.string.settings_import_error)
            OpmlResult.Cancelled, null -> opmlStatus
        }
        vm.clearOpmlResult()
    }
    LaunchedEffect(opmlStatus) {
        if (opmlStatus != null) {
            delay(OPML_STATUS_MS)
            opmlStatus = null
        }
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Section(stringResource(Res.string.settings_cache)) {
            SegmentedControl(
                options = listOf(
                    7 to stringResource(Res.string.settings_days7),
                    30 to stringResource(Res.string.settings_days30),
                    90 to stringResource(Res.string.settings_days90),
                    (null as Int?) to stringResource(Res.string.settings_unlimited),
                ),
                selected = vm.cacheRetentionDays,
                onSelect = { vm.updateCacheRetention(it) },
            )
        }

        Section(stringResource(Res.string.settings_read_timeout)) {
            SegmentedControl(
                options = listOf(
                    10 to stringResource(Res.string.settings_seconds10),
                    30 to stringResource(Res.string.settings_seconds30),
                    60 to stringResource(Res.string.settings_seconds60),
                ),
                selected = vm.readTimeoutSeconds,
                onSelect = { vm.updateReadTimeout(it) },
            )
        }

        Section(stringResource(Res.string.settings_data_management)) {
            // Disable both while either OPML op runs (import can take a while — one fetch per feed)
            // so the buttons don't look inert/re-triggerable; the running one shows a spinner in
            // place of its icon.
            val opmlBusy = vm.importingOpml || vm.exportingOpml
            Row {
                FlatTonalButton(onClick = { vm.importOpml() }, enabled = !opmlBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.importingOpml) {
                            SmallSpinner(size = 18.dp)
                        } else {
                            KeryxIcon(
                                KeryxIcons.FileDownload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.settings_import_opml))
                    }
                }
                Spacer(Modifier.width(8.dp))
                FlatTonalButton(onClick = { vm.exportOpml() }, enabled = !opmlBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (vm.exportingOpml) {
                            SmallSpinner(size = 18.dp)
                        } else {
                            KeryxIcon(
                                KeryxIcons.FileUpload,
                                contentDescription = null,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.settings_export_opml))
                    }
                }
            }
            opmlStatus?.let {
                Spacer(Modifier.height(8.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
