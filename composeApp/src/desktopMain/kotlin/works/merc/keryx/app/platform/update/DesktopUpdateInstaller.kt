package works.merc.keryx.app.platform.update

import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.InstallLaunchResult
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.domain.UpdatePlan

/**
 * Placeholder desktop [UpdateInstaller]: [canInstall] always refuses, so [UpdateRepository] never
 * starts a download and the UI falls back to the release-page action it already had. Replaced by
 * the real macOS `.app` self-replace / Windows `msiexec` / portable-ZIP self-replace logic once
 * that lands — this stub exists so the domain layer above it (repository, UI, tray) can be built
 * and tested against the [UpdateInstaller] seam before the platform-specific half is implemented.
 */
internal object DesktopUpdateInstaller : UpdateInstaller {
    override fun canInstall(plan: UpdatePlan): Boolean = false

    override suspend fun install(filePath: String, update: AvailableUpdate): InstallLaunchResult =
        InstallLaunchResult.Failed("Not yet implemented")
}
