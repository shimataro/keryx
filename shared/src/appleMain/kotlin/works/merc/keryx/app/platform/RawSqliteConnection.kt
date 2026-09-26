package works.merc.keryx.app.platform

import cnames.structs.sqlite3
import cnames.structs.sqlite3_stmt
import co.touchlab.sqliter.sqlite3.SQLITE_OK
import co.touchlab.sqliter.sqlite3.SQLITE_OPEN_CREATE
import co.touchlab.sqliter.sqlite3.SQLITE_OPEN_READWRITE
import co.touchlab.sqliter.sqlite3.SQLITE_ROW
import co.touchlab.sqliter.sqlite3.sqlite3_busy_timeout
import co.touchlab.sqliter.sqlite3.sqlite3_close_v2
import co.touchlab.sqliter.sqlite3.sqlite3_column_count
import co.touchlab.sqliter.sqlite3.sqlite3_column_int64
import co.touchlab.sqliter.sqlite3.sqlite3_column_name
import co.touchlab.sqliter.sqlite3.sqlite3_column_text
import co.touchlab.sqliter.sqlite3.sqlite3_errmsg
import co.touchlab.sqliter.sqlite3.sqlite3_errstr
import co.touchlab.sqliter.sqlite3.sqlite3_exec
import co.touchlab.sqliter.sqlite3.sqlite3_extended_errcode
import co.touchlab.sqliter.sqlite3.sqlite3_finalize
import co.touchlab.sqliter.sqlite3.sqlite3_free
import co.touchlab.sqliter.sqlite3.sqlite3_open_v2
import co.touchlab.sqliter.sqlite3.sqlite3_prepare_v2
import co.touchlab.sqliter.sqlite3.sqlite3_step
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.cstr
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.toKString
import kotlinx.cinterop.value

/** A failure from the system sqlite3, carrying its (extended) result code. */
internal class SqliteException(val resultCode: Int, message: String) : Exception(message) {
    /** The primary result code's symbolic-ish name, e.g. "constraint failed" for SQLITE_CONSTRAINT. */
    val resultCodeName: String get() = sqliteResultCodeName(resultCode)
}

/**
 * sqlite3's own English description of result code [code] (`sqlite3_errstr`), e.g. "constraint failed"
 * for SQLITE_CONSTRAINT — shared by [SqliteException] and any other failure that only carries a code.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun sqliteResultCodeName(code: Int): String = sqlite3_errstr(code)?.toKString() ?: "SQLite error $code"

/**
 * One dedicated connection to a database file through the system sqlite3 C API — what
 * [DatabaseMerger] and [DatabaseSnapshot] need: an `ATTACH`, a transaction and a `VACUUM INTO` that
 * must all run on the same connection, outside the SQLDelight driver's own pool (the Apple
 * counterpart of desktop's raw JDBC connection and Android's requery `SQLiteDatabase`).
 */
@OptIn(ExperimentalForeignApi::class)
internal class RawSqliteConnection private constructor(private val db: CPointer<sqlite3>) : AutoCloseable {

    fun busyTimeout(millis: Long) {
        sqlite3_busy_timeout(db, millis.toInt())
    }

    /** Runs [sql] (one or more statements), discarding any rows. */
    fun exec(sql: String) = memScoped {
        val err = alloc<CPointerVar<ByteVar>>()
        val rc = sqlite3_exec(db, sql, null, null, err.ptr)
        if (rc != SQLITE_OK) {
            val message = err.value?.toKString() ?: sqlite3_errmsg(db)?.toKString().orEmpty()
            sqlite3_free(err.value)
            throw SqliteException(sqlite3_extended_errcode(db), message)
        }
    }

    /** The first column of the first row of [sql] as a Long, or 0 when it returns no row. */
    fun queryLong(sql: String): Long = query(sql) { stmt -> if (sqlite3_step(stmt) == SQLITE_ROW) sqlite3_column_int64(stmt, 0) else 0L }

    /** Every row's [column] (by name) of [sql], as text. */
    fun queryColumn(sql: String, column: String): List<String> = query(sql) { stmt ->
        val index = (0 until sqlite3_column_count(stmt)).firstOrNull { sqlite3_column_name(stmt, it)?.toKString() == column }
            ?: return@query emptyList()
        buildList {
            while (sqlite3_step(stmt) == SQLITE_ROW) {
                sqlite3_column_text(stmt, index)?.let { add(it.reinterpret<ByteVar>().toKString()) }
            }
        }
    }

    private fun <T> query(sql: String, read: (CPointer<sqlite3_stmt>) -> T): T = memScoped {
        val out = alloc<CPointerVar<sqlite3_stmt>>()
        val rc = sqlite3_prepare_v2(db, sql.cstr.ptr, -1, out.ptr, null)
        val stmt = out.value
        if (rc != SQLITE_OK || stmt == null) throw SqliteException(sqlite3_extended_errcode(db), sqlite3_errmsg(db)?.toKString().orEmpty())
        try {
            read(stmt)
        } finally {
            sqlite3_finalize(stmt)
        }
    }

    override fun close() {
        sqlite3_close_v2(db)
    }

    companion object {
        /** Opens [path] read-write, creating it when [create] is set. */
        fun open(path: String, create: Boolean = false): RawSqliteConnection = memScoped {
            val out = alloc<CPointerVar<sqlite3>>()
            val flags = SQLITE_OPEN_READWRITE or (if (create) SQLITE_OPEN_CREATE else 0)
            val rc = sqlite3_open_v2(path, out.ptr, flags, null)
            val handle = out.value
            if (rc != SQLITE_OK || handle == null) {
                val message = handle?.let { sqlite3_errmsg(it)?.toKString() } ?: "cannot open"
                handle?.let { sqlite3_close_v2(it) }
                throw SqliteException(rc, "$message ($path)")
            }
            RawSqliteConnection(handle)
        }

        /** `PRAGMA user_version` of the file at [path], or 0 when there is no such file yet. */
        fun userVersionOf(path: String): Long =
            if (!FileIO.exists(path)) 0L else open(path).use { it.queryLong("PRAGMA user_version") }
    }
}
