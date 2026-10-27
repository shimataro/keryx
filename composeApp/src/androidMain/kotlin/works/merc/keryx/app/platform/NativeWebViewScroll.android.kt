package works.merc.keryx.app.platform

import android.annotation.SuppressLint
import android.view.MotionEvent
import io.github.kdroidfilter.webview.web.NativeWebView
import kotlin.math.roundToInt

/**
 * Listens through the view's own scroll, touch, generic-motion (mouse wheel) and key listeners, none
 * of which the web view library sets. Every listener returns false, leaving the event to the view.
 */
@SuppressLint("ClickableViewAccessibility")
actual fun observeNativeWebViewScroll(webView: NativeWebView, listener: NativeWebViewScrollListener) {
    webView.setOnScrollChangeListener { _, _, scrollY, _, _ ->
        val range = scrollRange(webView)
        listener.onScrolled(if (range > 0) scrollY.toFloat() / range else 0f)
    }
    webView.setOnTouchListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_DOWN) listener.onUserInput()
        false
    }
    webView.setOnGenericMotionListener { _, event ->
        if (event.actionMasked == MotionEvent.ACTION_SCROLL) listener.onUserInput()
        false
    }
    webView.setOnKeyListener { _, _, _ ->
        listener.onUserInput()
        false
    }
}

actual fun nativeWebViewContentHeight(webView: NativeWebView): Int = webView.contentHeight

actual fun scrollNativeWebViewTo(webView: NativeWebView, fraction: Float) {
    val range = scrollRange(webView)
    if (range > 0) webView.scrollTo(webView.scrollX, (fraction * range).roundToInt())
}

/** How far the document scrolls vertically, in view pixels: its scaled height less the view's own. */
@Suppress("DEPRECATION") // `scale` is the only public way to map `contentHeight` (CSS px) to view pixels.
private fun scrollRange(webView: NativeWebView): Int =
    (webView.contentHeight * webView.scale).roundToInt() - webView.height
