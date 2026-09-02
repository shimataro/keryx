package works.merc.keryx.app.platform.update

import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.InstallLaunchResult
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.domain.UpdatePlan

/**
 * Placeholder Android [UpdateInstaller]: [canInstall] always refuses, so [UpdateRepository] never
 * starts a download and the UI falls back to the release-page action it already had. Replaced by
 * the real `PackageInstaller` session logic (with its `REQUEST_INSTALL_PACKAGES` /
 * `canRequestPackageInstalls()` gating) once that lands — this stub exists so the domain layer
 * above it can be built and tested against the [UpdateInstaller] seam first.
 */
internal object AndroidUpdateInstaller : UpdateInstaller {
    override fun canInstall(plan: UpdatePlan): Boolean = false

    override suspend fun install(filePath: String, update: AvailableUpdate): InstallLaunchResult =
        InstallLaunchResult.Failed("Not yet implemented")
}
