package works.merc.keryx.app.core

import kotlinx.datetime.TimeZone
import kotlinx.datetime.number
import kotlinx.datetime.toLocalDateTime
import kotlin.time.ExperimentalTime
import kotlin.time.Instant

/**
 * The cloud path the current sync DB is archived to when the user resets cloud data.
 *
 * Deterministic in [epochMillis] and formatted in UTC (not the device zone), so a fixed [Clock] in
 * a test yields a fixed name, and two devices resetting at the same instant agree. Seconds
 * precision is enough: a reset is a deliberate, rate-limited user action (confirm dialog), and a
 * same-second collision is not fatal — `SyncRepository.archiveCloudDb` falls back to a plain
 * delete when the rename itself fails. Old archives are never pruned automatically — that would
 * need a provider listing API and would defeat the point of keeping a way back to the data.
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
