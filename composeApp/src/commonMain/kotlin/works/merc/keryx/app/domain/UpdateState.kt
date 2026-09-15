package works.merc.keryx.app.domain

import works.merc.keryx.app.core.UpdateException
import works.merc.keryx.app.platform.InstallLocation

/**
 * [UpdateRepository]'s state machine — the single source of truth the tray, the notification
 * center, and the settings Updates tab all read from.
 */
sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val update: AvailableUpdate) : UpdateState
    data class Downloading(val update: AvailableUpdate, val bytesDone: Long, val bytesTotal: Long) : UpdateState
    data class Verifying(val update: AvailableUpdate) : UpdateState

    /** [filePath] is the verified download, ready to hand to [UpdateInstaller.install]. */
    data class Ready(val update: AvailableUpdate, val filePath: String) : UpdateState
    data class Installing(val update: AvailableUpdate) : UpdateState

    /**
     * @param update The update this failure happened while acting on, or `null` when the check
     *   itself is what failed (nothing was ever found to act on).
     */
    data class Failed(val update: AvailableUpdate?, val exception: UpdateException) : UpdateState
}

/** The version an in-progress or completed download/install currently occupies on disk — the one
 * directory [UpdateRepository]'s sweep must never delete out from under it. `null` when nothing is
 * using disk space right now. */
internal fun updateVersionInUse(state: UpdateState): String? = when (state) {
    is UpdateState.Downloading -> state.update.version
    is UpdateState.Verifying -> state.update.version
    is UpdateState.Ready -> state.update.version
    is UpdateState.Installing -> state.update.version
    is UpdateState.Available, is UpdateState.Failed, UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> null
}

/**
 * Applies a fresh [UpdateChecker] result on top of [current], the one place all of this state
 * machine's "don't regress an in-progress or completed action" rules live — pure and independent
 * of the network/filesystem so it can be tested as such.
 *
 * - A download/verify/install already underway is never interrupted by a check completing (the
 *   check that started it has already moved past [UpdateState.Checking] into one of these, and a
 *   *later*, unrelated periodic check must not clobber it).
 * - [UpdateState.Ready] survives a check that reports the *same* version again (including
 *   [UpdateStatus.UpToDate], which is what the just-downloaded version now looks like from the
 *   checker's point of view) — only a genuinely *newer* version replaces it, handing the user a
 *   fresh [UpdateState.Available] for that one instead. "Genuinely newer" now also requires the
 *   asset's digest to match: a release rebuilt under the same tag (the release deleted and
 *   recreated after a failed upload, say) changes what [status.asset] hashes to without changing
 *   [status.version], and treating that as "the same download" would hand a stale, already-verified
 *   file to the installer instead of fetching the rebuilt one.
 * - [UpdateStatus.Failed] (the check itself failing, e.g. no network) never overwrites
 *   [UpdateState.Ready] either — a transient check failure is not a reason to forget an update
 *   that's already sitting on disk, verified and ready to install.
 * - [awaitsReleaseAsset] folds a release that exists but has no asset for this install form yet
 *   into the same branch as [UpdateStatus.UpToDate] — see its own KDoc for why: the release
 *   workflow publishes the GitHub release before attaching the built packages, so a check landing
 *   in that gap must not treat it as a durable "no in-app update, use the release page" verdict.
 *   [UpdateRepository] is what turns this into a short-lived watch instead of silence.
 */
internal fun nextStateAfterCheck(
    current: UpdateState,
    status: UpdateStatus,
    location: InstallLocation,
    // Resolves AvailableUpdate.installable — see its own KDoc. Defaulted to the plan alone (no
    // platform check) so the many call sites in UpdateStateMachineTest that don't care about this
    // distinction don't all need to supply one; UpdateRepository.check() passes its own canInstall.
    canInstall: (UpdatePlan) -> Boolean = UpdatePlan::isInstallable,
): UpdateState {
    if (current is UpdateState.Downloading || current is UpdateState.Verifying || current is UpdateState.Installing) {
        return current
    }
    return when (status) {
        is UpdateStatus.UpToDate -> current as? UpdateState.Ready ?: UpdateState.UpToDate
        is UpdateStatus.Failed -> current as? UpdateState.Ready
            ?: UpdateState.Failed(current.update, status.exception)
        is UpdateStatus.Available -> {
            if (awaitsReleaseAsset(location, status.asset)) {
                // The release exists but carries no asset for this install form yet — from here
                // that is indistinguishable from "not released yet", not "get it from the release
                // page". See awaitsReleaseAsset's own KDoc.
                current as? UpdateState.Ready ?: UpdateState.UpToDate
            } else if (current is UpdateState.Ready &&
                current.update.version == status.version &&
                current.update.asset?.sha256 == status.asset?.sha256
            ) {
                current
            } else {
                val plan = updatePlan(location, status.asset)
                UpdateState.Available(
                    AvailableUpdate(status.version, status.url, status.releaseNotes, status.asset, plan, canInstall(plan)),
                )
            }
        }
    }
}

/** The [AvailableUpdate] any state that carries one is currently showing — every state past
 * [UpdateState.Idle]/[UpdateState.Checking]/[UpdateState.UpToDate], `null` for those three. The
 * single definition both [UpdatesTab.kt] (deciding what to render) and [nextStateAfterCheck]
 * (recovering the update a bare check failure happened while acting on) use, so a state added later
 * that should carry an update can't have one of the two call sites quietly forget it. */
internal val UpdateState.update: AvailableUpdate?
    get() = when (this) {
        is UpdateState.Available -> update
        is UpdateState.Downloading -> update
        is UpdateState.Verifying -> update
        is UpdateState.Ready -> update
        is UpdateState.Installing -> update
        is UpdateState.Failed -> update
        UpdateState.Idle, UpdateState.Checking, UpdateState.UpToDate -> null
    }
