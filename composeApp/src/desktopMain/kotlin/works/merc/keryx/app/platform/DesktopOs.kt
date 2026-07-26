package works.merc.keryx.app.platform

private val osName = System.getProperty("os.name").lowercase()

/**
 * Whether this desktop JVM is running on macOS. Gates the platform integrations that only exist
 * there (screen menu bar, merged title bar, Dock activation policy, Aqua-specific styling).
 */
internal val isMacOs = osName.contains("mac")

/**
 * Whether this desktop JVM is running on Linux. Gates the look-and-feel substitutions that only
 * Linux needs: AWT's heavyweight `PopupMenu` and Java's GTK2-era Swing Look & Feel both look
 * dated against a modern GTK/Qt desktop, whereas macOS (Aqua / NSMenu) and Windows (Win32 menus)
 * already render natively.
 */
internal val isLinux = osName.contains("linux") || osName.contains("nix") || osName.contains("nux")
