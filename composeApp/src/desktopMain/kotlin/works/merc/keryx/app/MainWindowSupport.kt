package works.merc.keryx.app

import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowState
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.onEach
import works.merc.keryx.app.core.WINDOW_MIN_HEIGHT
import works.merc.keryx.app.core.WINDOW_MIN_WIDTH
import works.merc.keryx.app.core.WINDOW_STATE_PERSIST_DEBOUNCE_MS
import works.merc.keryx.app.platform.WindowChrome
import works.merc.keryx.app.platform.applyMacMergedTitleBar
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.platform.paintNativeSurface
import java.awt.Dimension
import java.util.concurrent.atomic.AtomicReference
import javax.swing.JFrame

/**
 * Tracks [windowState]'s placement/size/position: every change goes into [latestWindowState] at
 * once (for the shutdown hook), and is handed to [persist] debounced by
 * [WINDOW_STATE_PERSIST_DEBOUNCE_MS], so a transient mid-transition value isn't the one saved.
 */
@OptIn(FlowPreview::class)
@Composable
internal fun PersistWindowStateEffect(
    windowState: WindowState,
    latestWindowState: AtomicReference<ObservedWindowState?>,
    persist: (ObservedWindowState) -> Unit,
) {
    LaunchedEffect(windowState) {
        snapshotFlow { observedWindowState(windowState) }
            .onEach { latestWindowState.set(it) }
            .debounce(WINDOW_STATE_PERSIST_DEBOUNCE_MS)
            .collect { observed -> persist(observed) }
    }
}

/**
 * Applies the main window's initial chrome: its minimum size, the native surface pre-fill, and on
 * macOS the merged title bar. Called synchronously from a `remember {}` during the window's first
 * composition (not from a LaunchedEffect) so the switch happens as early as possible.
 */
internal fun applyInitialWindowChrome(window: JFrame, windowSurface: Color) {
    window.minimumSize = Dimension(WINDOW_MIN_WIDTH, WINDOW_MIN_HEIGHT)
    // Paint the native window/content-pane with the theme surface so a dark-mode
    // launch doesn't flash the platform-default (light) background in the gap between
    // the window becoming visible and Compose's first (already-dark) frame. Which of
    // the two actually fills the visible pixels is platform/timing dependent, so set
    // both (an opaque dark fill is harmless on either).
    window.paintNativeSurface(windowSurface)
    if (isMacOs) {
        // Measure the OS's actual title bar height *before* switching to
        // the transparent/merged style, since fullWindowContent changes
        // how insets are reported afterward. This adapts automatically
        // to whatever the current macOS version/system settings use,
        // instead of hardcoding a guessed constant.
        WindowChrome.titleBarInsetDp = window.insets.top.toFloat()
        window.rootPane.applyMacMergedTitleBar()
    }
}

/**
 * The wrapper `LocalWindowDragArea` provides for the window's header strip. macOS integrates the
 * title bar into the content, losing the OS's drag-to-move, so there it wraps the content in
 * [WindowDraggableArea] (callable only within a [FrameWindowScope], hence [frameScope]); on
 * Windows/Linux, which keep the OS title bar, it is a pass-through.
 */
internal fun windowDragWrapper(frameScope: FrameWindowScope): @Composable (Modifier, @Composable () -> Unit) -> Unit =
    if (isMacOs) {
        { mod, dragContent -> with(frameScope) { WindowDraggableArea(mod) { dragContent() } } }
    } else {
        { _, dragContent -> dragContent() }
    }
