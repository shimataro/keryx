package works.merc.keryx.app

/**
 * Host operating system flags, resolved once from `os.name`.
 *
 * Kept in the same package as `main.kt` so the existing `isMacOs` references
 * (`IconBadge.kt`, `AppMenuBar.kt`, `KeryxDialogs.desktop.kt`, …) keep resolving
 * without an import change.
 */
private val osName = System.getProperty("os.name").lowercase()

internal val isMacOs = osName.contains("mac")

internal val isWindows = osName.contains("win")

internal val isLinux = osName.contains("linux")
