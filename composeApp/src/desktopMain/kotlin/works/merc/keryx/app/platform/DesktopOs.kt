package works.merc.keryx.app.platform

/** The lowercased `os.name` system property, shared so every OS-name-derived check reads it once. */
internal val osName = System.getProperty("os.name")?.lowercase() ?: ""

/**
 * Whether this desktop JVM is running on macOS. Gates the platform integrations that only exist
 * there (screen menu bar, merged title bar, Dock activation policy, Aqua-specific styling), and
 * (via the commonMain `expect val` this backs) the `Return`-vs-`F2` rename shortcut convention.
 */
actual val isMacOs = osName.contains("mac")

/** A desktop OS's primary pointer is always a precise mouse/trackpad, never touch. */
actual val isTouchPrimary = false

/** Every desktop OS gets a native application menu (see `AppMenuBar.kt`, `main.kt`'s
 * `setAboutHandler`/`setPreferencesHandler` on macOS). */
actual val hasNativeAppMenu = true

/** Every desktop OS gets a system tray (see `tray/KeryxTray.kt`). */
actual val hasSystemTray = true

/** No desktop OS shows its own clipboard-copy confirmation. */
actual val platformShowsOwnCopyConfirmation = false

/**
 * Whether this desktop JVM is running on Windows. Gates the integrations the OS only offers there
 * (registering the `keryx://` URI scheme in the registry) and the tray implementation that avoids
 * AWT's non-HiDPI-aware menus (`tray/WindowsTray.kt`).
 */
internal val isWindows = osName.contains("win")

/**
 * Whether this desktop JVM is running on Linux. Gates the look-and-feel substitutions that only
 * Linux needs: Java's GTK2-era Swing Look & Feel looks dated against a modern GTK/Qt desktop,
 * whereas macOS (Aqua) and Windows both have a usable system Look & Feel.
 *
 * Note that the *context menu* backend is no longer one of these: it is chosen by [isMacOs]
 * instead, because AWT's `PopupMenu` is only genuinely native on macOS. On Windows it is drawn by
 * a JDK peer that ignores display scaling entirely — see `platform/NativeMenu.desktop.kt`.
 */
internal val isLinux = osName.contains("linux") || osName.contains("nix") || osName.contains("nux")

/** `snap/snapcraft.yaml`'s `name:`, duplicated here so [isSnap] can confirm it's *our own* snap. */
private const val KERYX_SNAP_NAME = "keryx"

/**
 * Whether this desktop JVM is running inside *this app's own* Snap confinement. Unlike the other
 * `is*` checks above (which sniff `os.name`), this reads snapd's own `SNAP_NAME` environment
 * variable — the same one libsecret's `secret-backend.c` checks to decide whether to route
 * through the Secret portal instead of Secret Service (see
 * `works.merc.keryx.app.data.cloud.LibSecretTokenStorage`) — but it isn't enough to check that
 * the variable is merely *set*: a **classic**-confinement snap (e.g. an IDE) does not isolate the
 * mount namespace, so launching a deb/rpm/portable Keryx from its integrated terminal inherits
 * that snap's `SNAP_NAME` (e.g. `code`) into a process that is not confined at all. Comparing
 * against [KERYX_SNAP_NAME] rules that out. Deliberately not the `SNAP` variable
 * `platform/InstallLocation.desktop.kt`'s `detectLinuxInstallLocation` reads (both are set for
 * every confined process, but this needs to stay in lockstep with libsecret's own detection,
 * which keys on `SNAP_NAME`, not with that unrelated install-location check).
 */
internal val isSnap = System.getenv("SNAP_NAME") == KERYX_SNAP_NAME
