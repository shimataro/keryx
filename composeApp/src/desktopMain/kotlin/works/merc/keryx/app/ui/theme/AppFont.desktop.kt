package works.merc.keryx.app.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import works.merc.keryx.app.platform.isLinux
import works.merc.keryx.app.platform.isMacOs
import java.awt.Toolkit
import javax.swing.UIManager

/**
 * Pango style/variant/weight/stretch keywords that may trail the family list in a font
 * description. Only used to strip a suffix, so a family whose name merely contains one of these
 * words (e.g. "Book Antiqua") keeps it — stripping stops at the first non-keyword token.
 */
private val PANGO_STYLE_KEYWORDS = setOf(
    // style
    "normal", "oblique", "italic",
    // weight
    "thin", "ultra-light", "extra-light", "light", "semi-light", "demi-light", "book",
    "regular", "medium", "semi-bold", "demi-bold", "bold", "ultra-bold", "extra-bold",
    "heavy", "black", "ultra-heavy", "extra-black",
    // stretch
    "ultra-condensed", "extra-condensed", "condensed", "semi-condensed",
    "semi-expanded", "expanded", "extra-expanded", "ultra-expanded",
    // variant
    "small-caps", "all-small-caps", "petite-caps", "all-petite-caps", "unicase", "title-caps",
)

/**
 * Extracts the font family from a Pango font description of the form
 * `[FAMILY-LIST] [STYLE-OPTIONS] [SIZE]`, which is what GTK-based desktops report as their
 * configured UI font (e.g. `"Cantarell 11"`, `"Noto Sans Bold 10"`).
 *
 * Returns the first family of the list, or `null` if [description] carries no family at all.
 */
internal fun pangoFontFamilyName(description: String): String? {
    var tokens = description.trim().split(' ', '\t').filter { it.isNotEmpty() }
    // A trailing size, in points ("11", "11.5") or pixels ("12px").
    if (tokens.isNotEmpty() && tokens.last().removeSuffix("px").toDoubleOrNull() != null) {
        tokens = tokens.dropLast(1)
    }
    while (tokens.isNotEmpty() && tokens.last().lowercase() in PANGO_STYLE_KEYWORDS) {
        tokens = tokens.dropLast(1)
    }
    return tokens.joinToString(" ")
        .substringBefore(',')
        .trim()
        .takeIf { it.isNotEmpty() }
}

/**
 * The family the installed Look & Feel resolved as the desktop's UI font. On Linux that's FlatLaf
 * (see [installLookAndFeel]), which already reads whichever source the running desktop uses —
 * XSettings on GNOME-like desktops, `kdeglobals` on KDE. Taking its answer keeps the Compose text
 * and the Swing surfaces (menu bar, context menus, dialog buttons) on the same family instead of
 * each resolving one independently, which on KDE meant Compose never saw the configured font at
 * all: the GTK desktop property below is a GNOME thing and is absent there.
 */
private fun lookAndFeelFontName(): String? =
    runCatching { UIManager.getFont("defaultFont")?.family }.getOrNull()

/**
 * The UI font the running Linux desktop is configured to use, read straight from XSettings. Only
 * reached when [lookAndFeelFontName] came up empty (FlatLaf not installed, or an older version
 * that doesn't publish `defaultFont`), and only meaningful on GTK-based desktops (GNOME, XFCE,
 * Cinnamon, MATE — including under XWayland).
 */
private fun gtkDesktopFontName(): String? =
    runCatching { Toolkit.getDefaultToolkit().getDesktopProperty("gnome.Gtk/FontName") as? String }
        .getOrNull()
        ?.let(::pangoFontFamilyName)

/**
 * Candidate OS-native UI font family names, tried in order via Skia's [FontMgr]. The first name
 * that [FontMgr.matchFamilyStyle] can resolve to a non-null [org.jetbrains.skia.Typeface] wins.
 * We don't bundle font files — these OS UI fonts (SF Pro, Segoe UI) are not redistributable —
 * we're only referencing whatever's already installed.
 */
private fun candidateFontNames(): List<String> = when {
    isMacOs -> listOf("SF Pro Text", "SF Pro Display", "Helvetica Neue")
    // No single "native" font across Linux distributions, so ask the Look & Feel (which knows the
    // running desktop's own convention), then XSettings, and fall back to the common defaults
    // (GNOME 48+, older GNOME, Ubuntu, then generic) only if neither will say.
    isLinux -> listOfNotNull(lookAndFeelFontName(), gtkDesktopFontName()) +
        listOf("Adwaita Sans", "Cantarell", "Ubuntu", "Noto Sans", "DejaVu Sans")
    // Windows. Anything else simply won't resolve these and falls through to FontFamily.Default.
    else -> listOf("Segoe UI Variable", "Segoe UI")
}

actual fun appFontFamily(): FontFamily? {
    val fontMgr = FontMgr.default
    for (name in candidateFontNames()) {
        val skiaTypeface = fontMgr.matchFamilyStyle(name, FontStyle.NORMAL) ?: continue
        return FontFamily(Typeface(skiaTypeface, name))
    }
    return null
}
