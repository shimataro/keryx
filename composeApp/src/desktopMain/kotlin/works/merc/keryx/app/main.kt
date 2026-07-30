package works.merc.keryx.app

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import androidx.compose.ui.window.application
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.swing.Swing
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.getString
import org.jetbrains.compose.resources.painterResource
import org.koin.core.context.startKoin
import org.koin.mp.KoinPlatform
import works.merc.keryx.app.core.AppNotification
import works.merc.keryx.app.core.AppNotificationAction
import works.merc.keryx.app.core.AppNotificationLevel
import works.merc.keryx.app.core.Log
import works.merc.keryx.app.core.SystemClock
import works.merc.keryx.app.core.WINDOW_DEFAULT_HEIGHT
import works.merc.keryx.app.core.WINDOW_DEFAULT_WIDTH
import works.merc.keryx.app.core.WINDOW_MIN_HEIGHT
import works.merc.keryx.app.core.WINDOW_MIN_WIDTH
import works.merc.keryx.app.data.local.FtsManager
import works.merc.keryx.app.di.appModule
import works.merc.keryx.app.di.configureImageLoader
import works.merc.keryx.app.di.platformModule
import works.merc.keryx.app.domain.ActivityCenter
import works.merc.keryx.app.domain.ArticleRepository
import works.merc.keryx.app.domain.CloudSession
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.IdGenerator
import works.merc.keryx.app.domain.NewArticleNotifier
import works.merc.keryx.app.domain.NotificationCenter
import works.merc.keryx.app.domain.NotificationMessages
import works.merc.keryx.app.domain.OAuthCallbackParams
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.parseOAuthUri
import works.merc.keryx.app.domain.SettingsRepository
import works.merc.keryx.app.domain.SyncRepository
import works.merc.keryx.app.domain.UpdateChecker
import works.merc.keryx.app.domain.UpdateStatus
import works.merc.keryx.app.domain.shouldCheckForUpdate
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import works.merc.keryx.app.platform.isLinux
import works.merc.keryx.app.platform.isMacOs
import works.merc.keryx.app.platform.LocalNativeWindow
import works.merc.keryx.app.platform.LocalWindowDragArea
import works.merc.keryx.app.platform.WindowChrome
import works.merc.keryx.app.resources.Res
import works.merc.keryx.app.resources.app_icon
import works.merc.keryx.app.resources.notification_app_translocated
import works.merc.keryx.app.resources.notification_app_translocated_detail
import works.merc.keryx.app.resources.tray_icon
import works.merc.keryx.app.resources.update_available_notification
import works.merc.keryx.app.appmenu.AppMenuBarHost
import works.merc.keryx.app.appmenu.AppMenuConnection
import works.merc.keryx.app.tray.KeryxTray
import works.merc.keryx.app.tray.SniConnection
import works.merc.keryx.app.ui.home.HomeViewModel
import works.merc.keryx.app.ui.menu.MenuCommand
import works.merc.keryx.app.ui.menu.MenuController
import works.merc.keryx.app.ui.theme.installLookAndFeel
import works.merc.keryx.app.ui.theme.keryxSurfaceColor
import works.merc.keryx.app.ui.theme.resolveDarkTheme
import works.merc.keryx.app.ui.theme.updateLookAndFeel
import java.awt.Desktop
import java.awt.Dimension
import java.awt.Frame
import java.awt.Image
import java.awt.Taskbar
import java.io.File
import javax.swing.SwingUtilities

private const val LOG_TAG = "Main"

// replay = 1 (not extraBufferCapacity): on a cold .opml launch, incomingArg is dispatched — and can
// finish importing and tryEmit here — before Compose mounts the window and the LaunchedEffect below
// starts collecting. With replay = 0, a value emitted with no active subscriber is simply dropped
// (see kotlinx.coroutines SharedFlow docs), which would leave the window hidden if startMinimized is
// set. replay = 1 guarantees the first subscriber still receives it regardless of ordering.
private val activationRequests = MutableSharedFlow<Unit>(replay = 1)

/**
 * Starts the Keryx desktop application and coordinates its initialization, single-instance behavior,
 * native integrations, background tasks, and main window.
 *
 * @param args Command-line arguments, including an optional `keryx://` callback URI or an `.opml`
 * file path.
 */
@OptIn(FlowPreview::class, ExperimentalComposeUiApi::class)
fun main(args: Array<String>) {
    // If the OS launched us with a custom-scheme redirect URI or an .opml file path
    // (Windows/Linux), capture it before single-instance coordination.
    val incomingArg = args.firstOrNull { classifyLaunchArg(it) != null }
    // Must be set before any AWT/Compose initialization, otherwise macOS falls back to the main class name.
    System.setProperty("apple.awt.application.name", "Keryx")
    // Render the application menu bar (AppMenuBar) in the macOS system menu bar rather than inside the
    // window — the window uses a merged/transparent title bar (apple.awt.fullWindowContent), so an
    // in-window menu bar would be out of place. Must also be set before AWT initializes.
    if (isMacOs) {
        System.setProperty("apple.laf.useScreenMenuBar", "true")
    }

    val singleInstanceCoordinator = SingleInstanceCoordinator(File(AppDirs.appDataDir()))
    if (!singleInstanceCoordinator.tryAcquireLock()) {
        // Another instance is already running. On macOS the OAuth redirect URI and an opened .opml
        // file are both delivered via an Apple Event (setOpenURIHandler / setOpenFileHandler), not
        // argv, so incomingArg is null here and only a plain activation is forwarded — see the
        // diagnostic note in docs/sync-architecture.md.
        Log.info(LOG_TAG, "Single-instance lock held by another instance; forwarding activation (hasArg=${incomingArg != null}) and exiting")
        singleInstanceCoordinator.signalRunningInstance(incomingArg)
        return
    }
    Log.info(LOG_TAG, "Acquired single-instance lock; running as primary instance from ${currentExecutablePath()}")
    // startActivationListener is registered after Koin initialization (see below)
    // so we can inject the URI callback flow.

    startKoin { modules(appModule, platformModule) }
    val koin = KoinPlatform.getKoin()

    // Register activation listener now that Koin is ready so we can emit incoming URIs into the
    // shared callback flow. dispatchOpmlFile resolves its own CoroutineScope/repository/notification
    // dependencies via koin.get<>() rather than capturing appScope, which is declared further down
    // (after this point) and would not be in scope here.
    val callbackFlow = koin.get<MutableSharedFlow<OAuthCallbackParams>>()
    fun dispatchOAuthCallback(uri: String) {
        // Do not log the URI itself — it carries the OAuth authorization code.
        Log.info(LOG_TAG, "OAuth callback URI received")
        runCatching { callbackFlow.tryEmit(parseOAuthUri(uri)) }
    }
    fun dispatchOpmlFile(path: String) {
        koin.get<CoroutineScope>().launch { handleOpenedOpmlFile(koin, path) }
    }
    fun dispatchLaunchArg(arg: String) {
        when (val launchArg = classifyLaunchArg(arg)) {
            is LaunchArg.OAuthCallback -> dispatchOAuthCallback(launchArg.uri)
            is LaunchArg.OpmlFile -> dispatchOpmlFile(launchArg.path)
            null -> Unit
        }
    }
    singleInstanceCoordinator.startActivationListener { uri ->
        if (!uri.isNullOrBlank()) dispatchLaunchArg(uri)
        activationRequests.tryEmit(Unit)
    }
    // startActivationListener only fires for a *second* launch forwarding to this one — the
    // primary instance's own launch args need dispatching separately. This matters mainly for an
    // .opml file-association cold start on Windows/Linux: unlike the OAuth callback (which only
    // ever arrives while Keryx is already running), double-clicking a file when Keryx isn't running
    // yet is the primary way this feature gets used.
    incomingArg?.let { dispatchLaunchArg(it) }

    configureImageLoader(koin.get<HttpClient>(), AppDirs.cacheDir())

    // Startup recovery: recreate articles_fts if a previous sync dropped it, and backfill any
    // articles missing from the index (e.g. fetched before the FTS index existed).
    koin.get<FtsManager>().ensureIndexed()

    val settingsRepository = koin.get<SettingsRepository>()
    // Local settings are persisted off-thread and coalesced (see SettingsRepository), so a change
    // made shortly before quitting may not have hit disk yet. Flush on JVM shutdown so the latest
    // value (theme, pane widths, last-selected article, setup completion, …) is never lost on exit.
    Runtime.getRuntime().addShutdownHook(
        Thread { runCatching { runBlocking { settingsRepository.flush() } } },
    )
    val saved = settingsRepository.getLocalSettings()

    // Both still before any AWT/Compose initialization (SingleInstanceCoordinator/Koin/settings
    // loading above don't touch AWT) — kept together since it's unconfirmed whether
    // installLookAndFeel itself begins toolkit init, which would make setting the appearance
    // property afterwards too late.
    //
    // "system" can't be resolved to dark/light here — isSystemInDarkTheme() is a Compose API and
    // Compose hasn't started yet — so assume light and let the effect inside the window (which
    // does have it) correct the choice. No Swing surface exists before Compose's first
    // composition: the menu bar is created inside it, and menus/dialog buttons are on demand.
    installLookAndFeel(resolveDarkTheme(saved.themeMode, systemDark = false))
    // Without this, Aqua's Swing L&F always paints light-mode colors regardless of the OS's
    // actual Dark Mode setting (JDK-8235363), which looks mismatched against this app's own dark
    // theme. Follow the app's own theme choice rather than a static "system" value so Swing's
    // native buttons match the rest of the (Compose-themed) dialog card even when the user has
    // overridden the app's theme independently of the OS. Note: changing the in-app theme without
    // restarting won't update this — it's read once at startup.
    if (isMacOs) {
        System.setProperty(
            "apple.awt.application.appearance",
            when (saved.themeMode) {
                "light" -> "NSAppearanceNameAqua"
                "dark" -> "NSAppearanceNameDarkAqua"
                else -> "system"
            },
        )
    }

    val appScope = koin.get<CoroutineScope>()

    // Pre-warm HomeViewModel (and its eagerly-shared query flows, see HomeViewModel.started)
    // before the window is shown, so feeds/articles are already populated by the time Home
    // is first composed instead of flashing empty lists. Skipped for not-yet-set-up users
    // to avoid spinning up query flows they won't see (mirrors App.kt's own setup-complete check).
    if (settingsRepository.isSetupComplete()) {
        koin.get<HomeViewModel>()
    }

    appScope.launch { runStartupTasks(koin) }

    // Bridges background "new articles" counts to the OS (KeryxTray picks the per-platform
    // sink: TrayIcon.displayMessage, org.freedesktop.Notifications, or TrayState, which only
    // exists inside the application {} scope) and to the in-app UI (HomeViewModel).
    val newArticleNotifier = koin.get<NewArticleNotifier>()
    val newArticleNotifications = newArticleNotifier.trayEvents
    appScope.launch { backgroundUpdateLoop(koin) }

    val menuController = koin.get<MenuController>()

    // Replace the JVM's default "About" panel (which shows "java" + the JVM version) with our
    // own About dialog, and route the native "Settings…" (⌘,) item to our Settings screen. Both
    // live in the macOS app (Keryx) menu, so they are omitted from AppMenuBar there. Registered
    // after the apple.awt.* properties above, since touching Desktop initializes AWT. macOS-only in
    // practice (APP_ABOUT / APP_PREFERENCES are unsupported elsewhere).
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_ABOUT)) {
            Desktop.getDesktop().setAboutHandler { menuController.send(MenuCommand.About) }
        }
    }.onFailure { Log.warn(LOG_TAG, "Could not install the About menu handler", it) }
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_PREFERENCES)) {
            Desktop.getDesktop().setPreferencesHandler { menuController.send(MenuCommand.OpenSettings) }
        }
    }.onFailure { Log.warn(LOG_TAG, "Could not install the Preferences menu handler", it) }

    // Install the in-process URI handler (macOS). Windows/Linux receive the URI as a
    // command-line argument instead, forwarded through SingleInstanceCoordinator.
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_URI)) {
            Desktop.getDesktop().setOpenURIHandler { event ->
                val uri = event.uri.toString()
                if (uri.startsWith("keryx://")) dispatchOAuthCallback(uri)
            }
        }
    }.onFailure { Log.warn(LOG_TAG, "Could not install URI handler", it) }

    // Install the in-process file-open handler (macOS). A double-clicked / "Open With"-launched
    // .opml file arrives here via an Apple Event whether or not Keryx was already running — unlike
    // Windows/Linux, which only ever deliver it as argv on a genuinely new process (see the
    // single-instance dispatch above).
    runCatching {
        if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.APP_OPEN_FILE)) {
            Desktop.getDesktop().setOpenFileHandler { event ->
                event.files
                    .filter { it.name.endsWith(".opml", ignoreCase = true) }
                    .forEach { file -> dispatchOpmlFile(file.absolutePath) }
            }
        }
    }.onFailure { Log.warn(LOG_TAG, "Could not install the .opml file-open handler", it) }

    // Tell the OS how to handle keryx:// URIs and .opml files (Windows registry / Linux .desktop +
    // mimeapps.list). macOS declares both in Info.plist at packaging time, so it needs nothing here.
    registerFileAssociations()

    // Linux: java.awt.SystemTray cannot draw a transparent icon on X11 (XTrayIconPeer fills the
    // canvas with the component background before drawing, and XSystemTrayPeer never adopts the
    // tray manager's ARGB visual), so the icon always ends up inside a white box. Use the native
    // StatusNotifierItem protocol when a host is available; null falls back to the AWT tray.
    // Resolved here, before the window exists, so the tray is up even with startMinimized.
    val sniConnection = if (isLinux) SniConnection.tryCreate() else null
    if (sniConnection != null) {
        Runtime.getRuntime().addShutdownHook(Thread { runCatching { sniConnection.close() } })
    }

    // Linux/KDE Global Menu: if the com.canonical.AppMenu.Registrar is present, export the app menu
    // over D-Bus so it can appear in the top-panel "Application Menu Bar" widget or titlebar button.
    // null (every non-KDE environment) leaves the in-window menu bar behaving exactly as before.
    // Independent of the tray (own connection, own lifecycle).
    val appMenuConnection = if (isLinux) AppMenuConnection.tryCreate() else null
    val resolvedAppMenuXid = java.util.concurrent.atomic.AtomicReference<Long?>(null)
    if (appMenuConnection != null) {
        Runtime.getRuntime().addShutdownHook(
            Thread { runCatching { appMenuConnection.close(resolvedAppMenuXid.get()) } },
        )
    }

    application {
        var windowVisible by remember { mutableStateOf(!saved.startMinimized) }
        val windowState = remember {
            WindowState(
                position = WindowPosition.Aligned(Alignment.Center),
                width = (saved.windowWidth ?: WINDOW_DEFAULT_WIDTH.toDouble()).coerceAtLeast(WINDOW_MIN_WIDTH.toDouble()).dp,
                height = (saved.windowHeight ?: WINDOW_DEFAULT_HEIGHT.toDouble()).coerceAtLeast(WINDOW_MIN_HEIGHT.toDouble()).dp,
            )
        }

        // Persist window size (debounced).
        LaunchedEffect(windowState) {
            snapshotFlow { windowState.size }.debounce(500).collect { size ->
                val current = settingsRepository.getLocalSettings()
                settingsRepository.saveLocalSettings(
                    current.copy(
                        windowWidth = size.width.value.toDouble(),
                        windowHeight = size.height.value.toDouble(),
                    ),
                )
            }
        }

        val unreadCount by koin.get<ArticleRepository>().watchUnreadCount().collectAsState(0L)

        // Dock/taskbar icon override (used below) needs the full branded icon, not the
        // small transparent tray glyph windowBaseImage uses for the window's own
        // title-bar/taskbar icon. Declared here (before the Dock activation-policy effect)
        // so that effect can re-apply the icon after a visibility toggle.
        val dockBaseImage = rememberDrawableImage(Res.drawable.app_icon)
        val dockBadgedImage = remember(dockBaseImage, unreadCount) { dockBaseImage?.let { drawUnreadBadge(it, unreadCount) } }

        // macOS only: the Dock icon / Cmd+Tab entry doesn't follow window visibility
        // on its own (apple.awt.UIElement is read once at AWT startup), so drive it
        // explicitly via the Cocoa activation policy whenever visibility changes.
        LaunchedEffect(windowVisible) {
            if (isMacOs) {
                SwingUtilities.invokeLater { MacActivationPolicy.setDockIconVisible(windowVisible) }
                if (windowVisible) {
                    // Switching the policy back to Regular recreates the Dock tile from
                    // scratch and discards the runtime taskbar.iconImage override, so
                    // re-apply the branded icon on a *later* EDT turn (guaranteed FIFO,
                    // after the tile is recreated).
                    SwingUtilities.invokeLater { applyBrandedDockIcon(dockBadgedImage) }
                }
            }
        }

        val windowBaseImage = rememberDrawableImage(Res.drawable.tray_icon)
        val windowBadgedImage = remember(windowBaseImage, unreadCount) { windowBaseImage?.let { drawUnreadBadge(it, unreadCount) } }
        val windowBadgedPainter = remember(windowBadgedImage) { windowBadgedImage?.let { BitmapPainter(it.toComposeImageBitmap()) } }

        KeryxTray(
            sniConnection = sniConnection,
            notificationIcon = dockBaseImage,
            unreadCount = unreadCount,
            windowVisible = windowVisible,
            onToggle = { windowVisible = !windowVisible },
            onQuit = ::exitApplication,
            newArticleNotifications = newArticleNotifications,
        )

        // Log any exception surfaced during composition/rendering before the default
        // handler runs; otherwise EDT crashes never reach keryx.N.log (background paths
        // already log via runCatching). We capture the current (default) factory and
        // delegate to it, so its existing behavior (error dialog + rethrow) is preserved
        // unchanged - we only add the log line. Logging is guarded so a logger failure
        // can't swallow the original crash. Remembered because LocalWindowExceptionHandlerFactory
        // is a static CompositionLocal: providing a fresh value would force the whole
        // Window/App subtree to recompose on every unrelated recomposition of this scope.
        val defaultExceptionFactory = LocalWindowExceptionHandlerFactory.current
        val exceptionHandlerFactory = remember(defaultExceptionFactory) {
            WindowExceptionHandlerFactory { win ->
                val downstream = defaultExceptionFactory.exceptionHandler(win)
                WindowExceptionHandler { throwable ->
                    runCatching { Log.error(LOG_TAG, "Uncaught exception in window", throwable) }
                    downstream.onException(throwable)
                }
            }
        }
        CompositionLocalProvider(LocalWindowExceptionHandlerFactory provides exceptionHandlerFactory) {
        Window(
            onCloseRequest = { windowVisible = false },
            title = "Keryx",
            state = windowState,
            visible = windowVisible,
            icon = windowBadgedPainter ?: painterResource(Res.drawable.tray_icon),
        ) {
            // The resolved theme's surface color, used just below to pre-fill the native window
            // background. Resolved from the startup theme snapshot (enough for the launch flash;
            // runtime theme switches are covered by Compose's own full-window Surface fill).
            val windowSurface = keryxSurfaceColor(resolveDarkTheme(saved.themeMode, isSystemInDarkTheme()))

            // Run synchronously during composition (not via LaunchedEffect, which is
            // dispatched as a coroutine after composition commits) so the chrome switch
            // happens as early as possible, minimizing the standard-title-bar flash.
            remember {
                window.minimumSize = Dimension(WINDOW_MIN_WIDTH, WINDOW_MIN_HEIGHT)
                // Paint the native window/content-pane with the theme surface so a dark-mode
                // launch doesn't flash the platform-default (light) background in the gap between
                // the window becoming visible and Compose's first (already-dark) frame. Which of
                // the two actually fills the visible pixels is platform/timing dependent, so set
                // both (an opaque dark fill is harmless on either).
                val nativeSurface = java.awt.Color(windowSurface.toArgb())
                window.background = nativeSurface
                window.contentPane.background = nativeSurface
                if (isMacOs) {
                    // Measure the OS's actual title bar height *before* switching to
                    // the transparent/merged style, since fullWindowContent changes
                    // how insets are reported afterward. This adapts automatically
                    // to whatever the current macOS version/system settings use,
                    // instead of hardcoding a guessed constant.
                    WindowChrome.titleBarInsetDp = window.insets.top.toFloat()
                    window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
                    window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
                    window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
                }
                Unit
            }

            // Keep the Swing look and feel (menu bar, context menus, dialog buttons) on the same
            // light/dark side as the Compose UI. Unlike `saved`, this follows the live setting, so
            // an in-app theme change applies without a restart (Linux only — see
            // updateLookAndFeel). Collected inside the effect rather than via collectAsState so a
            // theme-unrelated local-settings write (pane widths change on every drag frame) can't
            // recompose the window content.
            val systemDark = isSystemInDarkTheme()
            LaunchedEffect(systemDark) {
                settingsRepository.localSettings
                    .map { resolveDarkTheme(it.themeMode, systemDark) }
                    .distinctUntilChanged()
                    .collect { dark -> withContext(Dispatchers.Swing) { updateLookAndFeel(dark) } }
            }

            // Application menu bar (macOS: system menu bar; Windows/Linux: in-window). On KDE with a
            // Global Menu registrar, AppMenuBarHost also exports it over D-Bus and hides the in-window
            // bar once registered. Closing the window hides to the tray, matching onCloseRequest.
            AppMenuBarHost(
                appMenuConnection = appMenuConnection,
                windowVisible = windowVisible,
                onCloseWindow = { windowVisible = false },
                onQuit = ::exitApplication,
                resolvedXid = resolvedAppMenuXid,
            )

            // A second launch signals this instance (via SingleInstanceCoordinator's
            // loopback socket) instead of opening its own window. Bring this window
            // to front and restore it from the tray / OS-level minimized state.
            LaunchedEffect(Unit) {
                activationRequests.collect {
                    windowVisible = true
                    SwingUtilities.invokeLater {
                        window.isVisible = true
                        window.extendedState = window.extendedState and Frame.ICONIFIED.inv()
                        if (isMacOs) {
                            // LaunchedEffect(windowVisible) above only re-fires on a value change, so it
                            // won't call activateIgnoringOtherApps when the window was already visible
                            // (just backgrounded, not tray-hidden/minimized). Call it unconditionally here
                            // on every activation signal instead - harmless no-op when the Dock policy is
                            // already REGULAR.
                            MacActivationPolicy.setDockIconVisible(true)
                            // As above: re-apply the branded icon on a later EDT turn, since the policy
                            // switch may have recreated the Dock tile and dropped the icon override.
                            SwingUtilities.invokeLater { applyBrandedDockIcon(dockBadgedImage) }
                        }
                        window.toFront()
                        window.requestFocus()
                    }
                }
            }

            // Menu commands that must be visible on screen — "About Keryx" and "Settings…" from the
            // native macOS app menu — may fire while the window is tray-hidden, so surface it. App()
            // handles the actual navigation/dialog off the same MenuController.commands flow.
            LaunchedEffect(Unit) {
                menuController.commands.collect { command ->
                    if (command == MenuCommand.About || command == MenuCommand.OpenSettings) {
                        windowVisible = true
                    }
                }
            }

            // Dock/taskbar badge. Native macOS badge API (Taskbar.setIconBadge) was
            // tried and confirmed to call through without error but never actually
            // render on this macOS/JDK combination (verified via temporary logging)
            // - so macOS uses the same full-icon-composite fallback as other
            // platforms below instead.
            LaunchedEffect(unreadCount, dockBadgedImage) {
                if (Taskbar.isTaskbarSupported()) {
                    val taskbar = Taskbar.getTaskbar()
                    SwingUtilities.invokeLater {
                        when {
                            taskbar.isSupported(Taskbar.Feature.ICON_BADGE_IMAGE_WINDOW) ->
                                taskbar.setWindowIconBadge(window, drawBadgeOnlyImage(unreadCount))
                            else -> applyBrandedDockIcon(dockBadgedImage)
                        }
                    }
                }
            }

            // macOS integrates the title bar into the content, losing the OS's drag-to-move, so we wrap
            // the header strip in WindowDraggableArea to restore it. Windows/Linux keep the OS title bar,
            // so this stays a pass-through (no-op). WindowDraggableArea can only be called within
            // FrameWindowScope, so we capture that receiver (this@Window) and close over it in the wrapper.
            val frameScope = this
            val dragWrapper: @Composable (Modifier, @Composable () -> Unit) -> Unit =
                if (isMacOs) {
                    { mod, dragContent -> with(frameScope) { WindowDraggableArea(mod) { dragContent() } } }
                } else {
                    { _, dragContent -> dragContent() }
                }
            CompositionLocalProvider(
                LocalNativeWindow provides window,
                LocalWindowDragArea provides dragWrapper,
            ) {
                App()
            }
        }
        } // CompositionLocalProvider(LocalWindowExceptionHandlerFactory)
    }
}

/**
 * Applies the provided image as the taskbar or Dock icon when supported.
 *
 * @param image The icon image to apply, or `null` if no image is available.
 */
private fun applyBrandedDockIcon(image: Image?) {
    if (image == null || !Taskbar.isTaskbarSupported()) return
    val taskbar = Taskbar.getTaskbar()
    if (taskbar.isSupported(Taskbar.Feature.ICON_IMAGE)) {
        taskbar.iconImage = image
    }
}

/**
 * Cache cleanup (once per 24h) + Dropbox sync if connected + an unconditional feed
 * refresh/new-article check + an unconditional update check.
 */
private suspend fun runStartupTasks(koin: org.koin.core.Koin) {
    runCatching {
        warnIfAppTranslocated(koin)
        val settingsRepository = koin.get<SettingsRepository>()
        val settings = settingsRepository.getLocalSettings()
        val now = SystemClock.nowMillis()
        val last = settings.lastCacheCleanupAt
        val dayMillis = 24L * 60 * 60 * 1000
        if (last == null || now - last >= dayMillis) {
            val days = settingsRepository.getCacheRetentionDays()
            koin.get<ArticleRepository>().deleteExpiredArticles(days)
            settingsRepository.saveLocalSettings(settings.copy(lastCacheCleanupAt = now))
        }
        if (koin.get<CloudSession>().isConnected()) {
            koin.get<SyncRepository>().sync()
        }
        refreshFeedsAndNotify(koin)
        checkForUpdateAndNotify(koin)
        maybeRebuildFtsIndex(koin)
    }.onFailure { if (it is CancellationException) throw it else Log.error(LOG_TAG, "Startup tasks failed", it) }
}

/**
 * Once-per-24h healing rebuild of the full FTS index, run only while the app is idle (no sync / feed
 * refresh in flight) so it never competes with a foreground operation — with `busy_timeout` set, a
 * concurrent search waits rather than erroring anyway. Re-indexes content that incremental indexing
 * ([FtsManager.indexMissing]) left stale and sweeps entries left by cache-cleanup deletions. A cheap
 * no-op until 24h elapse. Shared by [runStartupTasks] and [backgroundUpdateLoop].
 */
private fun maybeRebuildFtsIndex(koin: org.koin.core.Koin) {
    val activityCenter = koin.get<ActivityCenter>()
    if (activityCenter.syncing.value || activityCenter.feedRefreshing.value) return
    val settingsRepository = koin.get<SettingsRepository>()
    val now = SystemClock.nowMillis()
    val last = settingsRepository.getLocalSettings().lastFtsRebuiltAt
    val dayMillis = 24L * 60 * 60 * 1000
    if (last != null && now - last < dayMillis) return
    koin.get<FtsManager>().rebuildIndex()
    settingsRepository.saveLocalSettings(settingsRepository.getLocalSettings().copy(lastFtsRebuiltAt = now))
}

/**
 * Refreshes all feeds and, if new articles were fetched and notifications are enabled, notifies
 * via [NewArticleNotifier]. Shared by [runStartupTasks] and [backgroundUpdateLoop].
 */
private suspend fun refreshFeedsAndNotify(koin: org.koin.core.Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    val results = koin.get<ActivityCenter>().trackFeedRefresh { koin.get<FeedRepository>().refreshAll() }
    koin.get<NewArticleNotifier>().notifyIfEnabled(
        results, settingsRepository.getLocalSettings().notificationEnabled, koin.get<NotificationMessages>(),
    )
}

/**
 * Desktop background refresh loop (coroutine equivalent of a periodic timer). Also drives the
 * periodic (non-startup) update check on its own, independent cadence — see
 * [shouldCheckForUpdate] — so setting feed refresh to "manual only" (minutes <= 0) doesn't starve
 * update checking of everything but the once-per-launch startup check. Feed refresh is similarly
 * skipped here when "manual only" is set, but still gets one unconditional check at startup via
 * [runStartupTasks].
 */
private suspend fun backgroundUpdateLoop(koin: org.koin.core.Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    while (true) {
        val minutes = settingsRepository.getLocalSettings().refreshIntervalMinutes
        delay(if (minutes <= 0) 60_000L else minutes * 60_000L)
        runCatching {
            if (minutes > 0) {
                refreshFeedsAndNotify(koin)
                koin.get<SyncRepository>().sync()
            }
            val settings = settingsRepository.getLocalSettings()
            if (shouldCheckForUpdate(SystemClock.nowMillis(), settings.lastUpdateCheckAt, settings.updateCheckIntervalHours)) {
                checkForUpdateAndNotify(koin)
            }
            maybeRebuildFtsIndex(koin)
        }.onFailure { if (it is CancellationException) throw it else Log.error(LOG_TAG, "Background update cycle failed", it) }
    }
}

/**
 * Runs [UpdateChecker.check] and records [LocalSettings.lastUpdateCheckAt]. This is the only
 * place that writes that timestamp — the Settings screen's manual "check for update" button calls
 * [UpdateChecker.check] directly instead, so it never perturbs the automatic schedule.
 */
private suspend fun checkForUpdateAndNotify(koin: org.koin.core.Koin) {
    val settingsRepository = koin.get<SettingsRepository>()
    val status = koin.get<UpdateChecker>().check()
    settingsRepository.saveLocalSettings(
        settingsRepository.getLocalSettings().copy(lastUpdateCheckAt = SystemClock.nowMillis()),
    )
    if (status is UpdateStatus.Available) {
        val message = getString(Res.string.update_available_notification, status.version)
        koin.get<NotificationCenter>().add(
            AppNotification(
                id = IdGenerator.newId(),
                level = AppNotificationLevel.INFO,
                message = message,
                timestampMillis = SystemClock.nowMillis(),
                // Acting on the notification goes straight to the release page — the only useful
                // next step for "a new version exists".
                action = AppNotificationAction.OpenUrl(status.url),
            ),
        )
    }
}

/**
 * Reads an OPML file opened via a file association (double-click / "Open With" on an `.opml`
 * file), subscribes to every feed it lists, and surfaces the result via the notification center.
 * No dialog is shown for this — [activationRequests] brings the window to front and the new feeds
 * appear live in the (already-visible) feed list, matching the "restrained notification" treatment
 * error-design.md already prescribes for background-originated events.
 */
private suspend fun handleOpenedOpmlFile(koin: org.koin.core.Koin, path: String) {
    val xml = FileIO.readText(path) ?: run {
        Log.warn(LOG_TAG, "Could not read the opened OPML file")
        return
    }
    val outcome = koin.get<OpmlImporter>().import(xml)
    val message = koin.get<NotificationMessages>().opmlImported(outcome.added, outcome.failed)
    koin.get<NotificationCenter>().add(
        AppNotification(
            id = IdGenerator.newId(),
            level = AppNotificationLevel.INFO,
            message = message,
            timestampMillis = SystemClock.nowMillis(),
        ),
    )
    activationRequests.tryEmit(Unit)
}

/**
 * Warns the user when the application is running from a translocated path that may prevent
 * `keryx://` OAuth callbacks from reaching the application.
 */
private suspend fun warnIfAppTranslocated(koin: org.koin.core.Koin) {
    val exePath = currentExecutablePath()
    if (!isTranslocatedPath(exePath)) return
    Log.warn(LOG_TAG, "App is running from a translocated path ($exePath); keryx:// OAuth linking may fail")
    koin.get<NotificationCenter>().add(
        AppNotification(
            id = IdGenerator.newId(),
            level = AppNotificationLevel.WARNING,
            message = getString(Res.string.notification_app_translocated),
            timestampMillis = SystemClock.nowMillis(),
            // Nothing to navigate to — the useful next step is understanding the cause and the fix,
            // so acting on it opens an explanatory dialog in place.
            action = AppNotificationAction.ShowInfoDialog(
                getString(Res.string.notification_app_translocated_detail),
            ),
        ),
    )
}
