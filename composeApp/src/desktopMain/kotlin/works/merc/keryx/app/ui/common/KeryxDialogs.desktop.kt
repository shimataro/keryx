package works.merc.keryx.app.ui.common

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeightIn
import androidx.compose.foundation.layout.requiredWidthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogModalityType
import androidx.compose.ui.window.DialogState
import androidx.compose.ui.window.DialogWindow
import androidx.compose.ui.window.DialogWindowScope
import androidx.compose.ui.window.WindowDecoration
import org.koin.compose.koinInject
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.platform.LocalNativeWindow
import works.merc.keryx.app.ui.theme.KeryxTheme
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Window
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.RootPaneContainer

/**
 * Owner window of the immediately enclosing [DesktopModalWindow], if any. Lets a dialog opened
 * from within another dialog (e.g. "add folder" opened from "move to folder") center itself
 * against the dialog that opened it rather than jumping back to the app's main window. Falls back
 * to [LocalNativeWindow] when not nested inside another dialog.
 */
private val LocalDialogWindowOwner = compositionLocalOf<Window?> { null }

/**
 * Height cap (in Dp) that [content][DesktopModalWindow] should honor for any internally
 * scrollable region. Clamping the OS window's size after the fact (see [MAX_HEIGHT_FRACTION])
 * only prevents the *window* from growing past the current screen's bounds — it does nothing on
 * its own to make oversized content (e.g. a long tag list) scrollable, since `verticalScroll`
 * only activates when the scrollable node itself is laid out with a bounded max height. Consumers
 * that want a scrollable region (like [KeryxAlertDialog]'s `text` slot) must apply
 * `Modifier.heightIn(max = ...)` (or `Modifier.weight(1f, fill = false)` inside a height-bounded
 * `Column`) using this value.
 *
 * Contract note: content that pairs a scroll region capped at this value with non-scrolling sibling
 * rows (e.g. a button row) can exceed the cap and clip those rows. Dialogs with a potentially long
 * inner list should bound that list to a *fixed* height well under this value (see the add-feed
 * candidate list) rather than letting it consume the full cap.
 */
private val LocalDialogMaxContentHeight = compositionLocalOf<Dp?> { null }

/**
 * Decoration-height allowance for the currently open [DesktopModalWindow], provided so
 * [KeryxAlertDialog] can size its macOS-merged title row (see [DECORATION_HEIGHT_ALLOWANCE]) to
 * match exactly what was added on top of the measured content height.
 */
private val LocalDialogTitleBarAllowance = compositionLocalOf { DECORATION_HEIGHT_ALLOWANCE }

private const val MAX_HEIGHT_FRACTION = 0.85f

/**
 * Fixed width for [KeryxAlertDialog]'s card. Deliberately NOT derived via `IntrinsicSize.Max`:
 * some callers' `title`/`text` slots (e.g. `AssignTagsDialog`, `MoveToFolderDialog`) use
 * `Modifier.weight(1f)` to push an icon to the far edge, and `weight()` has no well-defined
 * intrinsic width — mixing it with `IntrinsicSize.Max` produced an unstable measured width that,
 * combined with [DesktopModalWindow]'s auto-fit, could leave the actual OS window wider than what
 * was actually painted (an unpainted default-background strip). A fixed width sidesteps the
 * instability entirely, at the cost of short dialogs not fitting quite as tightly.
 */
private val KERYX_ALERT_DIALOG_WIDTH = 400.dp

/**
 * Estimated height of the OS-drawn title bar on Windows/Linux, added on top of the measured
 * content height when computing [DialogState.size] for a decorated
 * ([DesktopModalWindow.undecorated] = false) dialog — `DialogState.size` describes the whole
 * window including decoration, not just the content area, and on those platforms the title bar is
 * real, separate chrome that Compose never measures. A deliberate fixed guess rather than reading
 * the real value from `window.insets`: insets can be unreliable until the window manager has
 * actually applied the decoration, a known AWT/X11 quirk on Linux, and [DesktopModalWindow]'s
 * drift guard re-fires on a change of the *content*'s measured size or of the *window*'s size —
 * never on an inset value arriving late on its own, so that would not trigger a corrective
 * re-layout either. A deliberate overestimate is safe here —
 * [DesktopModalWindow]'s full-bleed themed background simply shows a little extra of the same
 * color if the real decoration is shorter than this.
 */
private val DECORATION_HEIGHT_ALLOWANCE = 40.dp

/**
 * Height of [KeryxAlertDialog]'s macOS merged title row (see [DesktopModalWindow]). A fixed
 * standard value rather than a runtime measurement of `window.insets.top`: that measurement was
 * found in practice to come back larger than the real traffic-light band's height for a
 * `DialogWindow` (unlike the proven-reliable `main.kt` case, which measures on the main `Window`),
 * which both mis-centered the title well below the traffic lights and inflated the whole dialog's
 * auto-fit height (since this row is part of what gets measured). Since this value only sizes a
 * purely cosmetic row — it no longer feeds into [DialogState.size] on macOS at all (see the
 * `decorationAllowance` comment below) — a fixed guess is exactly as good as a real measurement
 * here, without the measurement's reliability risk.
 */
private val MAC_TITLE_BAR_HEIGHT = 28.dp

/** Left padding reserved in the macOS merged-title-bar row so the title/[titleAction] never
 * overlaps the traffic-light window controls. */
private val MAC_TRAFFIC_LIGHT_PADDING = 72.dp

/**
 * Fixed width of [KeryxTabDialog]'s window content. Unlike its height (which auto-fits per tab, see
 * [KeryxTabDialog]), width stays fixed for the same reason [KERYX_ALERT_DIALOG_WIDTH] does:
 * `SettingsDialog.kt`'s `SwitchRow` uses `Modifier.fillMaxWidth()` + `Text(Modifier.weight(1f))` to
 * push a switch to the trailing edge, and `weight()` has no well-defined intrinsic width — letting
 * the outer `Column` auto-fit width too would measure that row against whatever (not-yet-converged)
 * width the window currently happens to be, rather than its true desired width, the same instability
 * [KERYX_ALERT_DIALOG_WIDTH]'s doc already describes. Sized to comfortably fit the widest row, the
 * 5-tab bar (icon + Japanese label per tab) and the OPML import/export button pair.
 */
private val KERYX_TAB_DIALOG_WIDTH = 640.dp

/** Diagnostic log tag for this file (see [works.merc.keryx.app.core.Log]). */
private const val LOG_TAG = "KeryxDialogs"

/** The only [works.merc.keryx.app.data.local.LocalSettings] fields the dialog's [KeryxTheme] needs;
 * subscribed on its own so unrelated settings changes don't recompose the dialog content. */
private data class DialogThemePrefs(val themeMode: String, val fontScale: Double)

/**
 * Renders [content] in a real, separate, natively-decorated, non-resizable OS window (title bar
 * with close button, draggable) instead of a Compose `Popup` — see [KeryxAlertDialog] for why.
 *
 * The window is created at a placeholder size (see [placeholderSize]) and then kept fitted to
 * [content] for its whole lifetime by the drift guard below, clamping the height to
 * [MAX_HEIGHT_FRACTION] of the current screen's height. It centers itself over the owner window
 * (see [resolvePosition]). [KeryxTheme] is re-applied because a `DialogWindow`'s content is an
 * independent composition root that does not inherit ambient theme values from the caller.
 *
 * @param title The native window title.
 * @param onDismissRequest Invoked when the dialog is closed or the Escape key is pressed.
 * @param modal Whether the dialog blocks interaction with its owner window.
 * @param initialWidth The dialog's initial and maximum content width.
 * @param repositionOnResize Whether to recompute [DialogState.position] when the content's fitted
 *   size changes. `false` keeps the dialog anchored to its initially computed position, which is
 *   useful for tabbed dialogs whose height varies per tab but whose top edge should stay stable
 *   when the user zaps between tabs.
 * @param content The dialog content.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun DesktopModalWindow(
    title: String?,
    onDismissRequest: () -> Unit,
    modal: Boolean = true,
    initialWidth: Dp = KERYX_ALERT_DIALOG_WIDTH,
    repositionOnResize: Boolean = true,
    content: @Composable () -> Unit,
) {
    val owner = LocalDialogWindowOwner.current ?: LocalNativeWindow.current

    val cursorPoint: Point? = null
    val screenBounds = remember(cursorPoint, owner) { currentScreenBounds(cursorPoint, owner) }
    val placeholderSize = remember(initialWidth) { placeholderSize(initialWidth) }

    val dialogState = remember {
        DialogState(
            position = resolvePosition(cursorPoint, owner, screenBounds, placeholderSize),
            size = placeholderSize,
        )
    }

    val onPreviewKey: (KeyEvent) -> Boolean = { keyEvent ->
        if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.Escape) {
            onDismissRequest()
            true
        } else {
            false
        }
    }

    // Shared window content so the modal and modeless DialogWindow overloads render identically.
    val dialogContent: @Composable DialogWindowScope.() -> Unit = {
        // Minimizing/zooming a modal one-off dialog isn't meaningful; only "close" and
        // "drag to move" (both native window behaviors) are wanted. Suppressed via the
        // macOS-specific client properties since there's no cross-platform Compose API for this
        // (Windows dialogs conventionally don't show minimize/maximize buttons to begin with).
        // On macOS this block also merges the title bar into the content background, per-dialog
        // (a local equivalent of main.kt's WindowChrome.titleBarInsetDp, since each dialog is its
        // own independent OS window rather than sharing the main window's chrome state).
        val titleBarAllowanceDp = remember(window) {
            val rootPane = (window as? RootPaneContainer)?.rootPane
            rootPane?.putClientProperty("apple.awt.windowMinimizable", false)
            rootPane?.putClientProperty("apple.awt.windowZoomable", false)
            if (isMacOs) {
                rootPane?.putClientProperty("apple.awt.fullWindowContent", true)
                rootPane?.putClientProperty("apple.awt.transparentTitleBar", true)
                rootPane?.putClientProperty("apple.awt.windowTitleVisible", false)
                MAC_TITLE_BAR_HEIGHT
            } else {
                DECORATION_HEIGHT_ALLOWANCE
            }
        }

        CompositionLocalProvider(
            LocalDialogWindowOwner provides window,
            LocalDialogTitleBarAllowance provides titleBarAllowanceDp,
        ) {
            // Subscribe to only the two theme-relevant fields (not the whole LocalSettings), so an
            // unrelated local-settings change (pane-width drag, scroll-position save, cache-cleanup
            // timestamp, …) doesn't recompose the entire dialog content under KeryxTheme.
            val settingsRepository = koinInject<SettingsRepository>()
            val themePrefs by remember(settingsRepository) {
                settingsRepository.localSettings
                    .map { DialogThemePrefs(it.themeMode, it.fontSizeScale) }
                    .distinctUntilChanged()
            }.collectAsState(
                initial = settingsRepository.getLocalSettings().let { DialogThemePrefs(it.themeMode, it.fontSizeScale) },
            )

            KeryxTheme(themeMode = themePrefs.themeMode, fontScale = themePrefs.fontScale.toFloat()) {
                // Density must come from the DIALOG's own composition, not the caller's: the pixels
                // measured below are produced by the dialog's layout pass, so converting them with
                // the owner window's density is off by the ratio between the two whenever owner and
                // dialog sit on screens with different scale factors (at owner density 2 / dialog
                // density 1, a 640dp-wide content became a 320pt window: tab bar clipped, content
                // over-wrapped, height pinned to maxHeightDp). KeryxTheme overrides only fontScale,
                // which toPx/toDp ignore, so reading inside it is equivalent for this conversion.
                val density = LocalDensity.current

                // screenBounds is in AWT "points" (macOS logical resolution, same space as the
                // cursor coordinates) — the same space Compose's Dp uses at density 1.0. Deriving
                // maxHeightDp directly from it (not via a stray extra density division) keeps it a
                // true 85%-of-screen value.
                val maxHeightDp = (screenBounds.height * MAX_HEIGHT_FRACTION).dp

                // On macOS the merged title row (drawn by KeryxAlertDialog when isMacOs) is already
                // part of the measured Surface and fullWindowContent zeroes the insets, so no
                // decoration allowance is added there; only non-mac needs the real title bar height
                // added on top of the measured content.
                val decorationAllowance = if (isMacOs) 0.dp else titleBarAllowanceDp

                // Held through rememberUpdatedState: the effect below lives as long as the dialog
                // and must never keep a stale conversion — density and maxHeightDp both change if
                // the dialog is dragged onto a screen with a different scale factor. Keying the
                // effect on them instead would reset the guard's state and re-place a tabbed dialog
                // on, say, a font-scale change.
                val fitSize: (IntSize) -> DpSize? = { contentPx ->
                    fitWindowSize(contentPx, density, maxHeightDp, decorationAllowance)
                }
                val currentFitSize by rememberUpdatedState(fitSize)

                // A freshly created DialogWindow's first layout pass does not reliably fire
                // onSizeChanged (not just the process's very first dialog — reopening the same
                // dialog composable creates a brand-new DialogWindow each time, and was observed
                // to hit this too). onGloballyPositioned does reliably report the laid-out content
                // size, so both callbacks just publish the measurement here and this effect is the
                // single place that resizes the window.
                var capturedContentPx by remember { mutableStateOf<IntSize?>(null) }

                // Lifetime-long, event-driven drift guard.
                //
                // Compose's SwingDialog mirrors every native resize back into DialogState.size (its
                // ComponentAdapter.componentResized writes BOTH DialogState.size and its own
                // internal "already applied" copy), so a size that lands behind Compose's back —
                // the asynchronous application of the initial placeholder, or the ~80x28 macOS
                // gives a not-yet-sized dialog when the peer is realized by AwtWindow's launched
                // setVisible(true) — is self-consistent from Compose's point of view and Compose
                // will never correct it. Reading DialogState.size here turns each of those into an
                // event, at any point in the dialog's life.
                //
                // The previous bounded, break-on-first-match re-assert loop could not: it stopped
                // watching after one matching frame and nothing re-armed it, because the
                // requiredWidthIn/requiredHeightIn below deliberately make the measured content
                // size a function of content only, so capturedContentPx never changes again. That
                // is why a late clobber stayed for the dialog's whole lifetime — the "tabs missing
                // / tall empty dialog" report, which reproduced on 7 of 10 opens.
                //
                // DialogState.size must be part of the emitted value, not merely read: snapshotFlow
                // only emits when the emitted value differs, so a size-only change would re-run the
                // block and be swallowed as a duplicate.
                //
                // Applying is two steps. DialogState.size is written first because that is the path
                // that packs a not-yet-displayable window (Compose's own sizing does
                // setPreferredSize + pack() before setSize while the peer does not exist). The size
                // is then pushed straight onto the native window, because writing an *unchanged*
                // DpSize is a no-op twice over: mutableStateOf uses structural equality, and
                // SwingDialog additionally skips the native call when state.size equals its applied
                // copy. componentResized then feeds the real size back into DialogState, which is
                // what re-arms this guard. The body is deliberately non-suspending — no
                // withFrameNanos — so it neither depends on the dialog's frame clock (which stops
                // delivering frames while the window is not being rendered) nor can be re-entered;
                // it runs on Compose Desktop's main (AWT event) dispatcher, so touching the native
                // window directly from here is safe.
                LaunchedEffect(Unit) {
                    var fit = DialogFitState()
                    snapshotFlow { capturedContentPx to dialogState.size }.collect { (contentPx, _) ->
                        val target = contentPx?.let(currentFitSize) ?: return@collect
                        val actual = windowSize(window)
                        val decision = nextDialogFit(fit, target, actual, repositionOnResize)
                        fit = decision.state
                        if (decision.applySize) {
                            dialogState.size = target
                            applyWindowSize(window, target)
                        }
                        if (decision.applyPosition) {
                            dialogState.position = resolvePosition(cursorPoint, owner, screenBounds, target)
                        }
                        if (decision.reportGiveUp) {
                            Log.warn(
                                LOG_TAG,
                                "Dialog stayed at $actual after $MAX_FIT_CORRECTIONS attempts to fit $target",
                            )
                        }
                    }
                }

                CompositionLocalProvider(LocalDialogMaxContentHeight provides maxHeightDp) {
                    // The outer Box always paints the OS window's full current bounds. The native
                    // window resize triggered below is asynchronous, so for at least one frame
                    // (or longer, if the size feedback loop hasn't settled) the window can be
                    // larger than what content actually measures — without this, that surplus area
                    // would show Skia's default (light) clear color instead of the theme.
                    Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceContainerLow)) {
                        // TopCenter (not Center): any excess between the window's actual size and
                        // the measured content must only ever show up as extra space at the
                        // *bottom* (covered by the background above). Center would split that
                        // excess evenly top/bottom, pushing the whole card — and on macOS, the
                        // merged title row specifically — down and out of alignment with the
                        // traffic lights, which sit fixed at the very top of the window.
                        Box(
                            Modifier
                                .align(Alignment.TopCenter)
                                .onSizeChanged { capturedContentPx = it }
                                // onGloballyPositioned fires even on the process's first dialog (where
                                // onSizeChanged doesn't), so capture the laid-out content size for the
                                // first-open forced fit above.
                                .onGloballyPositioned { capturedContentPx = it.size }
                                // Override the incoming (window-bounded) max height so content is
                                // measured at its natural height up to maxHeightPx, regardless of how
                                // small the window currently is. Without this, the outer fillMaxSize()
                                // box caps content at the *current* window height, so the auto-fit
                                // above could only ever SHRINK the window to content, never GROW it to
                                // reveal content added after the first layout (e.g. the add-feed
                                // candidate list appearing post-preview). onSizeChanged sits outside
                                // this so it observes the grown height. No oscillation: the measured
                                // height becomes a function of content only (independent of the window
                                // size), so it reports the same value next frame and settles.
                                .requiredHeightIn(max = maxHeightDp)
                                // The same override for width, and for a sharper reason: width has no
                                // "grow later" case (it is fixed per dialog), but it does have a
                                // *sticky failure* one. Content asks for its width with
                                // `Modifier.width(initialWidth)`, which — unlike requiredWidth —
                                // clamps to the incoming max (see placeholderSize's KDoc). Bounded by
                                // the window, that max is whatever size the native window happens to
                                // report at measure time; a DialogWindow that has not yet reached its
                                // requested size measures narrower. The fit above then writes that
                                // narrow width back to the window, and because onSizeChanged only
                                // re-fires when the measured size *changes*, the next pass re-measures
                                // at the same narrow width and reports nothing — the dialog is stuck
                                // narrow for its whole lifetime. Symptoms of that stuck state: the tab
                                // bar (a plain non-wrapping Row, ~530dp for the Japanese labels) clips
                                // its trailing tabs away, and the over-wrapped content grows tall
                                // enough to hit maxHeightDp, leaving a tall window mostly filled by
                                // the background painted below. Pinning the max here makes the
                                // measured width a function of content only, so a transient narrow
                                // window can no longer become permanent.
                                .requiredWidthIn(max = initialWidth),
                        ) {
                            content()
                        }
                    }
                }
            }
        }
    }

    if (modal) {
        // Non-experimental overload: DocumentModal — blocks the owner window until dismissed. Used
        // by KeryxAlertDialog (confirmations, prompts) that must be answered first.
        DialogWindow(
            onCloseRequest = onDismissRequest,
            state = dialogState,
            title = title ?: "",
            undecorated = false,
            transparent = false,
            resizable = false,
            onPreviewKeyEvent = onPreviewKey,
            content = dialogContent,
        )
    } else {
        // Experimental overload exposing modalityType = Modeless: leaves the owner (main) window
        // interactive while this dialog is open (Settings / KeryxTabDialog). SystemDefault
        // decoration == undecorated=false (native title bar + close box), matching the modal branch.
        DialogWindow(
            onCloseRequest = onDismissRequest,
            state = dialogState,
            title = title ?: "",
            decoration = WindowDecoration.SystemDefault,
            transparent = false,
            resizable = false,
            modalityType = DialogModalityType.Modeless,
            onPreviewKeyEvent = onPreviewKey,
            content = dialogContent,
        )
    }
}

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
private fun NativeButtonRow(
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

    SwingPanel(
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
            panel.revalidate()
            panel.repaint()
        },
    )
}

/**
 * Displays an alert dialog with optional title, content, and dismiss action.
 *
 * @param onDismissRequest Called when the dialog should be dismissed.
 * @param confirmText Label for the confirmation button.
 * @param onConfirm Called when the confirmation button is selected.
 * @param confirmEnabled Whether the confirmation button is enabled.
 * @param dismissText Label for the optional dismiss button, or `null` to hide it.
 * @param title Optional dialog title.
 * @param titleAction Optional action displayed alongside the title.
 * @param text Optional composable dialog content.
 * @param containerColor Background color of the dialog.
 * @param tonalElevation Elevation applied to the dialog surface.
 * @param modal Whether the dialog blocks interaction with its owner window.
 */
@Composable
actual fun KeryxAlertDialog(
    onDismissRequest: () -> Unit,
    confirmText: String,
    onConfirm: () -> Unit,
    confirmEnabled: Boolean,
    dismissText: String?,
    title: String?,
    titleAction: (@Composable () -> Unit)?,
    text: (@Composable () -> Unit)?,
    containerColor: Color,
    tonalElevation: Dp,
    modal: Boolean,
) {
    DesktopModalWindow(title = title, onDismissRequest = onDismissRequest, modal = modal) {
        val maxHeightDp = LocalDialogMaxContentHeight.current
        val titleBarAllowanceDp = LocalDialogTitleBarAllowance.current
        val dialogWindow = LocalDialogWindowOwner.current
        val resolvedContainerColor = containerColor.takeOrElse { MaterialTheme.colorScheme.surface }

        Surface(color = resolvedContainerColor, tonalElevation = tonalElevation) {
            // Fixed width (see KERYX_ALERT_DIALOG_WIDTH) so the button row can be right-aligned
            // within a stable width via Modifier.fillMaxWidth() below.
            Column(Modifier.width(KERYX_ALERT_DIALOG_WIDTH)) {
                // macOS only: since the native title bar's text is hidden (windowTitleVisible =
                // false, see DesktopModalWindow) to make room for the merged background, the same
                // title string is redrawn here at the top of the Surface instead, at the real
                // measured title-bar height, with room reserved on the left for the traffic
                // lights. On Windows/Linux the native title bar already shows `title` as-is, so
                // nothing is drawn here for it.
                if (isMacOs && (title != null || titleAction != null)) {
                    Row(
                        Modifier.fillMaxWidth().height(titleBarAllowanceDp).padding(start = MAC_TRAFFIC_LIGHT_PADDING, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        if (title != null) {
                            ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                                Text(title, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        } else {
                            Spacer(Modifier.weight(1f))
                        }
                        titleAction?.invoke()
                    }
                }

                // Non-mac: titleAction can't be embedded in the native title bar, so it's drawn
                // as a standalone right-aligned icon at the top of the card body instead (no
                // redundant title text here either way — see comment above).
                val showBodyTitleAction = !isMacOs && titleAction != null

                Column(
                    Modifier
                        .let { if (maxHeightDp != null) it.heightIn(max = maxHeightDp) else it }
                        .padding(24.dp),
                ) {
                    if (showBodyTitleAction) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) { titleAction.invoke() }
                        Spacer(Modifier.height(8.dp))
                    }
                    text?.let {
                        // heightIn(max = maxHeightDp) directly on this scrollable Column (rather
                        // than Modifier.weight on the outer Column) caps it without forcing the
                        // outer Column to expand to that cap: a Column with a weighted child
                        // reserves the full incoming max height to compute the weight
                        // distribution — regardless of that child's own fill setting — which
                        // stretched every dialog to maxHeightDp even for a single short line of
                        // content, pushing the button row far below it. Applying the cap directly
                        // here lets the outer Column size to its actual (short) content instead,
                        // while long content still gets clamped and scrollable exactly as before.
                        Column(
                            Modifier
                                .let { if (maxHeightDp != null) it.heightIn(max = maxHeightDp) else it }
                                .verticalScroll(rememberScrollState()),
                        ) {
                            ProvideTextStyle(MaterialTheme.typography.bodyMedium) { it() }
                        }
                        Spacer(Modifier.height(16.dp))
                    }
                    Spacer(Modifier.height(if (!showBodyTitleAction && text == null) 0.dp else 8.dp))
                    NativeButtonRow(
                        dialogWindow = dialogWindow,
                        confirmText = confirmText,
                        onConfirm = onConfirm,
                        confirmEnabled = confirmEnabled,
                        dismissText = dismissText,
                        onDismissRequest = onDismissRequest,
                        backgroundColor = resolvedContainerColor,
                    )
                }
            }
        }
    }
}

/**
 * Displays a modeless dialog with tab navigation and content for the selected tab.
 *
 * @param tabs The tabs available for selection.
 * @param selectedTabId The identifier of the selected tab.
 * @param onSelectTab Called with the identifier of the tab selected by the user.
 * @param content Composable content rendered for the selected tab.
 */
@Composable
actual fun KeryxTabDialog(
    onDismissRequest: () -> Unit,
    tabs: List<KeryxDialogTab>,
    selectedTabId: String,
    onSelectTab: (String) -> Unit,
    content: @Composable (String) -> Unit,
) {
    // The window title (and, on macOS, the merged-title-bar row) mirrors the selected tab's label.
    val selectedLabel = tabs.firstOrNull { it.id == selectedTabId }?.label
    DesktopModalWindow(
        title = selectedLabel,
        onDismissRequest = onDismissRequest,
        modal = false,
        initialWidth = KERYX_TAB_DIALOG_WIDTH,
        repositionOnResize = false,
    ) {
        Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
            Column(Modifier.width(KERYX_TAB_DIALOG_WIDTH)) {
                // macOS only: the native title text is hidden (see DesktopModalWindow), so the
                // selected tab's label is redrawn here next to the traffic lights. On Windows/Linux
                // the native title bar already shows it (the DialogWindow title follows recomposition).
                if (isMacOs && selectedLabel != null) {
                    Row(
                        Modifier.fillMaxWidth().height(MAC_TITLE_BAR_HEIGHT)
                            .padding(start = MAC_TRAFFIC_LIGHT_PADDING, end = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        ProvideTextStyle(MaterialTheme.typography.titleSmall) {
                            Text(selectedLabel, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }

                // Tab bar: a flat Compose-drawn row (see KeryxDialogTabBar) rather than a native
                // macOS Aqua toolbar/segmented control — see that composable's KDoc for why native
                // Swing interop was tried and dropped for this control specifically.
                Box(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    KeryxDialogTabBar(
                        tabs = tabs,
                        selectedTabId = selectedTabId,
                        onSelectTab = onSelectTab,
                    )
                }

                // Tab-content area sizes to its own natural height — DesktopModalWindow's existing
                // content-driven auto-fit (the same mechanism KeryxAlertDialog's `text` slot uses)
                // then resizes the window to match, so the window's height genuinely follows each
                // tab's content (a deliberate reversal of an earlier fixed-height decision — see
                // KERYX_TAB_DIALOG_WIDTH's KDoc for why width alone stays fixed). heightIn(max=...)
                // + verticalScroll is only a safety net for content taller than the screen allows
                // (e.g. a large font-scale setting), not the normal per-tab sizing mechanism.
                val maxHeightDp = LocalDialogMaxContentHeight.current
                Column(
                    Modifier
                        .width(KERYX_TAB_DIALOG_WIDTH)
                        .let { if (maxHeightDp != null) it.heightIn(max = maxHeightDp) else it }
                        .verticalScroll(rememberScrollState())
                        // Bottom breathing room independent of each tab's own content padding, so
                        // the last row never sits flush against the window's bottom edge — a small
                        // buffer against the auto-fit height calculation's own rounding (see
                        // MAC_TITLE_BAR_HEIGHT/DECORATION_HEIGHT_ALLOWANCE, both deliberate estimates
                        // rather than exact measurements).
                        .padding(bottom = 16.dp),
                ) {
                    content(selectedTabId)
                }
            }
        }
    }
}
