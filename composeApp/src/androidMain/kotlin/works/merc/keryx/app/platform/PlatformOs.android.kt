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
