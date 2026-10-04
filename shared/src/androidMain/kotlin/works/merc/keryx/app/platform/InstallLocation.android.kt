package works.merc.keryx.app.platform

import works.merc.keryx.app.core.isSelfUpdateCheckSupported

/**
 * Android has no on-disk app bundle for an in-app update to replace — installing an APK always
 * goes through the OS's own `PackageInstaller`, whether the running install is
 * [InstallKind.ANDROID_SIDELOADED] or [InstallKind.ANDROID_STORE]. [installerPackageName] backs
 * both this and [selfUpdateCheckSupported], so the two agree on what counts as a store install (and on a build that opts out of self-updates).
 */
actual fun detectInstallLocation(): InstallLocation {
    val context = AndroidAppContext.application
    val kind = if (isSelfUpdateCheckSupported(installerPackageName(context), distributionAllowsSelfUpdate(context))) {
        InstallKind.ANDROID_SIDELOADED
    } else {
        InstallKind.ANDROID_STORE
    }
    return InstallLocation(kind, appRoot = null, launcherPath = null, parentWritable = false, translocated = false)
}
