package works.merc.keryx.app.platform

/**
 * Always `true`: Android's web view is `android.webkit.WebView`, supplied by the OS itself on every
 * supported architecture, so the missing-native-binary problem this check exists for cannot arise.
 */
actual fun isNativeWebViewSupported(): Boolean = true
