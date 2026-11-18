package works.merc.keryx.app

import works.merc.keryx.app.appmenu.AppMenuConnection
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.isLinux
import works.merc.keryx.app.tray.SniConnection
import java.io.File
import java.util.concurrent.atomic.AtomicReference

private const val LOG_TAG = "Main"

/**
 * Takes the single-instance lock, or — when another instance already holds it — forwards this
 * launch's activation (with [incomingArg], if any) to that instance.
 *
 * @return The coordinator holding the lock, or `null` when this process is not the primary instance
 * and should exit.
 */
internal fun acquirePrimaryInstanceOrForward(incomingArg: String?): SingleInstanceCoordinator? {
    val singleInstanceCoordinator = SingleInstanceCoordinator(File(AppDirs.appDataDir()))
    if (!singleInstanceCoordinator.tryAcquireLock()) {
        // Another instance is already running. On macOS the OAuth redirect URI and an opened .opml
        // file are both delivered via an Apple Event (setOpenURIHandler / setOpenFileHandler), not
        // argv, so incomingArg is null here and only a plain activation is forwarded — see the
        // diagnostic note in docs/sync-architecture.md.
        Log.info(LOG_TAG, "Single-instance lock held by another instance; forwarding activation (hasArg=${incomingArg != null}) and exiting")
        val forwarded = singleInstanceCoordinator.signalRunningInstance(incomingArg)
        if (!forwarded) {
            Log.warn(LOG_TAG, "Failed to forward activation (hasArg=${incomingArg != null}) to the running instance")
        }
        return null
    }
    Log.info(LOG_TAG, "Acquired single-instance lock; running as primary instance from ${currentExecutablePath()}")
    return singleInstanceCoordinator
}

/**
 * The Linux D-Bus integrations opened at startup; each is `null` where unavailable (and always
 * off Linux).
 *
 * @property sniConnection The StatusNotifierItem tray connection, or `null` to fall back to the AWT tray.
 * @property appMenuConnection The KDE Global Menu export connection, or `null` for the in-window menu bar only.
 * @property resolvedAppMenuXid The window XID the app menu was registered for, read by its shutdown hook.
 */
internal class LinuxDBusIntegrations(
    val sniConnection: SniConnection?,
    val appMenuConnection: AppMenuConnection?,
    val resolvedAppMenuXid: AtomicReference<Long?>,
)

/**
 * Opens the Linux tray and global-menu D-Bus connections (in that order) and registers a shutdown
 * hook closing each one that opened. Call before `application {}` so the tray is up even with
 * startMinimized.
 */
internal fun openLinuxDBusIntegrations(): LinuxDBusIntegrations {
    // Linux: java.awt.SystemTray cannot draw a transparent icon on X11 (XTrayIconPeer fills the
    // canvas with the component background before drawing, and XSystemTrayPeer never adopts the
    // tray manager's ARGB visual), so the icon always ends up inside a white box. Use the native
    // StatusNotifierItem protocol when a host is available; null falls back to the AWT tray.
    // Resolved here, before the window exists, so the tray is up even with startMinimized.
    val sniConnection = if (isLinux) SniConnection.tryCreate() else null
    if (sniConnection != null) {
        addShutdownHook { sniConnection.close() }
    }

    // Linux/KDE Global Menu: if the com.canonical.AppMenu.Registrar is present, export the app menu
    // over D-Bus so it can appear in the top-panel "Application Menu Bar" widget or titlebar button.
    // null (every non-KDE environment) leaves the in-window menu bar behaving exactly as before.
    // Independent of the tray (own connection, own lifecycle).
    val appMenuConnection = if (isLinux) AppMenuConnection.tryCreate() else null
    val resolvedAppMenuXid = AtomicReference<Long?>(null)
    if (appMenuConnection != null) {
        addShutdownHook { appMenuConnection.close(resolvedAppMenuXid.get()) }
    }
    return LinuxDBusIntegrations(sniConnection, appMenuConnection, resolvedAppMenuXid)
}
