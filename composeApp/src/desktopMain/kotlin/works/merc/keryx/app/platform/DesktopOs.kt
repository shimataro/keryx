package works.merc.keryx.app.platform

/** The lowercased `os.name` system property, shared so every OS-name-derived check reads it once. */
internal val osName = System.getProperty("os.name")?.lowercase() ?: ""

/**
 * Whether this desktop JVM is running on macOS. Gates the platform integrations that only exist
 * there (screen menu bar, merged title bar, Dock activation policy, Aqua-specific styling), and
 * (via the commonMain `expect val` this backs) the `Return`-vs-`F2` rename shortcut convention.
 */
actual val isMacOs = osName.contains("mac")

/**
 * Whether this desktop JVM is running on Windows. Gates the integrations the OS only offers there
 * (registering the `keryx://` URI scheme in the registry).
 */
internal val isWindows = osName.contains("win")

/**
 * Whether this desktop JVM is running on Linux. Gates the look-and-feel substitutions that only
 * Linux needs: AWT's heavyweight `PopupMenu` and Java's GTK2-era Swing Look & Feel both look
 * dated against a modern GTK/Qt desktop, whereas macOS (Aqua / NSMenu) and Windows (Win32 menus)
 * already render natively.
 */
internal val isLinux = osName.contains("linux") || osName.contains("nix") || osName.contains("nux")
