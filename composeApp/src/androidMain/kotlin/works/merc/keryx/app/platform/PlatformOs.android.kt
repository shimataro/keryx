package works.merc.keryx.app.platform

import android.os.Build

actual val isMacOs: Boolean = false

/** Android's primary pointer is touch. */
actual val isTouchPrimary: Boolean = true

/** Android has no application menu bar equivalent; Settings/About need their own in-pane entry
 * points (see the commonMain `expect`'s KDoc). */
actual val hasNativeAppMenu: Boolean = false

/** Android has no system tray equivalent; the background story relies on the OS notification dot
 * instead (see `background-update.md`). */
actual val hasSystemTray: Boolean = false

/** Android 13 (API 33, TIRAMISU) introduced the system's own clipboard-copy confirmation — see
 * the commonMain `expect`'s KDoc for the Google guidance this follows. */
actual val platformShowsOwnCopyConfirmation: Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

/** Android ships a single universal APK (no per-ABI split — see `androidApp/build.gradle.kts`), so
 * this value is never consulted by [works.merc.keryx.app.domain.selectUpdateAsset]. Implemented
 * honestly anyway (from the device's primary ABI) rather than a hardcoded placeholder, since the
 * `expect` makes no such "unused on this platform" guarantee. */
actual val hostArchitecture: HostArchitecture = when (Build.SUPPORTED_ABIS.firstOrNull()) {
    "x86_64" -> HostArchitecture.X86_64
    "arm64-v8a" -> HostArchitecture.ARM64
    else -> HostArchitecture.UNKNOWN
}
