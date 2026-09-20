package works.merc.keryx.app.ui.article

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import kotlin.math.roundToInt

/**
 * A minimal, best-effort parser for the handful of `style=""` declarations the fallback reader
 * honors (color, background, size, weight, decoration, alignment, monospace font family) — see
 * [InlineStyle]'s KDoc for why an inline style is trusted at face value.
 *
 * This is deliberately not a general CSS parser: it recognizes only the value shapes real feed
 * markup is likely to use (`#rgb`/`#rrggbb`/`#rrggbbaa`, `rgb()`/`rgba()`, a small set of named
 * colors, `em`/`rem`/`%`/`px`/keyword lengths). Anything it doesn't recognize is left alone rather
 * than guessed at — a malformed or unsupported value must never crash the parse or produce a
 * misleading result, so every function here returns null instead of throwing.
 */
internal object InlineCss {
    /** Splits a `style=""` attribute into a property→value map, ignoring `!important` and malformed entries. */
    fun parseDeclarations(style: String): Map<String, String> {
        if (style.isBlank()) return emptyMap()
        val result = LinkedHashMap<String, String>()
        for (declaration in style.split(';')) {
            val colonIndex = declaration.indexOf(':')
            if (colonIndex <= 0) continue
            val property = declaration.substring(0, colonIndex).trim().lowercase()
            if (property.isEmpty()) continue
            val rawValue = declaration.substring(colonIndex + 1).trim()
            val value = rawValue.removeSuffix("!important").trim().removeSuffix("!IMPORTANT").trim()
            if (value.isEmpty()) continue
            result[property] = value
        }
        return result
    }

    fun parseColor(value: String): Color? {
        val v = value.trim().lowercase()
        if (v.isEmpty() || v == "inherit" || v == "initial" || v == "unset" || v == "currentcolor") return null
        if (v == "transparent") return Color.Transparent
        NAMED_COLORS[v]?.let { return it }
        if (v.startsWith("#")) return parseHexColor(v)
        if (v.startsWith("rgb(") || v.startsWith("rgba(")) return parseRgbFunction(v)
        return null
    }

    private fun parseHexColor(hex: String): Color? {
        val digits = hex.removePrefix("#")
        return when (digits.length) {
            3 -> runCatching {
                val r = digits[0].toString().repeat(2).toInt(16)
                val g = digits[1].toString().repeat(2).toInt(16)
                val b = digits[2].toString().repeat(2).toInt(16)
                Color(r, g, b)
            }.getOrNull()

            4 -> runCatching {
                val r = digits[0].toString().repeat(2).toInt(16)
                val g = digits[1].toString().repeat(2).toInt(16)
                val b = digits[2].toString().repeat(2).toInt(16)
                val a = digits[3].toString().repeat(2).toInt(16)
                Color(r, g, b, a)
            }.getOrNull()

            6 -> runCatching {
                val r = digits.substring(0, 2).toInt(16)
                val g = digits.substring(2, 4).toInt(16)
                val b = digits.substring(4, 6).toInt(16)
                Color(r, g, b)
            }.getOrNull()

            8 -> runCatching {
                val r = digits.substring(0, 2).toInt(16)
                val g = digits.substring(2, 4).toInt(16)
                val b = digits.substring(4, 6).toInt(16)
                val a = digits.substring(6, 8).toInt(16)
                Color(r, g, b, a)
            }.getOrNull()

            else -> null
        }
    }

    private fun parseRgbFunction(value: String): Color? {
        val inner = value.substringAfter('(', "").substringBeforeLast(')', "")
        if (inner.isEmpty()) return null
        val parts = inner.split(',', '/').map { it.trim() }.filter { it.isNotEmpty() }
        if (parts.size < 3) return null
        val r = parts[0].toIntOrNull() ?: return null
        val g = parts[1].toIntOrNull() ?: return null
        val b = parts[2].toIntOrNull() ?: return null
        val a = parts.getOrNull(3)?.let { parseAlphaComponent(it) } ?: 1f
        return runCatching {
            Color(r.coerceIn(0, 255), g.coerceIn(0, 255), b.coerceIn(0, 255), (a * 255f).roundToInt().coerceIn(0, 255))
        }.getOrNull()
    }

    private fun parseAlphaComponent(raw: String): Float? =
        if (raw.endsWith("%")) raw.removeSuffix("%").toFloatOrNull()?.let { it / 100f } else raw.toFloatOrNull()

    /**
     * A CSS length/keyword as a scale factor relative to the surrounding font size (i.e. what
     * `em`/`%`/a keyword already are, and what `px` is converted to against a 16px root — the
     * same base the reader document's own `html { font-size: 100% }` resolves against).
     */
    fun parseLengthScale(value: String): Float? {
        val v = value.trim().lowercase()
        KEYWORD_SCALES[v]?.let { return it }
        return when {
            v.endsWith("em") -> v.removeSuffix("em").toFloatOrNull()
            v.endsWith("%") -> v.removeSuffix("%").toFloatOrNull()?.let { it / 100f }
            v.endsWith("px") -> v.removeSuffix("px").toFloatOrNull()?.let { it / BASE_FONT_SIZE_PX }
            v.endsWith("pt") -> v.removeSuffix("pt").toFloatOrNull()?.let { it * 96f / 72f / BASE_FONT_SIZE_PX }
            else -> null
        }?.takeIf { it > 0f }
    }

    fun parseFontWeight(value: String): FontWeight? {
        val v = value.trim().lowercase()
        return when (v) {
            "bold", "bolder" -> FontWeight.Bold
            "normal" -> FontWeight.Normal
            else -> v.toIntOrNull()?.coerceIn(1, 1000)?.let { FontWeight(it) }
        }
    }

    fun parseItalic(value: String): Boolean? = when (value.trim().lowercase()) {
        "italic", "oblique" -> true
        "normal" -> false
        else -> null
    }

    fun parseTextDecoration(value: String): TextDecoration? {
        val v = value.trim().lowercase()
        return when {
            "underline" in v && "line-through" in v -> TextDecoration.combine(listOf(TextDecoration.Underline, TextDecoration.LineThrough))
            "underline" in v -> TextDecoration.Underline
            "line-through" in v -> TextDecoration.LineThrough
            v == "none" -> TextDecoration.None
            else -> null
        }
    }

    fun parseTextAlign(value: String): TextAlign? = when (value.trim().lowercase()) {
        "center" -> TextAlign.Center
        "right", "end" -> TextAlign.End
        "left", "start" -> TextAlign.Start
        "justify" -> TextAlign.Justify
        else -> null
    }

    /** Whether a `font-family` list names a monospace font (the only family this reader honors). */
    fun isMonospaceFontFamily(value: String): Boolean =
        value.split(',').any { it.trim().trim('"', '\'').lowercase().let { name -> name == "monospace" || "mono" in name } }

    private const val BASE_FONT_SIZE_PX = 16f

    private val KEYWORD_SCALES = mapOf(
        "xx-small" to 0.6f,
        "x-small" to 0.75f,
        "small" to 0.89f,
        "medium" to 1.0f,
        "large" to 1.2f,
        "x-large" to 1.5f,
        "xx-large" to 2.0f,
        "smaller" to 0.83f,
        "larger" to 1.2f,
    )

    private val NAMED_COLORS = mapOf(
        "black" to Color(0, 0, 0),
        "white" to Color(255, 255, 255),
        "red" to Color(255, 0, 0),
        "green" to Color(0, 128, 0),
        "blue" to Color(0, 0, 255),
        "yellow" to Color(255, 255, 0),
        "orange" to Color(255, 165, 0),
        "purple" to Color(128, 0, 128),
        "gray" to Color(128, 128, 128),
        "grey" to Color(128, 128, 128),
        "silver" to Color(192, 192, 192),
        "maroon" to Color(128, 0, 0),
        "navy" to Color(0, 0, 128),
        "teal" to Color(0, 128, 128),
        "olive" to Color(128, 128, 0),
        "lime" to Color(0, 255, 0),
        "pink" to Color(255, 192, 203),
        "brown" to Color(165, 42, 42),
        "cyan" to Color(0, 255, 255),
        "magenta" to Color(255, 0, 255),
    )
}

/**
 * Applies the recognized declarations of [style] onto this [InlineStyle], as a `style=""`
 * attribute would cascade over a tag mapping — see [InlineStyle]'s KDoc. Unrecognized or absent
 * properties leave the corresponding field untouched.
 */
internal fun InlineStyle.mergedWithCss(style: String): InlineStyle {
    val declarations = InlineCss.parseDeclarations(style)
    if (declarations.isEmpty()) return this
    var result = this
    declarations["color"]?.let { InlineCss.parseColor(it) }?.let { result = result.copy(color = it) }
    declarations["background-color"]?.let { InlineCss.parseColor(it) }?.let { result = result.copy(background = it) }
    declarations["font-size"]?.let { InlineCss.parseLengthScale(it) }?.let { result = result.copy(sizeScale = it) }
    declarations["font-weight"]?.let { InlineCss.parseFontWeight(it) }?.let { result = result.copy(bold = it >= FontWeight.Bold) }
    declarations["font-style"]?.let(InlineCss::parseItalic)?.let {
        result = result.copy(italic = it)
    }
    declarations["text-decoration"]?.let { raw ->
        InlineCss.parseTextDecoration(raw)?.let { decoration ->
            result = result.copy(
                underline = decoration.contains(TextDecoration.Underline),
                strikethrough = decoration.contains(TextDecoration.LineThrough),
            )
        }
    }
    declarations["font-family"]?.let { if (InlineCss.isMonospaceFontFamily(it)) result = result.copy(code = true) }
    return result
}
