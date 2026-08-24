package works.merc.keryx.app.ui.theme

import androidx.compose.foundation.IndicationNodeFactory
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.interaction.FocusInteraction
import androidx.compose.foundation.interaction.HoverInteraction
import androidx.compose.foundation.interaction.Interaction
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.ripple.RippleAlpha
import androidx.compose.material3.LocalRippleConfiguration
import androidx.compose.material3.RippleConfiguration
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.ContentDrawScope
import androidx.compose.ui.node.DelegatableNode
import androidx.compose.ui.node.DrawModifierNode
import androidx.compose.ui.node.invalidateDraw
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * Tighter corner radii than M3's default scale — reads less "rounded pill" and more native/dense,
 * matching macOS's own tighter-cornered chrome (this app's desktop look is intentionally macOS-
 * leaning — see `external-spec.md` §9 — and shared by Windows/Linux for the reasons documented
 * there).
 */
actual val platformShapes: Shapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(6.dp),
    medium = RoundedCornerShape(8.dp),
    large = RoundedCornerShape(10.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

/**
 * Applies one [interaction] to a running list of active press/drag identities, mirroring how
 * Compose Foundation's own `collectIsPressedAsState`/`collectIsDraggedAsState` track state: by
 * identity, not by count. A `Release`/`Cancel`/`Stop` only removes the specific
 * [PressInteraction.Press] (or [DragInteraction.Start]) instance it names — never any other — so a
 * stray termination event for an interaction this node never itself observed as started (e.g. a
 * gesture that began before this node attached) cannot clear feedback for a different, still-active
 * press.
 */
internal fun nextActiveInteractions(active: List<Interaction>, interaction: Interaction): List<Interaction> =
    when (interaction) {
        is PressInteraction.Press -> active + interaction
        is PressInteraction.Release -> active - interaction.press
        is PressInteraction.Cancel -> active - interaction.press
        is DragInteraction.Start -> active + interaction
        is DragInteraction.Stop -> active - interaction.start
        is DragInteraction.Cancel -> active - interaction.start
        else -> active
    }

/**
 * Flat press feedback: an immediate, non-animated `onSurface` low-alpha overlay while pressed —
 * no Material ripple animation. Applied via [LocalIndication] so every `clickable` / `selectable` /
 * `toggleable` etc. picks it up without per-call-site overrides.
 */
private class FlatIndicationNode(
    interactionSource: InteractionSource,
    private val pressedAlpha: Float,
) : Modifier.Node(), DrawModifierNode {
    private val source = interactionSource
    private var pressed = false

    override fun onAttach() {
        coroutineScope.launch {
            var activeInteractions = emptyList<Interaction>()
            source.interactions.collect { interaction ->
                activeInteractions = nextActiveInteractions(activeInteractions, interaction)
                val isPressed = activeInteractions.isNotEmpty()
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

actual @Composable fun ProvidePlatformInteraction(dark: Boolean, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalIndication provides if (dark) FlatIndicationDark else FlatIndicationLight,
        LocalRippleConfiguration provides NoRippleConfiguration,
        content = content,
    )
}
