package works.merc.keryx.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import works.merc.keryx.app.platform.isMacOs
import java.awt.FlowLayout
import java.awt.Window
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.RootPaneContainer

private const val CANCEL_BUTTON_KEY = "keryxCancelButton"
private const val CONFIRM_BUTTON_KEY = "keryxConfirmButton"

/**
 * Renders the confirm/(optional) dismiss buttons as real `javax.swing.JButton`s (system Look &
 * Feel, set once in `main.kt`) via [SwingPanel], instead of Material `Button`/`OutlinedButton` —
 * this is what actually makes the buttons look OS-native (macOS Aqua incl. accent-color
 * following), which a Compose-drawn button can only approximate. The confirm button is set as the
 * window's `rootPane.defaultButton` so Enter submits it. Both buttons are created once in
 * [SwingPanel]'s `factory` and only their `text`/`isEnabled`/visibility are synced in `update`, so
 * identity (and the `defaultButton` wiring) survives recomposition.
 */
@Composable
internal fun NativeButtonRow(
    dialogWindow: Window?,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    dismissText: String?,
    onDismissRequest: () -> Unit,
    backgroundColor: Color,
) {
    val currentOnConfirm by rememberUpdatedState(onConfirm)
    val currentOnDismiss by rememberUpdatedState(onDismissRequest)
    val awtBackground = remember(backgroundColor) { java.awt.Color(backgroundColor.toArgb()) }
    // Aqua's L&F does render cancel's neutral gray background with a foreground that's meant to
    // adapt to light/dark mode on its own (unlike confirm's accent-color gap above), but
    // explicitly matching this app's own resolved theme color is more reliable than trusting that
    // Swing's dark/light tracking (see main.kt's apple.awt.application.appearance) lines up
    // exactly with what this card itself considers readable on its surface.
    val onSurface = MaterialTheme.colorScheme.onSurface
    val awtCancelForeground = remember(onSurface) { java.awt.Color(onSurface.toArgb()) }

    // Values from the previous `update` invocation that actually affect the row's *layout*, so a
    // recomposition that changes nothing dimensional doesn't revalidate (see the update block).
    // Deliberately a plain holder rather than a mutableStateOf: SwingPanel's update runs inside
    // InteropViewHolder's SnapshotStateObserver.observeReads, so reading snapshot state here would
    // register an observation and writing it would schedule yet another interop update — i.e.
    // another SwingInteropContainer root validate()/repaint(), exactly the work being avoided.
    val lastLayoutInputs = remember { ButtonRowLayoutInputs() }

    SwingPanel(
        // Explicit, though SwingPanel's own update sets it immediately before invoking the update
        // block below (which sets it again): the parameter's default is Color.White, and nothing
        // should depend on that ordering to keep a white panel off a dark dialog.
        background = backgroundColor,
        modifier = Modifier.fillMaxWidth(),
        factory = {
            // isFocusable = false: with a heavyweight SwingPanel now present in the same window,
            // Swing's own focus traversal was found to steal focus away from a dialog's
            // autofocused Compose text field (see TextPromptDialog) right after opening. Since
            // rootPane.defaultButton (set in `update` below) already makes Enter submit
            // regardless of focus, these buttons don't need to be focusable themselves.
            val cancel = JButton().apply {
                isFocusable = false
                addActionListener { currentOnDismiss() }
            }
            val confirm = JButton().apply {
                isFocusable = false
                // Aqua's L&F paints the default/prominent button's accent-colored background
                // natively, but (unlike real native Cocoa "prominent" buttons, which always pair
                // it with white text) leaves the foreground at Button.foreground's default —
                // usually dark — leaving the text hard to read against the accent color. White
                // is safe here regardless of which of macOS's system accent colors the user has
                // chosen, since Apple's own prominent buttons use white text with all of them.
                //
                // macOS only: FlatLaf (Linux) picks a readable foreground itself — its light
                // theme leaves the default button on a neutral background with an accent border
                // and dark text, so forcing white here would make the label near-invisible.
                if (isMacOs) foreground = java.awt.Color.WHITE
                addActionListener { currentOnConfirm() }
            }
            JPanel(FlowLayout(FlowLayout.RIGHT, 8, 0)).apply {
                isOpaque = true
                add(cancel)
                add(confirm)
                putClientProperty(CANCEL_BUTTON_KEY, cancel)
                putClientProperty(CONFIRM_BUTTON_KEY, confirm)
            }
        },
        update = { panel ->
            val cancel = panel.getClientProperty(CANCEL_BUTTON_KEY) as JButton
            val confirm = panel.getClientProperty(CONFIRM_BUTTON_KEY) as JButton
            panel.background = awtBackground
            cancel.foreground = awtCancelForeground
            cancel.isVisible = dismissText != null
            if (dismissText != null) cancel.text = dismissText
            confirm.text = confirmText
            confirm.isEnabled = confirmEnabled
            (dialogWindow as? RootPaneContainer)?.rootPane?.defaultButton = confirm
            // revalidate() only when something dimensional changed. This block runs on EVERY
            // recomposition of the parent (callers pass non-remembered onConfirm/onDismissRequest
            // lambdas, so Compose can never skip it) — e.g. on every keystroke in TextPromptDialog,
            // where confirmEnabled flips. An unconditional revalidate() there reached
            // SwingInteropViewGroup.invalidate() -> layoutNode.invalidateMeasurements(), and since
            // AwtContentMeasurePolicy measures the node from component.preferredSize, that fed a
            // Compose re-measure -> window resize -> componentResized -> another drift-guard tick,
            // per keystroke. Only the button labels and whether the cancel button is shown can
            // change the row's preferred size; isEnabled and foreground are repaint-only.
            if (lastLayoutInputs.changedTo(confirmText, dismissText)) panel.revalidate()
            panel.repaint()
        },
    )
}

/**
 * Last layout-affecting inputs seen by [NativeButtonRow]'s `update` block. Intentionally *not*
 * Compose snapshot state — see the comment at its construction site.
 */
internal class ButtonRowLayoutInputs {
    private var recorded: Pair<String, String?>? = null

    /**
     * Records the current layout-affecting inputs and reports whether they differ from the previous
     * invocation's.
     *
     * @param confirmText The confirm button's label.
     * @param dismissText The dismiss button's label, or `null` when that button is hidden.
     * @return `true` when either value changed since the last call (including the first call).
     */
    fun changedTo(confirmText: String, dismissText: String?): Boolean {
        val current = confirmText to dismissText
        val changed = recorded != current
        recorded = current
        return changed
    }
}
