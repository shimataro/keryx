package works.merc.keryx.app.core

import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.atStartOfDayIn
import kotlinx.datetime.format.DateTimeComponents
import kotlinx.datetime.format.alternativeParsing
import kotlinx.datetime.toInstant
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * Parses feed timestamps to Unix milliseconds. Handles the formats found in the wild:
 * - ISO-8601 / RFC-3339 (Atom `updated`/`published`, some RSS): `2003-12-13T18:30:02Z`
 * - W3CDTF with the seconds omitted (Atom 0.3 `issued`/`modified`): `2020-01-01T00:00Z`
 * - An offset written without the colon (ISO-8601 basic): `2020-01-01T00:00:00+0900`
 * - RFC-822 / RFC-1123 (RSS `pubDate`): `Wed, 02 Oct 2002 08:00:00 GMT`, `... 08:00 EST`
 * - A local date-time with no zone, taken as UTC: `2020-01-01T00:00:00`
 * - A bare date, taken as midnight UTC (RSS 1.0 `dc:date`): `2020-01-01`
 *
 * Deliberately not supported: the `YYYY-MM` / `YYYY` W3CDTF forms (a bare number is
 * indistinguishable from junk, and rounding to January 1st would be a guess), RFC-822
 * military zones (RFC 2822 section 4.3 records that they were widely used with the wrong
 * sign and should be read as `-0000`), RFC-2822 folding whitespace and comments, and
 * two-digit years.
 */
@OptIn(ExperimentalTime::class)
object DateTimeParser {

    /**
     * ISO-8601 / W3CDTF date-time with an offset, accepting the two shapes
     * [Instant.parse] rejects: an omitted seconds field (`2020-01-01T00:00Z`) and an
     * offset written without the colon (`+0900`).
     */
    private val isoDateTimeWithOffset = DateTimeComponents.Format {
        // LocalDateTime's ISO format already makes the seconds and their fraction optional.
        dateTime(LocalDateTime.Formats.ISO)
        alternativeParsing({ offsetHours() }, { offset(UtcOffset.Formats.ISO_BASIC) }) {
            offset(UtcOffset.Formats.ISO)
        }
    }

    /** RFC-822 section 5.1 North American zone abbreviations, which `RFC_1123` does not accept. */
    private val NORTH_AMERICAN_ZONES = mapOf(
        "EST" to "-0500", "EDT" to "-0400",
        "CST" to "-0600", "CDT" to "-0500",
        "MST" to "-0700", "MDT" to "-0600",
        "PST" to "-0800", "PDT" to "-0700",
    )

    fun parseToEpochMillis(raw: String?): Long? {
        val s = raw?.trim().orEmpty()
        if (s.isEmpty()) return null

        // ISO-8601 / RFC-3339 with offset (Atom, some RSS).
        runCatching { return Instant.parse(s).toEpochMilliseconds() }

        // The same, with the seconds omitted and/or the offset colon dropped.
        runCatching {
            return isoDateTimeWithOffset.parse(s)
                .toInstantUsingOffset()
                .toEpochMilliseconds()
        }

        // RFC-1123 / RFC-822 (RSS pubDate).
        runCatching {
            return DateTimeComponents.Formats.RFC_1123.parse(withNumericZone(s))
                .toInstantUsingOffset()
                .toEpochMilliseconds()
        }

        // Bare local date-time with no zone → assume UTC.
        runCatching {
            return LocalDateTime.parse(s).toInstant(TimeZone.UTC).toEpochMilliseconds()
        }

        // Bare date with no time → assume midnight UTC, to match the case above.
        runCatching {
            return LocalDate.parse(s).atStartOfDayIn(TimeZone.UTC).toEpochMilliseconds()
        }

        return null
    }

    /**
     * Rewrites a trailing RFC-822 zone abbreviation to its numeric offset. Anything else —
     * including the `GMT` / `UT` / `Z` that `RFC_1123` handles itself — is returned unchanged.
     */
    private fun withNumericZone(s: String): String {
        val zone = s.substringAfterLast(' ', "")
        val offset = NORTH_AMERICAN_ZONES[zone.uppercase()] ?: return s
        return s.dropLast(zone.length) + offset
    }
}
