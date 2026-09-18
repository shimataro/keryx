package works.merc.keryx.app.platform

import android.graphics.drawable.GradientDrawable
import android.os.Build
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.github.kdroidfilter.webview.web.NativeWebView

/** Matches [works.merc.keryx.app.platform.ScrollIndicatorOverlay]'s own corner radius, in dp. */
private const val SCROLLBAR_THUMB_CORNER_RADIUS_DP = 2f

/**
 * `View#setVerticalScrollbarThumbDrawable`/`setHorizontalScrollbarThumbDrawable` are only public
 * from API 29 (Android 10) onward; API 26–28 keep the platform's own (invisible-on-dark) default,
 * since there is no public API to replace it there.
 */
actual fun setNativeWebViewScrollbarColor(webView: NativeWebView, color: Color) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) return
    val cornerRadiusPx = SCROLLBAR_THUMB_CORNER_RADIUS_DP * webView.resources.displayMetrics.density
    val thumb = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        cornerRadius = cornerRadiusPx
        setColor(color.toArgb())
    }
    // Both directions get the same fixed color: the article body can scroll horizontally too (a
    // wide <table> or <pre> the CSS places no overflow-x guard around), and this drawable is a
    // static solid shape that never animates or invalidates itself, so the two roles safely share
    // one instance.
    webView.verticalScrollbarThumbDrawable = thumb
    webView.horizontalScrollbarThumbDrawable = thumb
}
