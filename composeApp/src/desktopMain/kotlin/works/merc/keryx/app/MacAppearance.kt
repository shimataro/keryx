package works.merc.keryx.app

/**
 * The `apple.awt.application.appearance` value for the app's own theme setting [themeMode]: Aqua
 * for `"light"`, Dark Aqua for `"dark"`, and `"system"` (follow the OS) for anything else.
 */
internal fun macAppearanceFor(themeMode: String): String = when (themeMode) {
    "light" -> "NSAppearanceNameAqua"
    "dark" -> "NSAppearanceNameDarkAqua"
    else -> "system"
}
