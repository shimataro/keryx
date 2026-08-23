package works.merc.keryx.app.platform

import android.content.Context
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
 */
private fun installerPackageName(context: Context): String? = runCatching {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
    } else {
        @Suppress("DEPRECATION")
        context.packageManager.getInstallerPackageName(context.packageName)
    }
}.getOrNull()

/** Evaluated once per process — the installer of an already-running app cannot change. */
actual val selfUpdateCheckSupported: Boolean by lazy {
    isSelfUpdateCheckSupported(installerPackageName(AndroidAppContext.application))
}
