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
import androidx.compose.runtime.collectAsState
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
import works.merc.keryx.app.presentation.settings.OpmlOperation
import works.merc.keryx.app.presentation.settings.OpmlRequest
import works.merc.keryx.app.presentation.settings.OpmlResult
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
    val readTimeoutSeconds by vm.readTimeoutSeconds.collectAsState()
    val cacheRetentionDays by vm.cacheRetentionDays.collectAsState()
    val opmlResult by vm.opmlResult.collectAsState()
    val opmlRunning by vm.opmlRunning.collectAsState()
    val opmlBusy by vm.opmlBusy.collectAsState()
    val pendingOpmlRequest by vm.pendingOpmlRequest.collectAsState()
    // Inline status shown right under the OPML import/export buttons (macOS-style transient text).
    var opmlStatus by remember { mutableStateOf<String?>(null) }

    // Carries out an import/export asked for outside this tab (the File menu, an opened .opml file)
    // here, so it gets the same file dialog, spinner and result as the buttons below. Waits while
    // another operation runs; consumeOpmlRequest() hands the request out only once nothing does.
    LaunchedEffect(pendingOpmlRequest, opmlBusy) {
        if (pendingOpmlRequest == null || opmlBusy) return@LaunchedEffect
        when (val request = vm.consumeOpmlRequest()) {
            OpmlRequest.ImportFile -> vm.importOpml()
            OpmlRequest.ExportFile -> vm.exportOpml()
            is OpmlRequest.ImportDocument -> vm.importDocument(request.xml)
            null -> Unit
        }
    }

    // Shows a finished operation's result once and clears it — including one that finished while
    // the settings dialog was closed, which is therefore shown on the next visit to this tab.
    LaunchedEffect(opmlResult) {
        opmlStatus = when (val r = opmlResult) {
            is OpmlResult.Exported -> getString(Res.string.settings_export_success)
            is OpmlResult.Imported -> opmlImportedText(r.added, r.failed)
            OpmlResult.ExportFailed -> getString(Res.string.settings_export_error)
            OpmlResult.ImportFailed -> getString(Res.string.settings_import_error)
            null -> opmlStatus
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
                selected = cacheRetentionDays,
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
                selected = readTimeoutSeconds,
                onSelect = { vm.updateReadTimeout(it) },
            )
        }

        Section(stringResource(Res.string.settings_data_management)) {
            // Disable both while any OPML op runs, whichever route started it (import can take a
            // while — one fetch per feed) so the buttons don't look inert/re-triggerable; the running
            // one shows a spinner in place of its icon.
            val importingOpml = opmlRunning == OpmlOperation.Importing
            val exportingOpml = opmlRunning == OpmlOperation.Exporting
            Row {
                FlatTonalButton(onClick = { vm.importOpml() }, enabled = !opmlBusy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (importingOpml) {
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
                        if (exportingOpml) {
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
