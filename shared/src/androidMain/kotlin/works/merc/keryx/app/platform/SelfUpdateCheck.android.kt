package works.merc.keryx.app.platform

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import works.merc.keryx.app.core.isSelfUpdateCheckSupported

/**
 * Resolves the package that installed this app, trying the API appropriate to this device first —
 * the version difference is contained entirely inside this one function, so callers never branch
 * on `Build.VERSION.SDK_INT` themselves.
 *
 * The app's own package name is always installed (we're running as it), so
 * [android.content.pm.PackageManager.NameNotFoundException] should never actually throw here, but
 * [runCatching] treats any failure as "unknown" rather than crashing a non-critical UX check.
 *
 * `internal` rather than `private`: [detectInstallLocation] reuses this exact same signal so the
 * "is this build allowed to check for updates" and "how would an update actually install" checks
 * can never disagree about what counts as a Play install.
 */
internal fun installerPackageName(context: Context): String? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
}.getOrNull()

/** Manifest `<meta-data>` an Android flavor sets to `false` to say its build must never self-update. */
internal const val SELF_UPDATE_CHECK_META_DATA = "works.merc.keryx.SELF_UPDATE_CHECK"

/**
 * Whether this build's own manifest permits an in-app update check. Only the `fdroid` flavor
 * declares [SELF_UPDATE_CHECK_META_DATA] (`false`) — F-Droid builds and signs the app itself and
 * updates it through its own client, so the check must stay off whichever installer happened to
 * deliver the APK. Read from the merged manifest at runtime rather than branching on the flavor
 * name, like `AndroidUpdateInstaller` does for `REQUEST_INSTALL_PACKAGES`. A missing key or any
 * lookup failure counts as "allowed": only an explicit opt-out disables the check.
 */
internal fun distributionAllowsSelfUpdate(context: Context): Boolean = runCatching {
    val metaData = context.packageManager
        .getApplicationInfo(context.packageName, PackageManager.GET_META_DATA)
        .metaData
    metaData?.getBoolean(SELF_UPDATE_CHECK_META_DATA, true) ?: true
}.getOrDefault(true)

/** Evaluated once per process — the installer of an already-running app cannot change. */
actual val selfUpdateCheckSupported: Boolean by lazy {
    val context = AndroidAppContext.application
    isSelfUpdateCheckSupported(installerPackageName(context), distributionAllowsSelfUpdate(context))
}
