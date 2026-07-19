package works.merc.keryx.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle

/**
 * Candidate OS-native UI font family names, tried in order via Skia's [FontMgr]. The first name
 * that [FontMgr.matchFamilyStyle] can resolve to a non-null [org.jetbrains.skia.Typeface] wins.
 * We don't bundle font files — these OS UI fonts (SF Pro, Segoe UI) are not redistributable —
 * we're only referencing whatever's already installed.
 */
private fun candidateFontNames(): List<String> {
    val osName = System.getProperty("os.name").lowercase()
    return when {
        osName.contains("mac") -> listOf("SF Pro Text", "SF Pro Display", "Helvetica Neue")
        osName.contains("win") -> listOf("Segoe UI Variable", "Segoe UI")
        // No single "native" font across Linux distributions — leave FontFamily.Default alone.
        else -> emptyList()
    }
}

actual fun appFontFamily(): FontFamily? {
    val fontMgr = FontMgr.default
    for (name in candidateFontNames()) {
        val skiaTypeface = fontMgr.matchFamilyStyle(name, FontStyle.NORMAL) ?: continue
        return FontFamily(Typeface(skiaTypeface, name))
    }
    return null
}
