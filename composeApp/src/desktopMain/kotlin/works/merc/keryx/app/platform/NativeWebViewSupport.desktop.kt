package works.merc.keryx.app.platform

import works.merc.keryx.app.core.Log

private const val LOG_TAG = "NativeWebViewSupport"

/**
 * Overrides the probe below. `false` renders every article with the Compose fallback even where the
 * web view would work — the only practical way to develop and review that rendering, since the
 * fallback otherwise appears on no machine this project builds on. `true` forces the web view back
 * on if the probe ever misjudges a working install (see [WRY_ENTRY_POINT]'s note).
 */
private const val OVERRIDE_PROPERTY = "keryx.reader.webview"

/**
 * The library class whose initializer loads `libcomposewebview_wry` through JNA. Loading it is the
 * probe: it is exactly what fails on a platform/architecture pair the library ships no binary for.
 *
 * Named by string rather than referenced directly so that touching this file cannot itself trigger
 * the load. The cost is that a library upgrade renaming the class turns the probe into a permanent
 * `ClassNotFoundException` — which is why that case is logged as a warning distinct from a genuine
 * missing binary, and why [OVERRIDE_PROPERTY] can force the web view back on.
 */
private const val WRY_ENTRY_POINT = "io.github.kdroidfilter.webview.wry.UniffiLib"

/**
 * Probed once, lazily. The probe is not free — it loads a native library — but it runs immediately
 * before the reader would have loaded the same library anyway, so on a supported machine it only
 * moves that cost a few milliseconds earlier.
 */
private val supported: Boolean by lazy { resolveSupport() }

actual fun isNativeWebViewSupported(): Boolean = supported

private fun resolveSupport(): Boolean {
    System.getProperty(OVERRIDE_PROPERTY)?.toBooleanStrictOrNull()?.let { forced ->
        Log.info(LOG_TAG, "Native web view support forced to $forced by -D$OVERRIDE_PROPERTY")
        return forced
    }
    // runCatching, not try/catch(Exception): the failure this probes for is UnsatisfiedLinkError,
    // an Error, and it arrives wrapped in ExceptionInInitializerError.
    return runCatching {
        Class.forName(WRY_ENTRY_POINT, true, Log::class.java.classLoader)
    }.fold(
        onSuccess = {
            Log.info(LOG_TAG, "Native web view is available; the article reader will use it")
            true
        },
        onFailure = { cause ->
            if (cause is ClassNotFoundException) {
                // Not the missing-binary case: the library is absent or has renamed this class, so
                // the probe can no longer tell the two apart. Falling back is still the safe
                // choice, but it is worth flagging loudly - it would otherwise silently demote
                // every platform, including ones whose web view works.
                Log.warn(
                    LOG_TAG,
                    "Could not find $WRY_ENTRY_POINT; assuming the native web view is unavailable. " +
                        "If the reader works on this platform, the class was likely renamed by a " +
                        "library upgrade - override with -D$OVERRIDE_PROPERTY=true and update the probe",
                    cause,
                )
            } else {
                Log.warn(
                    LOG_TAG,
                    "Native web view is unavailable on this platform/architecture " +
                        "(${System.getProperty("os.name")} ${System.getProperty("os.arch")}); " +
                        "the article reader will fall back to simplified rendering",
                    cause,
                )
            }
            false
        },
    )
}
