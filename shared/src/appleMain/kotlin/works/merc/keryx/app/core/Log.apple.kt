package works.merc.keryx.app.core

import platform.Foundation.NSLog

/** Apple [Log]: unified logging through `NSLog`, visible in Console.app and Xcode's console. */
actual object Log {
    actual fun debug(tag: String, message: String) = emit("DEBUG", tag, message, null)
    actual fun info(tag: String, message: String) = emit("INFO", tag, message, null)
    actual fun warn(tag: String, message: String, throwable: Throwable?) = emit("WARN", tag, message, throwable)
    actual fun error(tag: String, message: String, throwable: Throwable?) = emit("ERROR", tag, message, throwable)

    private fun emit(level: String, tag: String, message: String, throwable: Throwable?) {
        val text = if (throwable == null) "[$level] [$tag] $message" else "[$level] [$tag] $message\n${throwable.stackTraceToString()}"
        // NSLog's variadic arguments do not bridge a Kotlin String to NSString (passing one as `%@`
        // crashes), so the text itself is the format string — with every `%` escaped, since a
        // message may contain one.
        NSLog(text.replace("%", "%%"))
    }
}
