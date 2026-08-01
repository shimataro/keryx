package works.merc.keryx.app.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

private val Teal = Color(0xFF00897B)
private val TealLight = Color(0xFF4DB6AC)

/**
 * Override for secondaryContainer/onSecondaryContainer — M3's automatic tone derivation from
 * [Teal] produces a container that reads as "another shade of teal", which looks web-app-y.
 * Derive the container by compositing a clearly visible tint of primary over a light/dark
 * neutral base instead (strong enough that row selection reads as obviously highlighted), and
 * keep the "on" color a plain neutral (not primary-tinted) for legibility.
 */
private val SecondaryContainerLight = Teal.copy(alpha = 0.26f).compositeOver(Color(0xFFF1F1F1))
private val OnSecondaryContainerLight = Color(0xFF3A3D3B)
private val SecondaryContainerDark = TealLight.copy(alpha = 0.32f).compositeOver(Color(0xFF303030))
private val OnSecondaryContainerDark = Color(0xFFDADAD8)

/**
 * Override for tertiaryContainer/onTertiaryContainer — M3's untouched default (pale pink in
 * light, maroon in dark) reads as an off-brand accent leaking into an otherwise teal-only app.
 * Used for the "attach" (not "move") drop-target highlight on [TagRow], which needs a hue
 * distinct from [SecondaryContainerLight]/[SecondaryContainerDark]'s teal tint (the folder
 * "move" highlight) — a blue tint reads as clearly different from teal while still following the
 * same "tint a neutral base" recipe, and the neutral "on" colors are shared with the secondary
 * container since both tints composite to the same base luminance.
 */
private val Attach = Color(0xFF1E88E5)
private val TertiaryContainerLight = Attach.copy(alpha = 0.26f).compositeOver(Color(0xFFF1F1F1))
private val TertiaryContainerDark = Attach.copy(alpha = 0.32f).compositeOver(Color(0xFF303030))

private val LightColors = lightColorScheme(
    primary = Teal,
    secondary = Teal,
    secondaryContainer = SecondaryContainerLight,
    onSecondaryContainer = OnSecondaryContainerLight,
    tertiaryContainer = TertiaryContainerLight,
    onTertiaryContainer = OnSecondaryContainerLight,
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    secondary = TealLight,
    secondaryContainer = SecondaryContainerDark,
    onSecondaryContainer = OnSecondaryContainerDark,
    tertiaryContainer = TertiaryContainerDark,
    onTertiaryContainer = OnSecondaryContainerDark,
)

/**
 * Determines whether dark theme colors should be used for the selected theme mode.
 *
 * @param themeMode The theme mode, such as `"light"` or `"dark"`; other values follow the system setting.
 * @param systemDark Whether the system is using dark mode.
 * @return `true` if dark theme colors should be used, `false` otherwise.
 */
fun resolveDarkTheme(themeMode: String, systemDark: Boolean): Boolean = when (themeMode) {
    "light" -> false
    "dark" -> true
    else -> systemDark
}

/**
 * The `surface` color for a resolved dark/light flag. Used to pre-fill the native window /
 * WebView background so a dark-mode launch doesn't flash a light frame before Compose paints
 * its first (already-dark) frame.
 */
fun keryxSurfaceColor(dark: Boolean): Color = if (dark) DarkColors.surface else LightColors.surface

/**
 * The `primary` (brand teal) color for a resolved dark/light flag. Used to tint the platform's
 * native widget styling — on Linux the remaining Swing surfaces (menu bar, context menus, dialog
 * buttons) are themed from this so they read as part of the same app rather than as generic
 * Swing chrome. See `ui/theme/DesktopLookAndFeel.kt`.
 */
fun keryxAccentColor(dark: Boolean): Color = if (dark) DarkColors.primary else LightColors.primary

/**
 * Tighter corner radii than M3's default scale — reads less "rounded pill" and more native/dense.
 */
private val KeryxShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

/**
 * Flat press feedback: an immediate, non-animated `onSurface` low-alpha overlay while pressed —
 * no Material ripple animation. Applied app-wide via [LocalIndication] so every `clickable` /
 * `selectable` / `toggleable` etc. picks it up without per-call-site overrides.
 */
private class FlatIndicationNode(
    interactionSource: InteractionSource,
    private val pressedAlpha: Float,
) : Modifier.Node(), DrawModifierNode {
    private val source = interactionSource
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            var pressCount = 0
            source.interactions.collect { interaction ->
                when (interaction) {
                    is PressInteraction.Press -> pressCount++
                    is PressInteraction.Release, is PressInteraction.Cancel -> pressCount--
                    is DragInteraction.Start -> pressCount++
                    is DragInteraction.Stop, is DragInteraction.Cancel -> pressCount--
                    is HoverInteraction.Enter, is HoverInteraction.Exit -> Unit
                    is FocusInteraction.Focus, is FocusInteraction.Unfocus -> Unit
                }
                val isPressed = pressCount > 0
                if (pressed != isPressed) {
                    pressed = isPressed
                    invalidateDraw()
                }
            }
        }
    }

    override fun ContentDrawScope.draw() {
        drawContent()
        if (pressed) {
            drawRect(color = Color.Black.copy(alpha = pressedAlpha), size = size)
        }
    }
}

private class FlatIndication(private val pressedAlpha: Float) : IndicationNodeFactory {
    override fun create(interactionSource: InteractionSource): DelegatableNode =
        FlatIndicationNode(interactionSource, pressedAlpha)

    override fun hashCode(): Int = pressedAlpha.hashCode()
    override fun equals(other: Any?): Boolean = other is FlatIndication && other.pressedAlpha == pressedAlpha
}

private val FlatIndicationLight = FlatIndication(pressedAlpha = 0.08f)
private val FlatIndicationDark = FlatIndication(pressedAlpha = 0.12f)

/**
 * Global safety net for M3 components (`Button`/`IconButton`/`Switch`/`Checkbox`/…) that
 * hardcode `androidx.compose.material3.ripple.ripple()` internally and never consult
 * [LocalIndication]. This doesn't remove the ripple animation itself (no public API for that),
 * but zeroing every [RippleAlpha] channel makes it fully invisible. Call sites that need flat
 * press feedback should still prefer plain `Modifier.clickable` (picks up [FlatIndication] via
 * [LocalIndication]) — this is a fallback for components that can't be replaced that way.
 */
private val NoRippleAlpha = RippleAlpha(
    draggedAlpha = 0f,
    focusedAlpha = 0f,
    hoveredAlpha = 0f,
    pressedAlpha = 0f,
)
private val NoRippleConfiguration = RippleConfiguration(rippleAlpha = NoRippleAlpha)

/**
 * Applies [appFontFamily] (an OS-native UI font resolved by name, if found — see
 * `ui/theme/AppFont.kt`) to every text style, leaving sizes/line-heights/tracking untouched.
 * Falls back to M3's default [Typography] verbatim when no native font could be resolved.
 */
private fun typographyWithFontFamily(family: FontFamily): Typography {
    val base = Typography()
    return base.copy(
        displayLarge = base.displayLarge.copy(fontFamily = family),
        displayMedium = base.displayMedium.copy(fontFamily = family),
        displaySmall = base.displaySmall.copy(fontFamily = family),
        headlineLarge = base.headlineLarge.copy(fontFamily = family),
        headlineMedium = base.headlineMedium.copy(fontFamily = family),
        headlineSmall = base.headlineSmall.copy(fontFamily = family),
        titleLarge = base.titleLarge.copy(fontFamily = family),
        titleMedium = base.titleMedium.copy(fontFamily = family),
        titleSmall = base.titleSmall.copy(fontFamily = family),
        bodyLarge = base.bodyLarge.copy(fontFamily = family),
        bodyMedium = base.bodyMedium.copy(fontFamily = family),
        bodySmall = base.bodySmall.copy(fontFamily = family),
        labelLarge = base.labelLarge.copy(fontFamily = family),
        labelMedium = base.labelMedium.copy(fontFamily = family),
        labelSmall = base.labelSmall.copy(fontFamily = family),
    )
}

/**
 * Applies the Keryx color scheme, shapes, typography, and interaction styling.
 *
 * @param themeMode Selects light, dark, or system-based appearance.
 * @param fontScale Scales text within the range from 0.8 to 1.6.
 * @param content The composable content displayed within the theme.
 */
@Composable
fun KeryxTheme(
    themeMode: String,
    fontScale: Float = 1f,
    content: @Composable () -> Unit,
) {
    val dark = resolveDarkTheme(themeMode, isSystemInDarkTheme())
    val density = LocalDensity.current
    val nativeFontFamily = remember { appFontFamily() }
    val typography = remember(nativeFontFamily) {
        nativeFontFamily?.let { typographyWithFontFamily(it) }
    }
    CompositionLocalProvider(
        LocalDensity provides Density(density.density, fontScale.coerceIn(0.8f, 1.6f)),
        LocalIndication provides if (dark) FlatIndicationDark else FlatIndicationLight,
        LocalRippleConfiguration provides NoRippleConfiguration,
    ) {
        if (typography != null) {
            MaterialTheme(
                colorScheme = if (dark) DarkColors else LightColors,
                shapes = KeryxShapes,
                typography = typography,
                content = content,
            )
        } else {
            MaterialTheme(
                colorScheme = if (dark) DarkColors else LightColors,
                shapes = KeryxShapes,
                content = content,
            )
        }
    }
}
