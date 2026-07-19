package works.merc.keryx.app.ui.theme

import androidx.compose.ui.text.font.FontFamily

/**
 * OS-native UI font, resolved by name via the platform's font manager, if one is available and
 * bundled fonts are avoided intentionally (OS UI fonts like SF Pro are not redistributable).
 * `null` means "no native font could be resolved — keep [FontFamily.Default]".
 */
expect fun appFontFamily(): FontFamily?
