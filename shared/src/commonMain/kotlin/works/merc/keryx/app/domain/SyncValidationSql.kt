package works.merc.keryx.app.domain

/**
 * The SQL and the pure checks behind "Validating Cloud Files" checks 5, 7, 8 and 9
 * (`docs/sync-architecture.md`). Pure — executed by
 * [works.merc.keryx.app.platform.TransportFiles.validate] on a read-only connection.
 */
object SyncValidationSql {

    private fun quote(identifier: String): String = "\"${identifier.replace("\"", "\"\"")}\""

    /**
     * The condition that is true for a value of [column] it must not hold: ids are non-empty text,
     * text is text, stamps and integers are integers, booleans are 0 or 1, counts are non-negative
     * integers, and a NOT NULL column holds no NULL (a nullable one may).
     */
    fun violation(column: ColumnSpec): String {
        val c = quote(column.name)
        val notNullViolation = when (column.kind) {
            ColumnKind.ID -> "typeof($c) <> 'text' OR $c = ''"
            ColumnKind.TEXT -> "typeof($c) <> 'text'"
            ColumnKind.INTEGER, ColumnKind.STAMP -> "typeof($c) <> 'integer'"
            ColumnKind.BOOLEAN -> "typeof($c) <> 'integer' OR $c NOT IN (0, 1)"
            ColumnKind.COUNT -> "typeof($c) <> 'integer' OR $c < 0"
        }
        return if (column.notNull) "($notNullViolation)" else "($c IS NOT NULL AND ($notNullViolation))"
    }

    /**
     * A query returning the name of the first column of [table] holding a value it must not
     * ([violation]), or no row when every value is acceptable. One scan of the table.
     */
    fun typeViolationQuery(table: TableSpec): String {
        val cases = table.columns.joinToString(" ") { "WHEN ${violation(it)} THEN '${it.name}'" }
        val any = table.columns.joinToString(" OR ") { violation(it) }
        return "SELECT CASE $cases END FROM ${quote(table.name)} WHERE $any LIMIT 1"
    }

    /** `PRAGMA table_info` of [table]; its second column (index 1) is the column name. */
    fun tableInfoQuery(table: String): String = "PRAGMA table_info(${quote(table)})"

    /**
     * The first problem with [actualColumns] (table name to its columns, an empty set for a table the
     * file lacks) against [expected], or `null` when every expected table and column is present.
     * Extra tables and columns are ignored.
     */
    fun missingColumnsProblem(expected: List<TableSpec>, actualColumns: Map<String, Set<String>>): String? {
        for (table in expected) {
            val actual = actualColumns[table.name].orEmpty().map { it.lowercase() }.toSet()
            if (actual.isEmpty()) return "missing table ${table.name}"
            val missing = table.columnNames.firstOrNull { it.lowercase() !in actual }
            if (missing != null) return "missing column ${table.name}.$missing"
        }
        return null
    }

    /** One reference-closure check (check 9): [query] returns a row when a reference is dangling. */
    data class ClosureCheck(val description: String, val query: String)

    /**
     * Check 9: every feed's folder, every article's feed and every `feed_tags` row's feed and tag
     * is in the same file. A delta carries the rows its rows reference, so the merge never finds one
     * missing; a gap means the writer was broken.
     */
    val closureChecks: List<ClosureCheck> = listOf(
        ClosureCheck(
            "feeds.folder_id",
            "SELECT 1 FROM feeds f WHERE f.folder_id IS NOT NULL " +
                "AND NOT EXISTS (SELECT 1 FROM folders d WHERE d.id = f.folder_id) LIMIT 1",
        ),
        ClosureCheck(
            "articles.feed_id",
            "SELECT 1 FROM articles a WHERE NOT EXISTS (SELECT 1 FROM feeds f WHERE f.id = a.feed_id) LIMIT 1",
        ),
        ClosureCheck(
            "feed_tags.feed_id",
            "SELECT 1 FROM feed_tags ft WHERE NOT EXISTS (SELECT 1 FROM feeds f WHERE f.id = ft.feed_id) LIMIT 1",
        ),
        ClosureCheck(
            "feed_tags.tag_id",
            "SELECT 1 FROM feed_tags ft WHERE NOT EXISTS (SELECT 1 FROM tags t WHERE t.id = ft.tag_id) LIMIT 1",
        ),
    )

    private val DECIMAL = Regex("^(0|[1-9][0-9]{0,17})$")

    /**
     * Check 8, `ks_meta` against the file name: every key is present, generation / device / kind /
     * number match [name], and format / created_at are decimal (format at least 1). Returns the first
     * problem, or `null`.
     */
    fun metaProblem(meta: Map<String, String>, name: TransportFileName): String? {
        val keys = TransportSchema.MetaKeys
        keys.ALL.firstOrNull { it !in meta }?.let { return "ks_meta lacks $it" }
        val format = meta.getValue(keys.FORMAT)
        if (!DECIMAL.matches(format) || format == "0") return "ks_meta.format is not a version"
        if (!DECIMAL.matches(meta.getValue(keys.CREATED_AT))) return "ks_meta.created_at is not a time"
        if (meta[keys.GENERATION] != name.generation) return "ks_meta.generation does not match the file name"
        if (meta[keys.DEVICE] != name.device) return "ks_meta.device does not match the file name"
        if (meta[keys.KIND] != name.kind.metaValue) return "ks_meta.kind does not match the file name"
        if (meta[keys.NUMBER] != name.number.toString()) return "ks_meta.number does not match the file name"
        return null
    }

    /**
     * Check 8, a file's coverage: a delta's `ks_vector` / `ks_covered` are empty; a base's vector
     * names well-formed devices and its covered names are deltas of its own generation. Values are
     * already type-checked (check 7). Returns the first problem, or `null`.
     */
    fun coverageProblem(name: TransportFileName, vectorDevices: List<String>, coveredNames: List<String>): String? {
        if (name.kind == TransportFileKind.DELTA) {
            if (vectorDevices.isNotEmpty()) return "a delta carries ks_vector rows"
            if (coveredNames.isNotEmpty()) return "a delta carries ks_covered rows"
            return null
        }
        if (vectorDevices.any { !TransportFileName.isSyncId(it) }) return "ks_vector names a malformed device"
        for (covered in coveredNames) {
            val parsed = TransportFileName.parse(covered) ?: return "ks_covered holds a malformed name"
            if (parsed.kind != TransportFileKind.DELTA) return "ks_covered names a base"
            if (parsed.generation != name.generation) return "ks_covered names another generation"
        }
        return null
    }
}
