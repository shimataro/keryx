package works.merc.keryx.app.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The cloud path the pre-delta `keryx.db.gz` is archived to by the migration ("Migration from the
 * Pre-Delta Format" in sync-architecture.md) — a one-time manual way back.
 *
 * Deterministic in [epochMillis] and formatted in UTC (not the device zone), so a fixed [Clock] in
 * a test yields a fixed name. The archive is written with `create`; a name already taken that second
 * (possibly by different bytes) makes the caller try `-1`, `-2`, … before the suffix
 * (`LegacyCloudFile.backup`). Archives are never pruned automatically.
 */
@OptIn(ExperimentalTime::class)
fun cloudBackupPath(epochMillis: Long): String {
    val dt = Instant.fromEpochMilliseconds(epochMillis).toLocalDateTime(TimeZone.UTC)
    val stamp = buildString(15) {
        append(dt.year.toString().padStart(4, '0'))
        append(dt.month.number.toString().padStart(2, '0'))
        append(dt.day.toString().padStart(2, '0'))
        append('-')
        append(dt.hour.toString().padStart(2, '0'))
        append(dt.minute.toString().padStart(2, '0'))
        append(dt.second.toString().padStart(2, '0'))
    }
    return "$CLOUD_DB_BACKUP_PREFIX$stamp$CLOUD_DB_BACKUP_SUFFIX"
}
