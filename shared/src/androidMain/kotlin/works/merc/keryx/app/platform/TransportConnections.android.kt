package works.merc.keryx.app.platform

import io.requery.android.database.sqlite.SQLiteDatabase
import works.merc.keryx.app.domain.SqliteFailureCategory

/** Android: a dedicated requery [SQLiteDatabase], like [DatabaseMerger]'s. */
actual object TransportConnections {
    actual fun open(path: String, mode: TransportOpenMode): TransportConnection {
        val flags = when (mode) {
            TransportOpenMode.READ_ONLY -> SQLiteDatabase.OPEN_READONLY
            TransportOpenMode.READ_WRITE -> SQLiteDatabase.OPEN_READWRITE
            TransportOpenMode.CREATE -> SQLiteDatabase.OPEN_READWRITE or SQLiteDatabase.CREATE_IF_NECESSARY
        }
        // NoOpDatabaseErrorHandler: the default handler deletes a file it finds corrupt.
        return RequeryTransportConnection(SQLiteDatabase.openDatabase(path, null, flags, NoOpDatabaseErrorHandler))
    }

    actual fun failureCategory(failure: Throwable): SqliteFailureCategory? {
        var current: Throwable? = failure
        repeat(CAUSE_CHAIN_MAX_DEPTH) {
            val c = current ?: return null
            with(DatabaseMerger) { c.failureCategory() }?.let { return it }
            current = c.cause
        }
        return null
    }

    private const val CAUSE_CHAIN_MAX_DEPTH = 8
}

private class RequeryTransportConnection(private val db: SQLiteDatabase) : TransportConnection {
    override fun exec(sql: String) {
        db.execSQL(sql)
    }

    override fun queryRows(sql: String): List<List<String?>> =
        db.rawQuery(sql, null).use { cursor ->
            buildList {
                while (cursor.moveToNext()) {
                    add(List(cursor.columnCount) { if (cursor.isNull(it)) null else cursor.getString(it) })
                }
            }
        }

    override fun <T> transaction(block: () -> T): T {
        db.beginTransaction()
        try {
            val result = block()
            db.setTransactionSuccessful()
            return result
        } finally {
            // Rolls back when setTransactionSuccessful() was not reached.
            db.endTransaction()
        }
    }

    override fun close() {
        db.close()
    }
}
