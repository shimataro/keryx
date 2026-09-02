package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.core.AppInfo
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.domain.isInstallable
import works.merc.keryx.app.domain.plainTextReleaseNotes
import works.merc.keryx.app.ui.common.FlatButton
import works.merc.keryx.app.ui.common.FlatTextButton
import works.merc.keryx.app.ui.common.FlatTonalButton
import works.merc.keryx.app.ui.common.KeryxIcon
import works.merc.keryx.app.ui.common.KeryxIcons
import works.merc.keryx.app.ui.common.KeryxLinearProgressBar
import works.merc.keryx.app.ui.common.SegmentedControl
import works.merc.keryx.app.ui.common.SmallSpinner
import works.merc.keryx.app.ui.i18n.userMessage
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.common_cancel
import works.merc.keryx.app.resources.settings_update_check_3days
import works.merc.keryx.app.resources.settings_update_check_available
import works.merc.keryx.app.resources.settings_update_check_daily
import works.merc.keryx.app.resources.settings_update_check_failed
import works.merc.keryx.app.resources.settings_update_check_interval
import works.merc.keryx.app.resources.settings_update_check_now
import works.merc.keryx.app.resources.settings_update_check_startup_only
import works.merc.keryx.app.resources.settings_update_check_up_to_date
import works.merc.keryx.app.resources.settings_update_check_weekly
import works.merc.keryx.app.resources.settings_update_download
import works.merc.keryx.app.resources.settings_update_downloading
import works.merc.keryx.app.resources.settings_update_install
import works.merc.keryx.app.resources.settings_update_installing
import works.merc.keryx.app.resources.settings_update_manual_only
import works.merc.keryx.app.resources.settings_update_open_release_page
import works.merc.keryx.app.resources.settings_update_retry
import works.merc.keryx.app.resources.settings_update_verifying
import works.merc.keryx.app.resources.settings_version

/**
 * Updates tab: update-check interval, the manual "check for update" trigger, and — once one is
 * found — the download/verify/install flow driven by [SettingsViewModel.updateState].
 *
 * Opening the tab starts a check if nothing has run yet, same as before this composable was
 * rewritten against the full [UpdateState] machine — equivalent to one press of "check now", so it
 * never perturbs the automatic check schedule.
 *
 * @param vm The view model providing update settings, state, and actions.
 */
@Composable
internal fun UpdatesTabContent(vm: SettingsViewModel) {
    val settings by vm.localSettings.collectAsState()
    val state by vm.updateState.collectAsState()

    LaunchedEffect(Unit) {
        if (state is UpdateState.Idle) vm.checkForUpdate()
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(
            stringResource(Res.string.settings_update_check_interval),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(4.dp))
        SegmentedControl(
            options = listOf(
                0 to stringResource(Res.string.settings_update_check_startup_only),
                24 to stringResource(Res.string.settings_update_check_daily),
                72 to stringResource(Res.string.settings_update_check_3days),
                168 to stringResource(Res.string.settings_update_check_weekly),
            ),
            selected = settings.updateCheckIntervalHours,
            onSelect = { vm.setUpdateCheckIntervalHours(it) },
        )

        Spacer(Modifier.height(12.dp))
        val checking = state is UpdateState.Checking
        FlatTonalButton(onClick = { vm.checkForUpdate() }, enabled = !checking) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (checking) {
                    SmallSpinner()
                } else {
                    KeryxIcon(KeryxIcons.Update, contentDescription = null, modifier = Modifier.size(18.dp))
                }
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_update_check_now))
            }
        }

        UpdateResultSection(
            state = state,
            onCheckForUpdate = { vm.checkForUpdate() },
            onStartDownload = { vm.startDownload() },
            onCancelDownload = { vm.cancelDownload() },
            onInstall = { vm.installUpdate() },
        )
    }
}

/**
 * The result of the check above: nothing yet, "up to date", a bare check failure, or the full
 * card once a release is (or was) known — see [availableUpdateOrNull]. Takes plain callbacks
 * rather than [SettingsViewModel] itself so it (and the two helpers below) can be exercised
 * directly from a Compose UI test against a bare [UpdateState], with no ViewModel/DI wiring needed.
 */
@Composable
internal fun UpdateResultSection(
    state: UpdateState,
    onCheckForUpdate: () -> Unit,
    onStartDownload: () -> Unit,
    onCancelDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    val update = availableUpdateOrNull(state)
    if (update == null) {
        when (state) {
            UpdateState.UpToDate -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.settings_update_check_up_to_date),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            is UpdateState.Failed -> {
                Spacer(Modifier.height(8.dp))
                Text(
                    stringResource(Res.string.settings_update_check_failed),
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.error,
                )
                Spacer(Modifier.height(4.dp))
                FlatTonalButton(onClick = onCheckForUpdate) {
                    Text(stringResource(Res.string.settings_update_retry))
                }
            }
            UpdateState.Idle, UpdateState.Checking -> Unit
            else -> Unit // unreachable: every other state carries an AvailableUpdate
        }
        return
    }

    Spacer(Modifier.height(12.dp))
    SettingsCard {
        Text(
            stringResource(Res.string.settings_version, AppInfo.version),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            stringResource(Res.string.settings_update_check_available, update.version),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        update.releaseNotes?.let { notes ->
            Text(
                plainTextReleaseNotes(notes),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }

        val installable = update.plan.isInstallable
        if (!installable) {
            Text(
                stringResource(Res.string.settings_update_manual_only),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        UpdateProgressSlot(state, onCancelDownload)

        if (state is UpdateState.Failed) {
            Text(
                userMessage(state.exception),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.error,
            )
        }

        PrimaryUpdateActionButton(state, installable, onStartDownload, onInstall)
        LinkRow(label = stringResource(Res.string.settings_update_open_release_page), url = update.releaseUrl)
    }
}

/** Fixed-height reserved for download/verify progress feedback — present (though empty) for
 * every state the card can be in, so reaching [UpdateState.Downloading]/[UpdateState.Verifying]
 * and leaving them again never shifts the primary action button below it. Only the *content*
 * inside is conditional, per the "reserve unconditionally" layout-stability rule. */
private val UPDATE_PROGRESS_SLOT_HEIGHT = 40.dp

/** Exposed for `UpdatesTabTest` to measure the slot directly, independent of every other element
 * around it whose own height can legitimately vary by state (the primary button's label, whether
 * release notes wrap to more lines, …). */
internal const val UPDATE_PROGRESS_SLOT_TEST_TAG = "update-progress-slot"

@Composable
private fun UpdateProgressSlot(state: UpdateState, onCancelDownload: () -> Unit) {
    Box(Modifier.fillMaxWidth().height(UPDATE_PROGRESS_SLOT_HEIGHT).testTag(UPDATE_PROGRESS_SLOT_TEST_TAG)) {
        when (state) {
            is UpdateState.Downloading -> {
                val percent = if (state.bytesTotal > 0) ((state.bytesDone * 100) / state.bytesTotal).toInt() else 0
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        KeryxLinearProgressBar(
                            progress = { if (state.bytesTotal > 0) state.bytesDone.toFloat() / state.bytesTotal else 0f },
                        )
                        Text(
                            stringResource(Res.string.settings_update_downloading, "$percent%"),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.width(8.dp))
                    FlatTextButton(onClick = onCancelDownload) { Text(stringResource(Res.string.common_cancel)) }
                }
            }
            is UpdateState.Verifying -> {
                Row(Modifier.fillMaxSize(), verticalAlignment = Alignment.CenterVertically) {
                    SmallSpinner()
                    Spacer(Modifier.width(8.dp))
                    Text(
                        stringResource(Res.string.settings_update_verifying),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            else -> Unit // reserved but empty — see this function's own KDoc
        }
    }
}

/** The card's own state-dependent action — omitted (not disabled) for a state with nothing
 * actionable at all ([UpdateState.Available] this install form can't act on), since that is a
 * permanent property of this install, not a merely temporary one. */
@Composable
private fun PrimaryUpdateActionButton(
    state: UpdateState,
    installable: Boolean,
    onStartDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    when (state) {
        is UpdateState.Available -> if (installable) {
            FlatButton(onClick = onStartDownload) { Text(stringResource(Res.string.settings_update_download)) }
        }
        is UpdateState.Ready -> FlatButton(onClick = onInstall) {
            Text(stringResource(Res.string.settings_update_install))
        }
        is UpdateState.Installing -> FlatButton(onClick = {}, enabled = false) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmallSpinner()
                Spacer(Modifier.width(8.dp))
                Text(stringResource(Res.string.settings_update_installing))
            }
        }
        is UpdateState.Failed -> if (state.update != null) {
            FlatTonalButton(onClick = onStartDownload) { Text(stringResource(Res.string.settings_update_retry)) }
        }
        else -> Unit // Downloading/Verifying: DownloadProgressRow above already covers the action (cancel)
    }
}

private fun availableUpdateOrNull(state: UpdateState): AvailableUpdate? = when (state) {
    is UpdateState.Available -> state.update
    is UpdateState.Downloading -> state.update
    is UpdateState.Verifying -> state.update
    is UpdateState.Ready -> state.update
    is UpdateState.Installing -> state.update
    is UpdateState.Failed -> state.update
    UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> null
}
