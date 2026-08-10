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
import kotlinx.coroutines.delay
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
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Point
import java.awt.Window
import javax.swing.JButton
import javax.swing.JPanel
import javax.swing.RootPaneContainer
import kotlin.math.roundToInt

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
 * Fallback height allowance for the OS-drawn title bar on Windows/Linux, used by
 * [decorationAllowanceFor] only until the window manager has actually reported real
 * `window.insets` (they read all-zero before that, a known AWT/X11 timing quirk) — see that
 * function's doc for why real insets are preferred once available. `DialogState.size` describes
 * the whole window including decoration, not just the content area, and on those platforms the
 * title bar is real, separate chrome that Compose never measures, so *some* allowance is needed
 * even before real insets arrive. A deliberate overestimate is safe here — [DesktopModalWindow]'s
 * full-bleed themed background simply shows a little extra of the same color if the real
 * decoration is shorter than this.
 */
private val DECORATION_HEIGHT_ALLOWANCE = 40.dp

/**
 * Floor applied to a dialog's [window.minimumSize][Window.setMinimumSize] and to every size
 * [applyWindowGeometry] pushes onto the window (see its `minSize` parameter). A safety net, not
 * the primary defense against a collapsed dialog — [fitWindowSize] no longer derives width from
 * measurement at all, which is what actually closes the self-amplifying shrink this guards
 * against (see [fitWindowSize]'s doc) — kept because `resizable = false` means a dialog that ever
 * did collapse could not be dragged back open by the user.
 */
private val MIN_DIALOG_HEIGHT = 100.dp

/**
 * Height of [KeryxAlertDialog]'s macOS merged title row (see [DesktopModalWindow]). A fixed
 * standard value rather than a runtime measurement of `window.insets.top`: that measurement was
 * found in practice to come back larger than the real traffic-light band's height for a
 * `DialogWindow` (unlike the proven-reliable `main.kt` case, which measures on the main `Window`),
 * which both mis-centered the title well below the traffic lights and inflated the whole dialog's
 * auto-fit height (since this row is part of what gets measured). Since this value only sizes a
 * purely cosmetic row — it no longer feeds into [DialogState.size] on macOS at all (see
 * [decorationAllowanceFor], which short-circuits to zero on macOS) — a fixed guess is exactly as
 * good as a real measurement here, without the measurement's reliability risk.
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

/**
 * Safety net for [DesktopModalWindow]'s "stay invisible until fitted" gate: however the fit goes,
 * the dialog is shown once this long has passed since it entered the composition. The gate itself
 * releases as soon as the drift guard reports [DialogFitDecision.presentable], which normally
 * happens within a frame or two of the content's first measurement — this only covers the
 * pathological case where content never reports a usable height at all (the guard's `return@collect`
 * on a null measurement), which would otherwise leave the window invisible forever.
 */
private const val DIALOG_PRESENT_FALLBACK_MS = 500L

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
 * @param containerColor The color [content] paints its own card with, or [Color.Unspecified] to use
 *   the theme's `surface`. Used for the full-bleed background *and* the native window's own
 *   background, so no area the card doesn't cover can show a different tone.
 * @param content The dialog content.
 */
@OptIn(ExperimentalComposeUiApi::class)
/**
 * Displays a non-resizable native dialog window containing Compose content.
 *
 * @param title The native window title.
 * @param onDismissRequest Called when the window is closed or Escape is pressed.
 * @param modal Whether the dialog blocks interaction with its owner window.
 * @param initialWidth The fixed content width of the dialog.
 * @param repositionOnResize Whether to reposition the dialog when its size changes.
 * @param containerColor The dialog's background color, or [Color.Unspecified] for the theme's
 *   `surface`.
 * @param content The content to display in the dialog.
 */
@Composable
private fun DesktopModalWindow(
    title: String?,
    onDismissRequest: () -> Unit,
    modal: Boolean = true,
    initialWidth: Dp = KERYX_ALERT_DIALOG_WIDTH,
    repositionOnResize: Boolean = true,
    containerColor: Color = Color.Unspecified,
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

    // The window is created (and its content composed and measured) while still invisible, and only
    // shown once the drift guard below reports there is nothing left to correct. Compose Desktop
    // realizes the peer via pack() from SwingDialog's update — "Pack to allow drawing the first
    // frame" — which is what runs the composition, and makes the window visible separately, from a
    // coroutine launched by AwtWindow's DisposableEffect(visible). Those two land in an order that
    // is pure scheduling, so without this gate the placeholder-sized (see placeholderSize), and
    // therefore placeholder-*centered*, first frame could be on screen before the fit landed: the
    // card visibly warped upward by (fittedHeight - 240dp) / 2 as the correction arrived. That is
    // the "components appeared somewhere else for an instant" report, and it is theme-independent —
    // it happened in light mode too.
    var readyToShow by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        delay(DIALOG_PRESENT_FALLBACK_MS)
        readyToShow = true
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
            // Floor, not the primary defense — see MIN_DIALOG_HEIGHT's doc. Same Dp/AWT-point
            // space applyWindowGeometry's own rounding uses, so the two floors agree.
            window.minimumSize = Dimension(initialWidth.value.roundToInt(), MIN_DIALOG_HEIGHT.value.roundToInt())
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
                // Resolved here (rather than only in the content) because the same color has to
                // reach three surfaces that must never disagree: the card the content draws, the
                // full-bleed background behind it, and the *native* window underneath both. It is
                // the same input and the same fallback KeryxAlertDialog applies to its own card
                // within this very KeryxTheme scope, so the two always resolve identically.
                val resolvedContainerColor = containerColor.takeOrElse { MaterialTheme.colorScheme.surface }

                // Paint the native window/content-pane with the dialog's own background color, the
                // same technique (and for the same reason) as main.kt does for the main window: the
                // AWT window's default background is the platform Look & Feel's, i.e. light, and it
                // is what shows through wherever Compose hasn't painted yet. Two places expose it —
                // the area a resize adds before Compose repaints it, and the rectangle the native
                // button row's SwingPanel punches out of the Compose canvas with BlendMode.Clear
                // (SwingInteropContainer schedules its bounds update asynchronously and follows it
                // with a validate()/repaint() of the whole dialog root). Applied synchronously
                // during composition, not from a LaunchedEffect, so it lands before the first frame
                // — the same call this function's remember(window) block above already makes for
                // the other AWT properties. Keyed on the color so a runtime theme switch follows.
                remember(resolvedContainerColor) {
                    val nativeSurface = java.awt.Color(resolvedContainerColor.toArgb())
                    window.background = nativeSurface
                    (window as? RootPaneContainer)?.contentPane?.background = nativeSurface
                }

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

                // Held through rememberUpdatedState: the effect below lives as long as the dialog
                // and must never keep a stale conversion — density and maxHeightDp both change if
                // the dialog is dragged onto a screen with a different scale factor. Keying the
                // effect on them instead would reset the guard's state and re-place a tabbed dialog
                // on, say, a font-scale change.
                //
                // Width is always initialWidth — never derived from contentPx.width — and insets
                // are read fresh from `window` on every invocation rather than memoized once per
                // recomposition, since this lambda already runs once per drift-guard tick (i.e. on
                // every native size change, including the window manager finally reparenting the
                // window and reporting real insets for the first time). See fitWindowSize's and
                // decorationAllowanceFor's docs for why.
                val fitSize: (IntSize) -> DpSize? = { contentPx ->
                    val decorationAllowance = decorationAllowanceFor(window.insets, isMacOs, titleBarAllowanceDp)
                    fitWindowSize(initialWidth, contentPx.height, density, maxHeightDp, decorationAllowance)
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
                        // Size and position go onto the native window as ONE bounds change.
                        // Writing only DialogState for the position while pushing the size
                        // straight to AWT left the two out of step: the size landed
                        // synchronously, the position a Channel hop later through Compose's
                        // UpdateEffect, so a frame painted in between showed the dialog at its
                        // final size but at the location AWT gives a freshly constructed Window —
                        // the screen origin plus the screen insets, i.e. the top-left corner.
                        // Whether a frame lands in that gap is pure scheduling, hence the
                        // intermittency. DialogState is still written so Compose's model stays
                        // truthful and keeps the setPreferredSize + pack() path it uses while the
                        // peer does not exist yet.
                        val position = if (decision.applyPosition) {
                            resolvePosition(cursorPoint, owner, screenBounds, target)
                        } else {
                            null
                        }
                        if (decision.applySize) dialogState.size = target
                        if (position != null) dialogState.position = position
                        applyWindowGeometry(
                            window,
                            target.takeIf { decision.applySize },
                            position,
                            minSize = DpSize(initialWidth, MIN_DIALOG_HEIGHT),
                        )
                        if (decision.reportGiveUp) {
                            Log.warn(
                                LOG_TAG,
                                "Dialog stayed at $actual after $MAX_FIT_CORRECTIONS attempts to fit $target",
                            )
                        }
                        if (decision.presentable && !readyToShow) {
                            // Draw the fitted frame into the still-invisible window before letting
                            // AwtWindow show it, so the first visible frame is already the final
                            // one. This is the same API, used for the same reason, that Compose
                            // Desktop's own SwingDialog.update calls when the peer first becomes
                            // displayable ("make sure we draw the first frame before making the
                            // dialog visible to avoid showing the dialog background") — extended
                            // from the placeholder frame to the fitted one. The !readyToShow guard
                            // keeps it to exactly one call: later drift corrections on an
                            // already-visible dialog repaint through the normal path.
                            window.renderImmediately()
                            readyToShow = true
                        }
                    }
                }

                CompositionLocalProvider(LocalDialogMaxContentHeight provides maxHeightDp) {
                    // The outer Box always paints the OS window's full current bounds. The native
                    // window resize triggered below is asynchronous, so for at least one frame
                    // (or longer, if the size feedback loop hasn't settled) the window can be
                    // larger than what content actually measures — without this, that surplus area
                    // would show Skia's default (light) clear color instead of the theme.
                    //
                    // It paints the card's OWN color, not a distinct tone: a different tone (this
                    // used to be surfaceContainerLow against a `surface` card — #141218 vs #1D1B20
                    // in the M3 dark scheme) reads as a visible band around the card for as long as
                    // the size takes to settle, which is precisely the window in which the surplus
                    // exists at all.
                    Box(Modifier.fillMaxSize().background(resolvedContainerColor)) {
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
                                // The same override for width, now belt-and-suspenders rather than
                                // load-bearing. Content asks for its width with
                                // `Modifier.width(initialWidth)`, which — unlike requiredWidth —
                                // clamps to the incoming max (see placeholderSize's KDoc). Bounded by
                                // the window, that max is whatever size the native window happens to
                                // report at measure time; a DialogWindow that has not yet reached its
                                // requested size measures narrower. This used to be able to become
                                // permanent: fitSize (above) fed that narrower measurement straight
                                // back into the next requested window width, which then measured
                                // narrower still — a self-amplifying shrink that reproduced on Linux as
                                // a modeless dialog's window collapsing to ~1dp wide over the following
                                // second (see fitWindowSize's doc and "Dialogs occasionally opened at
                                // an unexpected size" in docs/known-issues.md). fitSize no longer reads
                                // contentPx.width at all — the requested width is always initialWidth —
                                // so that feedback path is gone regardless of what the window
                                // momentarily reports here. This modifier still matters for a plainer
                                // reason: without it, a transiently narrow window would visibly clip
                                // the tab bar (a plain non-wrapping Row, ~530dp for the Japanese
                                // labels) for however long that transient narrowness lasts.
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
            visible = readyToShow,
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
            visible = readyToShow,
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
private class ButtonRowLayoutInputs {
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
    DesktopModalWindow(
        title = title,
        onDismissRequest = onDismissRequest,
        modal = modal,
        // Forwarded raw (not pre-resolved): DesktopModalWindow applies the same Unspecified ->
        // surface fallback inside its own KeryxTheme scope, which is the scope that owns the
        // full-bleed background and the native window it also has to paint.
        containerColor = containerColor,
    ) {
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
