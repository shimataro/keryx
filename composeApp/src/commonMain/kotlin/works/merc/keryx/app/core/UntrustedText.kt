package works.merc.keryx.app.core

/**
 * Makes a string that came from outside the app safe to put in a log line or an exception message.
 *
 * Two properties, both load-bearing for the update pipeline's diagnostics. **Control characters are
 * dropped**, so a value carrying `\n` cannot forge additional log lines — `Log`'s desktop actual
 * writes `<instant> <level> <message>` with no escaping, so an injected newline is indistinguishable
 * from a genuine entry. And the result is **bounded**, so a value cannot consume a meaningful share
 * of the rotating log file. A ZIP entry name may be 65535 bytes and an `Info.plist` `<string>` is
 * bounded only by the extraction limit, so neither is safe to log as-is.
 *
 * Filters *before* truncating, and lazily, so neither the intermediate nor the peak allocation is
 * proportional to the input: a 64 KB name costs [maxLength] characters, not three copies of itself.
 *
 * This is not escaping for a rich sink — it does not touch `U+2028`/`U+2029` or bidi overrides,
 * which [Char.isISOControl] excludes. It is exactly enough for a plain-text, line-oriented log.
 */
fun untrustedText(text: String, maxLength: Int): String =
    text.asSequence()
        .filterNot { it.isISOControl() }
        .take(maxLength)
        .joinToString("")
