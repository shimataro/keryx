package works.merc.keryx.app.platform

import org.sqlite.SQLiteException

/**
 * Walks the cause chain looking for the [SQLiteException] that explains [this], since SQLDelight's
 * `JdbcSqliteDriver` (or another wrapper) may wrap it before it reaches the caller's catch. Bounded
 * so a (theoretical) cause cycle cannot loop forever. Shared by [DatabaseMerger]'s failure
 * classification and [localWriteFailureKind].
 */
internal fun Throwable.findSqliteCause(): SQLiteException? {
    var current: Throwable? = this
    repeat(CAUSE_CHAIN_MAX_DEPTH) {
        val c = current ?: return null
        if (c is SQLiteException) return c
        current = c.cause
    }
    return null
}

/** Bounds [findSqliteCause]'s cause-chain walk against a theoretical cause cycle. */
private const val CAUSE_CHAIN_MAX_DEPTH = 8
