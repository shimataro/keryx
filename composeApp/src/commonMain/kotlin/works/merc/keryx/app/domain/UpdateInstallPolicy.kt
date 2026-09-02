package works.merc.keryx.app.domain

import works.merc.keryx.app.platform.InstallKind
import works.merc.keryx.app.platform.InstallLocation

/**
 * What an in-app update should actually do here. Distinct from [UpdateAssetKind]/[selectUpdateAsset]
 * (which decide *which file*): this decides what to do with that file once it's picked. Pure
 * function of [InstallLocation] and the already-selected [UpdateAsset] — never touches the network
 * or filesystem, so it can be tested without either.
 */
sealed interface UpdatePlan {
    /** No in-app update path exists here at all (a development run, a Play-installed Android
     * build, or an install form [detectInstallLocation][works.merc.keryx.app.platform.detectInstallLocation]
     * couldn't recognize). The update check itself is also not offered in this case — see
     * [works.merc.keryx.app.platform.selfUpdateCheckSupported]. */
    data object NotOffered : UpdatePlan

    /** An update was found, but this install form can't apply it in place (no matching asset in
     * the release, a Linux deb/rpm install, macOS App Translocation, an unwritable install
     * directory, …) — fall back to opening the release page, same as before this feature existed. */
    data object OpenReleasePage : UpdatePlan

    /** Download [asset] and replace this install's files in place, then relaunch (macOS `.app`,
     * Windows/Linux portable ZIP). */
    data class SelfReplace(val asset: UpdateAsset) : UpdatePlan

    /** Download [asset] and hand it to the OS's own installer (Windows `msiexec`, Android
     * `PackageInstaller`) rather than replacing files directly. */
    data class RunInstaller(val asset: UpdateAsset) : UpdatePlan
}

/** Whether [UpdatePlan] represents something an in-app update can actually carry out, as opposed
 * to falling back to the release page or not being offered at all — the one thing the Updates tab
 * (`UpdatesTab.kt`) and the tray (`tray/KeryxTray.kt`'s `trayUpdateEntry`) both need to decide
 * whether an [UpdateState.Available] update gets a "download" affordance at all. */
internal val UpdatePlan.isInstallable: Boolean
    get() = this is UpdatePlan.SelfReplace || this is UpdatePlan.RunInstaller

/**
 * Decides [UpdatePlan] for [location] given the asset (if any) [UpdateChecker] already selected for
 * it. [asset] is `null` exactly when [selectUpdateAsset] found nothing installable — that alone
 * forces [UpdatePlan.OpenReleasePage] for every [InstallKind] that would otherwise self-replace or
 * run an installer.
 */
fun updatePlan(location: InstallLocation, asset: UpdateAsset?): UpdatePlan = when (location.kind) {
    InstallKind.DEVELOPMENT, InstallKind.ANDROID_STORE, InstallKind.UNKNOWN -> UpdatePlan.NotOffered

    InstallKind.LINUX_PACKAGE -> UpdatePlan.OpenReleasePage

    InstallKind.MAC_APP_BUNDLE ->
        if (asset == null || location.translocated || !location.parentWritable) {
            UpdatePlan.OpenReleasePage
        } else {
            UpdatePlan.SelfReplace(asset)
        }

    InstallKind.WINDOWS_PORTABLE, InstallKind.LINUX_PORTABLE ->
        if (asset == null || !location.parentWritable) UpdatePlan.OpenReleasePage else UpdatePlan.SelfReplace(asset)

    InstallKind.WINDOWS_INSTALLED, InstallKind.ANDROID_SIDELOADED ->
        if (asset == null) UpdatePlan.OpenReleasePage else UpdatePlan.RunInstaller(asset)
}
