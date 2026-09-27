package works.merc.keryx.app.core

import android.util.Log as AndroidLog

/** Android [Log], backed by `android.util.Log` — already persisted (logcat) and rotated by the OS. */
actual object Log {
    actual fun debug(tag: String, message: String) {
        AndroidLog.d(tag, message)
    }

    actual fun info(tag: String, message: String) {
        AndroidLog.i(tag, message)
    }

    actual fun warn(tag: String, message: String, throwable: Throwable?) {
        AndroidLog.w(tag, message, throwable)
    }

    actual fun error(tag: String, message: String, throwable: Throwable?) {
        AndroidLog.e(tag, message, throwable)
    }
}
