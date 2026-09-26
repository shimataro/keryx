package works.merc.keryx.app.platform

import platform.Foundation.NSBundle

/**
 * Apple builds never self-replace (App Store or Sparkle update them), so there is no install kind
 * for the updater to act on — [InstallKind.UNKNOWN], which the update policy treats as "offer the
 * release page at most".
 */
actual fun detectInstallLocation(): InstallLocation = InstallLocation(
    kind = InstallKind.UNKNOWN,
    appRoot = NSBundle.mainBundle.bundlePath,
    launcherPath = null,
    parentWritable = false,
    translocated = false,
)
