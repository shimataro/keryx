package works.merc.keryx.app.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.jetbrains.compose.resources.stringResource
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.UpdateState
import works.merc.keryx.app.domain.plainTextReleaseNotes
import works.merc.keryx.app.domain.update
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
import works.merc.keryx.app.resources.settings_update_ready
import works.merc.keryx.app.resources.settings_update_retry
import works.merc.keryx.app.resources.settings_update_verifying

/**
 * Updates tab: the update status/action — once one is known — leads, driven by
 * [SettingsViewModel.updateState]; the update-check interval and the manual "check for update"
 * trigger sit below a divider, deprioritized as ordinary configuration rather than the thing most
 * worth a glance. A newly available update is the reason someone opens this tab in the first
 * place, so it (and its one actionable button — download, install, retry, whichever applies) reads
 * first; "check for update" is something to reach for only once that story is already known.
 *
 * Opening the tab starts a check if nothing has run yet, same as before this composable was
 * rewritten against the full [UpdateState] machine — equivalent to one press of "check now", so it
 * never perturbs the automatic check schedule.
 *
 * [vm.updateState] is deliberately *not* collected here: a download in progress emits an
 * [UpdateState.Downloading] tick per percent, and collecting it in this outer function would
 * invalidate this whole composable — including [SegmentedControl] and the "check now" button below
 * the divider, neither of which cares about download progress — on every one of those ticks. Each
 * of [UpdateStatusAndAction] and [CheckNowButton] below collects the flow itself instead, confining
 * that recomposition to the (small) scope that actually needs it.
 *
 * @param vm The view model providing update settings, state, and actions.
 */
@Composable
internal fun UpdatesTabContent(vm: SettingsViewModel) {
    val settings by vm.localSettings.collectAsState()

    LaunchedEffect(Unit) {
        if (vm.updateState.value is UpdateState.Idle) vm.checkForUpdate()
    }

    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        UpdateStatusAndAction(vm)

        Spacer(Modifier.height(16.dp))
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
        Spacer(Modifier.height(16.dp))

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
        CheckNowButton(vm)
    }
}

/** The status/action block that leads the tab (see [UpdatesTabContent]'s own KDoc for why this is
 * its own composable rather than inlined). */
@Composable
private fun UpdateStatusAndAction(vm: SettingsViewModel) {
    val state by vm.updateState.collectAsState()
    UpdateResultSection(
        state = state,
        onCheckForUpdate = { vm.checkForUpdate() },
        onStartDownload = { vm.startDownload() },
        onCancelDownload = { vm.cancelDownload() },
        onInstall = { vm.installUpdate() },
    )
}

/** The manual "check for update" button (see [UpdatesTabContent]'s own KDoc for why this is its
 * own composable). [checking] is a [derivedStateOf] rather than a plain `is` check against the
 * collected state directly, so this button's own recomposition scope only re-runs when that
 * boolean actually flips — not on every [UpdateState.Downloading]/[UpdateState.Verifying] tick,
 * which this button has no visual dependency on in the first place. */
@Composable
private fun CheckNowButton(vm: SettingsViewModel) {
    val state = vm.updateState.collectAsState()
    val checking by remember { derivedStateOf { state.value is UpdateState.Checking } }
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
}

/** Floor height for [UpdateResultSection]'s status/action area (everything up to, but not
 * including, the release-notes card and link row) — see that function's own KDoc for why. Sized to
 * the "update available, installable" case: 12dp top spacer + a ~40dp headline row + 4dp spacer +
 * [UPDATE_PROGRESS_SLOT_HEIGHT]'s own 40dp. */
private val UPDATE_STATUS_ACTION_MIN_HEIGHT = 96.dp

/** Exposed for `UpdatesTabTest` to measure the reserved area directly, the same way
 * [UPDATE_PROGRESS_SLOT_TEST_TAG] does for the progress slot nested inside it. */
internal const val UPDATE_STATUS_ACTION_TEST_TAG = "update-status-action"

/**
 * The result of the check above: nothing yet, "up to date", a bare check failure, or the full
 * card once a release is (or was) known — see [UpdateState.update]. Takes plain callbacks
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
    val update = state.update

    // Reserved unconditionally — independent of which branch below actually renders — for the
    // same reason UpdateProgressSlot's own height is: without it, the interval control and "check
    // for update" button beneath this tab's divider jumped every time the check resolved (Idle /
    // Checking render nothing at all here, so the very first resolved outcome — UpToDate, Failed,
    // or an available update — pushed everything below down by however tall that outcome's content
    // happened to be). heightIn(min=...) only sets a *floor*: a state whose content genuinely needs
    // more room (e.g. a long Failed error message) still grows past it rather than being clipped.
    // The release-notes card and link row further below are deliberately NOT part of this reserved
    // area — their height is separately bounded (see the notes Text's own `maxLines`).
    Box(Modifier.heightIn(min = UPDATE_STATUS_ACTION_MIN_HEIGHT).testTag(UPDATE_STATUS_ACTION_TEST_TAG)) {
        Column(Modifier.fillMaxWidth()) {
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
            } else {
                Spacer(Modifier.height(12.dp))
                val installable = update.installable
                UpdateHeadlineRow(state, update, installable, onStartDownload, onInstall)
                Spacer(Modifier.height(4.dp))
                UpdateProgressSlot(state, onCancelDownload)

                if (!installable) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        stringResource(Res.string.settings_update_manual_only),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                if (state is UpdateState.Failed) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        userMessage(state.exception),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
        }
    }
    if (update == null) return

    // The status/action block above is a plain, unboxed banner — always the first thing seen,
    // never sharing a frame with the release notes below (see UpdatesTab.kt's own module KDoc for
    // why): what to do about an update and what changed in it are different kinds of information,
    // and only the latter is "read more" content worth setting apart in its own panel — matching
    // how Sparkle/macOS's own Software Update present an update banner over a framed notes area.
    update.releaseNotes?.let { notes ->
        Spacer(Modifier.height(12.dp))
        SettingsCard(modifier = Modifier.testTag(UPDATE_RELEASE_NOTES_CARD_TEST_TAG)) {
            val text = remember(notes) { plainTextReleaseNotes(notes) }
            Text(
                text,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 12,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }

    Spacer(Modifier.height(8.dp))
    LinkRow(label = stringResource(Res.string.settings_update_open_release_page), url = update.releaseUrl)
}

/** Exposed for `UpdatesTabTest` to confirm the release notes are the *only* thing inside this
 * card — the status/action block above it is deliberately unboxed (see this file's own KDoc). */
internal const val UPDATE_RELEASE_NOTES_CARD_TEST_TAG = "update-release-notes-card"

/**
 * The card's single hero line: what's currently true about the update, and — trailing, on the
 * same row — the one button that acts on it (download / install / retry), so the most useful
 * thing to do about an update is never more than one glance and one click away.
 *
 * Downloading and Verifying keep the same disabled Download button structurally present (matching
 * [UpdateState.Installing]'s own disabled button below) rather than omitting it: an omitted button
 * shrinks this row's height (no button content to size against, just the headline text), which
 * shifted [UpdateProgressSlot] right below up and down as a download started/finished — a real,
 * if small, layout jump *inside* [UpdateResultSection]'s own reserved floor height (see that
 * function's own KDoc), which only guards the row's *total* height, not this internal wobble.
 * [UpdateProgressSlot] remains the actual progress feedback (bar/percent, or a spinner) and the
 * Cancel action; this button is disabled precisely because there is nothing to click here while
 * either is in flight.
 */
internal const val UPDATE_HEADLINE_ROW_TEST_TAG = "update-headline-row"

@Composable
private fun UpdateHeadlineRow(
    state: UpdateState,
    update: AvailableUpdate,
    installable: Boolean,
    onStartDownload: () -> Unit,
    onInstall: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().testTag(UPDATE_HEADLINE_ROW_TEST_TAG),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val headline = if (state is UpdateState.Ready) {
            stringResource(Res.string.settings_update_ready, update.version)
        } else {
            stringResource(Res.string.settings_update_check_available, update.version)
        }
        Text(
            headline,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        when (state) {
            is UpdateState.Available -> if (installable) {
                Spacer(Modifier.width(8.dp))
                FlatButton(onClick = onStartDownload) { Text(stringResource(Res.string.settings_update_download)) }
            }
            is UpdateState.Downloading, is UpdateState.Verifying -> if (installable) {
                Spacer(Modifier.width(8.dp))
                FlatButton(onClick = {}, enabled = false) { Text(stringResource(Res.string.settings_update_download)) }
            }
            is UpdateState.Ready -> {
                Spacer(Modifier.width(8.dp))
                FlatButton(onClick = onInstall) { Text(stringResource(Res.string.settings_update_install)) }
            }
            is UpdateState.Installing -> {
                Spacer(Modifier.width(8.dp))
                FlatButton(onClick = {}, enabled = false) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        SmallSpinner()
                        Spacer(Modifier.width(8.dp))
                        Text(stringResource(Res.string.settings_update_installing))
                    }
                }
            }
            is UpdateState.Failed -> {
                Spacer(Modifier.width(8.dp))
                FlatTonalButton(onClick = onStartDownload) { Text(stringResource(Res.string.settings_update_retry)) }
            }
            else -> Unit // unreachable here: Idle/Checking/UpToDate never carry an AvailableUpdate (see UpdateResultSection)
        }
    }
}

/** Fixed-height reserved for download/verify progress feedback — present (though empty) for
 * every state the card can be in, so reaching [UpdateState.Downloading]/[UpdateState.Verifying]
 * and leaving them again never shifts what's below this slot: the manual-only/error caption (when
 * present), then [UpdateResultSection]'s release-notes card and its trailing `LinkRow`. Only the
 * *content* inside this slot is conditional, per the "reserve unconditionally" layout-stability
 * rule — the same rule [UPDATE_STATUS_ACTION_MIN_HEIGHT] applies one level up, to this slot plus
 * everything above it. */
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

