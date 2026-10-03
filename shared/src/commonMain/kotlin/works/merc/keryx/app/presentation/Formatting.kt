package works.merc.keryx.app.presentation

import kotlin.time.ExperimentalTime
import kotlin.time.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime

/**
 * Formats an epoch-millisecond timestamp as `yyyy-MM-dd HH:mm` in the system default time zone.
 *
 * @param epochMillis The timestamp to format, or `null`.
 * @return The formatted timestamp, or an empty string when `epochMillis` is `null`.
 */
fun formatTimestamp(epochMillis: Long?): String =
    formatTimestamp(epochMillis, TimeZone.currentSystemDefault())

/**
 * Formats an epoch-millis timestamp as `yyyy-MM-dd HH:mm` in [zone].
 *
 * Callers that format many timestamps in a row (the article list) resolve the zone once and pass it
 * here: `TimeZone.currentSystemDefault()` clones the JVM default zone on every call, which is the
 * bulk of the cost when this runs per visible row.
 */
@OptIn(ExperimentalTime::class)
fun formatTimestamp(epochMillis: Long?, zone: TimeZone): String {
    if (epochMillis == null) return ""
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(zone)
    // Hand-rolled padding rather than padStart: same output, without a StringBuilder and an
    // intermediate String per field.
    return buildString(16) {
        appendFourDigits(dt.year)
        append('-')
        appendTwoDigits(dt.month.number)
        append('-')
        appendTwoDigits(dt.day)
        append(' ')
        appendTwoDigits(dt.hour)
        append(':')
        appendTwoDigits(dt.minute)
    }
}

/**
 * The reader's byline: `"feed · author · formatted timestamp"`, dropping any part that is absent —
 * shared so every UI (Compose's `ArticleDetailPane.kt`, the Apple app's `ArticleDetailView.swift`)
 * renders the same meta line rather than re-deriving it.
 *
 * @param author The article's author, if available.
 * @param publishedAt The article's publication time in Unix milliseconds, if available.
 * @param feedName The owning feed's display title, for a UI whose toolbar has no room to show it
 *   (the iOS reader); null where the toolbar already does.
 * @return The formatted metadata line, or an empty string when no value is available.
 */
fun articleMetaText(author: String?, publishedAt: Long?, feedName: String? = null): String =
    listOfNotNull(
        feedName?.takeIf { it.isNotBlank() },
        author?.takeIf { it.isNotBlank() },
        formatTimestamp(publishedAt).ifBlank { null },
    ).joinToString(" · ")

private fun StringBuilder.appendTwoDigits(value: Int) = appendNumber(value, 2)

/**
 * Appends [value] zero-padded to at least four digits, so the year keeps the documented `yyyy`
 * width. A negative value is appended as-is: the format has no representation for one anyway.
 */
private fun StringBuilder.appendFourDigits(value: Int) = appendNumber(value, 4)

/** Appends [value] zero-padded to at least [minDigits] digits. Negative values are appended as-is. */
private fun StringBuilder.appendNumber(value: Int, minDigits: Int) {
    if (value < 0) {
        append(value)
        return
    }
    val str = value.toString()
    repeat(minDigits - str.length) { append('0') }
    append(str)
}
