package works.merc.keryx.app.domain

/** How handing a downloaded, verified update file off to the OS went. */
sealed interface InstallLaunchResult {
    /** The OS-level install/replace process was launched. On desktop the app should now exit — the
     * launched process is waiting for exactly that (see the platform actual's own KDoc) — and
     * *this result* is the trigger for it: it makes [UpdateRepository] emit
     * [UpdateRepository.installLaunched], the one signal `main.kt` exits on. On Android this is
     * unreachable: a successful `PackageInstaller` commit kills the process itself before a result
     * would ever be delivered. */
    data object Launched : InstallLaunchResult

    /** Android only: the OS needs the user's consent first (e.g. "install unknown apps" is not yet
     * granted for this app) — a consent screen was shown or requested. The app keeps running; the
     * caller should wait for the follow-up result rather than treat this as done. */
    data object AwaitingUserConsent : InstallLaunchResult

    /** Launching the OS-level install failed outright (not merely "user declined" — see
     * `UpdateException`'s own KDoc for how that's reported instead). */
    data class Failed(val reason: String) : InstallLaunchResult
}

/**
 * Hands a downloaded, [UpdateDownloader][works.merc.keryx.app.data.remote.UpdateDownloader]-verified
 * update file off to this OS's own install mechanism. Platform-specific, bound the same way
 * [OsNotificationSink] is: an `actual` under `platform/update/` per platform, wired into
 * [works.merc.keryx.app.di.platformModule]. The desktop and Android implementations don't share an
 * approach at all (self-replacing files in place vs. `PackageInstaller`), so this interface only
 * describes the seam between them, not a shared algorithm.
 */
interface UpdateInstaller {
    /** Whether this running environment can actually carry out [plan] — not just "what [updatePlan]
     * says should happen" but "is this instance currently allowed to" (e.g. Android's
     * `REQUEST_INSTALL_PACKAGES` permission and per-user consent, which [UpdatePlan] itself knows
     * nothing about). [UpdateRepository] only starts a download when this is `true`. */
    fun canInstall(plan: UpdatePlan): Boolean

    /**
     * Launches the OS-level install of the file at [filePath] (already downloaded and verified
     * against [update]'s asset digest). Returning [InstallLaunchResult.Launched] does not mean the
     * update is finished: the actual install/replace happens out-of-process (a detached helper
     * script on desktop, the OS installer on Android).
     *
     * "Launching" is not necessarily quick. Desktop unpacks and health-checks the whole bundle
     * first, which for a macOS app image means inflating ~200 MB and running two child processes, so
     * an implementation with blocking work must move it off the caller's dispatcher itself —
     * `UpdateRepository` calls this from the shared app scope, whose `Dispatchers.Default` pool is
     * also what every DB write and feed refresh runs on.
     */
    suspend fun install(filePath: String, update: AvailableUpdate): InstallLaunchResult
}
