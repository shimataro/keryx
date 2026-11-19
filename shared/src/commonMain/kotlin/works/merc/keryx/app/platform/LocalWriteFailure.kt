package works.merc.keryx.app.platform

import works.merc.keryx.app.core.LocalWriteFailureKind

/**
 * Classifies a failed local DB write by the SQLite result code found in this throwable's cause
 * chain — `SQLITE_BUSY`/`SQLITE_LOCKED` → [LocalWriteFailureKind.BUSY], `SQLITE_FULL` →
 * [LocalWriteFailureKind.DISK_FULL], everything else (including a failure with no SQLite result
 * code behind it) → [LocalWriteFailureKind.OTHER].
 *
 * `expect` because each platform's driver reports the code its own way: desktop's JDBC driver as
 * `org.sqlite.SQLiteException.resultCode`, Android's requery driver as the exception's class, and
 * the Apple drivers as a raw result code.
 */
expect fun Throwable.localWriteFailureKind(): LocalWriteFailureKind
