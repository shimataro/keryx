package works.merc.keryx.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.window.LocalWindowExceptionHandlerFactory
import androidx.compose.ui.window.WindowExceptionHandler
import androidx.compose.ui.window.WindowExceptionHandlerFactory
import works.merc.keryx.app.core.Log

private const val LOG_TAG = "Main"

/**
 * The window exception handler factory to provide around the main window: it logs any exception
 * surfaced during composition/rendering before the default handler runs; otherwise EDT crashes
 * never reach keryx.N.log (background paths already log via runCatching). It captures the current
 * (default) factory and delegates to it, so its existing behavior (error dialog + rethrow) is
 * preserved unchanged — it only adds the log line. Logging is guarded so a logger failure can't
 * swallow the original crash. Remembered because LocalWindowExceptionHandlerFactory is a static
 * CompositionLocal: providing a fresh value would force the whole Window/App subtree to recompose
 * on every unrelated recomposition of the calling scope.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun rememberLoggingExceptionHandlerFactory(): WindowExceptionHandlerFactory {
    val defaultExceptionFactory = LocalWindowExceptionHandlerFactory.current
    return remember(defaultExceptionFactory) {
        WindowExceptionHandlerFactory { win ->
            val downstream = defaultExceptionFactory.exceptionHandler(win)
            WindowExceptionHandler { throwable ->
                runCatching { Log.error(LOG_TAG, "Uncaught exception in window", throwable) }
                downstream.onException(throwable)
            }
        }
    }
}
