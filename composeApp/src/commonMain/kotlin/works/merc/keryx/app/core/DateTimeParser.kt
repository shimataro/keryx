package works.merc.keryx.app.core

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.toInstant
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Parses feed timestamps to Unix milliseconds. Handles the two formats found in
 * the wild:
 * - ISO-8601 / RFC-3339 (Atom `updated`/`published`, some RSS): `2003-12-13T18:30:02Z`
 * - RFC-822 / RFC-1123 (RSS `pubDate`): `Wed, 02 Oct 2002 08:00:00 GMT`
 */
@OptIn(ExperimentalTime::class)
object DateTimeParser {
    fun parseToEpochMillis(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null

        // ISO-8601 / RFC-3339 with offset (Atom, some RSS).
        runCatching { return Instant.parse(s).toEpochMilliseconds() }

        // RFC-1123 / RFC-822 (RSS pubDate).
        runCatching {
            return DateTimeComponents.Formats.RFC_1123.parse(s)
                .toInstantUsingOffset()
                .toEpochMilliseconds()
        }

        // Bare local date-time with no zone → assume UTC.
        runCatching {
            return LocalDateTime.parse(s).toInstant(TimeZone.UTC).toEpochMilliseconds()
        }

        return null
    }
}
