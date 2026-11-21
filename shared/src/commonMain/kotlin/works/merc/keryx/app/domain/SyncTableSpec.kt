package works.merc.keryx.app.domain

/**
 * What a synced column may hold, as far as validating a cloud file is concerned ("Validating Cloud
 * Files" check 7 in `docs/sync-architecture.md`). SQLite does not enforce declared types, so a cloud
 * file is checked value by value against these kinds ([SyncValidationSql.typeViolationQuery]).
 *
 * @property declaredType the SQLite type the column is declared with (`TEXT` / `INTEGER`).
 */
enum class ColumnKind(val declaredType: String) {
    /** Non-empty text: a primary key, or a reference to one. */
    ID("TEXT"),

    /** Any text. */
    TEXT("TEXT"),

    /** Any integer (a sort order, a publication time). */
    INTEGER("INTEGER"),

    /** A last-write-wins stamp or another Unix-millis time: an integer. */
    STAMP("INTEGER"),

    /** 0 or 1. */
    BOOLEAN("INTEGER"),

    /** A non-negative integer (a sequence number). */
    COUNT("INTEGER"),
}

/** One column of a synced (or transport metadata) table. */
data class ColumnSpec(val name: String, val kind: ColumnKind, val notNull: Boolean)

/**
 * One table of a cloud file's expected schema: its columns (with kinds and nullability) and its
 * primary key. Used to validate cloud files and, for the transport schema, to generate its DDL.
 */
data class TableSpec(val name: String, val columns: List<ColumnSpec>, val primaryKey: List<String>) {
    init {
        require(primaryKey.isNotEmpty() && primaryKey.all { pk -> columns.any { it.name == pk } }) {
            "Primary key of $name must name its own columns"
        }
    }

    /** Column names, in declaration order. */
    val columnNames: List<String> get() = columns.map { it.name }

    /** A copy without the columns named in [names]. */
    fun without(vararg names: String): TableSpec = copy(columns = columns.filterNot { it.name in names })

    /** A copy with [column] inserted right after the column named [after]. */
    fun withColumnAfter(after: String, column: ColumnSpec): TableSpec {
        val index = columns.indexOfFirst { it.name == after }
        require(index >= 0) { "No column $after in $name" }
        return copy(columns = columns.toMutableList().apply { add(index + 1, column) })
    }

    /** A copy with [extra] appended. */
    fun plus(vararg extra: ColumnSpec): TableSpec = copy(columns = columns + extra)
}

/** A NOT NULL column. */
internal fun req(name: String, kind: ColumnKind) = ColumnSpec(name, kind, notNull = true)

/** A nullable column. */
internal fun opt(name: String, kind: ColumnKind) = ColumnSpec(name, kind, notNull = false)
