package works.merc.keryx.app.platform

import kotlin.experimental.ExperimentalNativeApi

@OptIn(ExperimentalNativeApi::class)
actual val isMacOs: Boolean = Platform.osFamily == OsFamily.MACOSX

@OptIn(ExperimentalNativeApi::class)
actual val isTouchPrimary: Boolean = Platform.osFamily == OsFamily.IOS

/** macOS has the system menu bar; iOS/iPadOS have none. */
actual val hasNativeAppMenu: Boolean = isMacOs

/** macOS can show a menu-bar extra; iOS/iPadOS have no system tray. */
actual val hasSystemTray: Boolean = isMacOs

/** Neither macOS nor iOS shows its own confirmation when an app copies to the pasteboard. */
actual val platformShowsOwnCopyConfirmation: Boolean = false

@OptIn(ExperimentalNativeApi::class)
actual val hostArchitecture: HostArchitecture = when (Platform.cpuArchitecture) {
    CpuArchitecture.ARM64 -> HostArchitecture.ARM64
    CpuArchitecture.X64 -> HostArchitecture.X86_64
    else -> HostArchitecture.UNKNOWN
}
