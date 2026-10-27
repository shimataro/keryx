package works.merc.keryx.app.platform

import io.github.kdroidfilter.webview.web.NativeWebView

/** What the article reader's native web view reports about its scrolling — see [observeNativeWebViewScroll]. */
interface NativeWebViewScrollListener {
    /** The view is now scrolled to [fraction] of its scrollable height (0 at the top, 1 at the bottom). */
    fun onScrolled(fraction: Float)

    /** The user touched the view, turned a mouse wheel over it, or pressed a key in it. */
    fun onUserInput()
}

/**
 * Reports [webView]'s vertical scrolling and the user's own input to [listener], so the reader can
 * restore the position after the view's document is loaded again
 * ([works.merc.keryx.app.ui.home.ReaderScrollRestoration]). Call once per view.
 *
 * Kept behind expect/actual because the mechanism is platform-specific (`android.view.View`'s
 * scroll, touch, generic-motion and key listeners on Android) and has no counterpart on the common
 * [NativeWebView] type. Desktop's `WryWebViewPanel` exposes no scroll position, and neither of the
 * cases the restore exists for — the OS killing the process in the background, or the renderer
 * process dying under a live app — arises there, so its `actual` is a no-op.
 */
expect fun observeNativeWebViewScroll(webView: NativeWebView, listener: NativeWebViewScrollListener)

/**
 * The height of [webView]'s current document, in the units the platform reports it in — compared
 * only with itself, to tell when the content grew. Constant (0) where [observeNativeWebViewScroll]
 * is a no-op.
 */
expect fun nativeWebViewContentHeight(webView: NativeWebView): Int

/** Scrolls [webView] to [fraction] of its scrollable height. A no-op where there is nothing to scroll yet. */
expect fun scrollNativeWebViewTo(webView: NativeWebView, fraction: Float)
