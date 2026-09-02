package works.merc.keryx.app.platform.update

import android.app.PendingIntent
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.net.Uri
import android.provider.Settings
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.AvailableUpdate
import works.merc.keryx.app.domain.InstallLaunchResult
import works.merc.keryx.app.domain.UpdateAssetKind
import works.merc.keryx.app.domain.UpdateInstaller
import works.merc.keryx.app.domain.UpdatePlan
import works.merc.keryx.app.domain.canInstallAndroidApkUpdate
import works.merc.keryx.app.platform.AndroidAppContext
import java.io.File
import java.io.IOException

private const val TAG = "AndroidUpdateInstaller"

/** Never registered in the manifest — this app's own process, matched by explicit package. A
 * fresh dynamic registration backs every session (see [AndroidUpdateInstaller.installResultPendingIntent]). */
private const val INSTALL_RESULT_ACTION = "works.merc.keryx.app.UPDATE_INSTALL_RESULT"
private const val INSTALL_SESSION_NAME = "keryx_update"

/**
 * Android [UpdateInstaller]: hands a downloaded, digest-verified APK off to the OS's own
 * `PackageInstaller`. Unlike the desktop actual, this never touches the filesystem beyond reading
 * the file — no self-replace, no detached script — since Android's installer is the only
 * mechanism allowed to update an installed package.
 *
 * [canInstall] and the defensive re-check inside [install] both read
 * `PackageManager.canRequestPackageInstalls()`, which already folds together the two facts
 * [UpdateInstaller]'s own KDoc calls out: the `REQUEST_INSTALL_PACKAGES` permission being declared
 * at all (only the "github" distribution flavor's manifest does — see
 * `androidApp/build.gradle.kts`'s `flavorDimensions`, so a Play-flavored APK sideloaded outside
 * Play still correctly refuses here) and the user's per-app "install unknown apps" consent. The
 * pure decision itself lives in [canInstallAndroidApkUpdate] (`domain/UpdateInstallPolicy.kt`) so
 * it's exercisable by `commonTest` — this module has no JVM-testable unit-test source set (see
 * `docs/testing.md`), so everything below that pure function is necessarily uncovered by automated
 * tests, the same as this app's other Android OS integrations (`AndroidNotificationSink`,
 * `WorkManager` scheduling, …).
 */
class AndroidUpdateInstaller internal constructor(
    private val context: Context,
) : UpdateInstaller {

    constructor() : this(AndroidAppContext.application)

    override fun canInstall(plan: UpdatePlan): Boolean =
        canInstallAndroidApkUpdate(plan, context.packageManager.canRequestPackageInstalls())

    override suspend fun install(filePath: String, update: AvailableUpdate): InstallLaunchResult {
        val asset = update.asset
        if (asset == null || update.plan !is UpdatePlan.RunInstaller || asset.kind != UpdateAssetKind.ANDROID_APK) {
            return InstallLaunchResult.Failed("This install form has no in-app installer")
        }
        // Re-checked here (not just trusted from the canInstall() the UI gated the download on):
        // the user can revoke "install unknown apps" consent at any point between starting the
        // download and clicking install.
        if (!context.packageManager.canRequestPackageInstalls()) {
            return requestInstallConsent()
        }
        return commitPackageInstallerSession(filePath)
    }

    /**
     * Directs the user to the per-app "install unknown apps" system settings screen —
     * `REQUEST_INSTALL_PACKAGES` being declared is not by itself enough; the user must also grant
     * this toggle at least once. The app is not exited: [works.merc.keryx.app.domain.UpdateRepository]
     * leaves its state at `Ready` (see [InstallLaunchResult.AwaitingUserConsent]'s own KDoc) so a
     * later "Install" click retries once the user returns having granted it.
     */
    private fun requestInstallConsent(): InstallLaunchResult {
        val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return try {
            context.startActivity(intent)
            InstallLaunchResult.AwaitingUserConsent
        } catch (e: ActivityNotFoundException) {
            InstallLaunchResult.Failed("Could not open the \"install unknown apps\" settings screen: ${e.message}")
        }
    }

    /** Streams [filePath] into a new `PackageInstaller` session and commits it. Committing only
     * *launches* the OS-level install (see [InstallLaunchResult.Launched]'s own KDoc) — the
     * confirmation dialog and the actual install/replace happen asynchronously, delivered through
     * [installResultPendingIntent]'s receiver. */
    private fun commitPackageInstallerSession(filePath: String): InstallLaunchResult {
        val apkFile = File(filePath)
        val installer = context.packageManager.packageInstaller
        val sessionId = try {
            val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                setAppPackageName(context.packageName)
            }
            installer.createSession(params)
        } catch (e: IOException) {
            return InstallLaunchResult.Failed("Could not create an install session: ${e.message}")
        } catch (e: SecurityException) {
            return InstallLaunchResult.Failed("Not permitted to create an install session: ${e.message}")
        }

        return try {
            installer.openSession(sessionId).use { session ->
                session.openWrite(INSTALL_SESSION_NAME, 0, apkFile.length()).use { out ->
                    apkFile.inputStream().use { it.copyTo(out) }
                    session.fsync(out)
                }
                session.commit(installResultPendingIntent(sessionId).intentSender)
            }
            InstallLaunchResult.Launched
        } catch (e: IOException) {
            installer.abandonSession(sessionId)
            InstallLaunchResult.Failed("Could not write the update to the install session: ${e.message}")
        } catch (e: SecurityException) {
            installer.abandonSession(sessionId)
            InstallLaunchResult.Failed("Not permitted to commit the install session: ${e.message}")
        }
    }

    /**
     * A [PendingIntent] the OS fires — with `PackageInstaller.EXTRA_STATUS`/`EXTRA_INTENT` added to
     * the [Intent] — once this session's outcome is known. Must be [PendingIntent.FLAG_MUTABLE]:
     * an immutable one would silently drop those extras (enforced from API 31 onward). Delivered
     * to a receiver registered dynamically per session, never in the manifest — a static receiver
     * would have no way to know which in-flight session a broadcast belongs to.
     */
    private fun installResultPendingIntent(sessionId: Int): PendingIntent {
        val receiver = InstallResultReceiver(context)
        ContextCompat.registerReceiver(
            context, receiver, IntentFilter(INSTALL_RESULT_ACTION), ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        val intent = Intent(INSTALL_RESULT_ACTION).setPackage(context.packageName)
        return PendingIntent.getBroadcast(
            context, sessionId, intent, PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}

/**
 * Handles the asynchronous result of a single [PackageInstaller] session commit. Registered
 * dynamically (never in the manifest) and unregisters itself on its one delivery — a fresh
 * instance backs every session (see [AndroidUpdateInstaller.installResultPendingIntent]), so
 * there is never a second broadcast to route.
 *
 * [PackageInstaller.STATUS_PENDING_USER_ACTION] is the routine "confirm this install" dialog every
 * APK install shows — distinct from the one-time "install unknown apps" consent
 * [AndroidUpdateInstaller.requestInstallConsent] handles *before* the session is even created —
 * and its `EXTRA_INTENT` needs `FLAG_ACTIVITY_NEW_TASK` since it's launched from a receiver, not
 * an `Activity`.
 *
 * A terminal failure (most notably `STATUS_FAILURE_INCOMPATIBLE` — the sideloaded APK's signature
 * doesn't match this install's, e.g. because the Play-signing keystore mismatch `docs/build.md`
 * describes wasn't followed; the guidance there is "uninstall and reinstall, which loses local
 * data") is only logged here, not fed back into
 * [works.merc.keryx.app.domain.UpdateRepository.state]: that state already moved to `Installing`
 * synchronously when [AndroidUpdateInstaller.install] returned [InstallLaunchResult.Launched], and
 * [UpdateInstaller] has no channel for a late async result to revise it afterward — a real, if
 * narrow, UX gap this in-app installer accepts for now (the app is left showing "Installing…"
 * rather than reverting to a retryable state). A successful install never reaches this branch at
 * all: the OS kills this process as part of committing it.
 */
private class InstallResultReceiver(private val context: Context) : BroadcastReceiver() {
    override fun onReceive(receivedContext: Context, intent: Intent) {
        context.unregisterReceiver(this)
        when (val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirmIntent = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_INTENT, Intent::class.java)
                confirmIntent?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                confirmIntent?.let { context.startActivity(it) }
            }
            PackageInstaller.STATUS_SUCCESS -> Unit // Unreachable in practice — the OS kills this process first.
            else -> {
                val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
                Log.warn(TAG, "Update install finished with status $status: $message")
            }
        }
    }
}
