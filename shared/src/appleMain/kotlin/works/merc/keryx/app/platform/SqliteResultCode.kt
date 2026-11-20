package works.merc.keryx.app.platform

import co.touchlab.sqliter.interop.SQLiteExceptionErrorCode

/**
 * Walks the cause chain for a SQLite result code: [RawSqliteConnection]'s own [SqliteException], or
 * SQLiter's [SQLiteExceptionErrorCode], which is what `NativeSqliteDriver` throws (the app's own
 * database, and [DatabaseMerger] while it opens and migrates the cloud file). SQLiter keeps the raw
 * code private and exposes only its primary code, through `errorType`; that getter throws for a code
 * it has no enum entry for, which is treated as "no code found". Bounded so a (theoretical) cause
 * cycle cannot loop forever. Shared by [DatabaseMerger]'s failure classification and
 * [localWriteFailureKind].
 */
internal fun Throwable.findSqliteResultCode(): Int? {
    var current: Throwable? = this
    repeat(CAUSE_CHAIN_MAX_DEPTH) {
        val c = current ?: return null
        when (c) {
            is SqliteException -> return c.resultCode
            is SQLiteExceptionErrorCode -> return runCatching { c.errorType.code }.getOrNull()
        }
        current = c.cause
    }
    return null
}

/** Bounds [findSqliteResultCode]'s cause-chain walk against a theoretical cause cycle. */
private const val CAUSE_CHAIN_MAX_DEPTH = 8
