package works.merc.keryx.app.domain

import works.merc.keryx.app.domain.ColumnKind.INTEGER
import works.merc.keryx.app.domain.ColumnKind.TEXT

/**
 * The tables and columns of a pre-delta (full) database, per local schema version — what [MergeSql]
 * needs from one, and what its values are checked against before it is merged ("Validating Cloud
 * Files" in `docs/sync-architecture.md`).
 *
 * Pure data — no DB driver is involved, so every platform's `DatabaseMerger.validateSchema` actual
 * reads the same expectations and only the reflection mechanism (`PRAGMA table_info`) stays
 * platform-specific.
 */
object MergeSchema {

    /**
     * Schema version 3's synced tables: the current transport version's tables plus
     * `articles.search_text`. The device-local bookkeeping tables are not listed: transport files
     * never carry them ([DeltaExportSql] copies the synced tables only). Keep in sync with
     * [MergeSql] and the `.sq` schema files (a test compares this with a freshly created database).
     */
    private val EXPECTED_TABLES_V3: List<TableSpec> = TransportSchema.SYNCED_TABLES.map { table ->
        if (table.name == "articles") table.withColumnAfter("cached_at", req("search_text", TEXT)) else table
    }

    /**
     * Schema version 2: `feeds` still carries its fetch state, `tags` / `folders` have no per-field
     * stamps and no `v2_id` yet (`2.sqm` adds all of them), and `articles` has no
     * `body_updated_at`.
     */
    private val EXPECTED_TABLES_V2: List<TableSpec> = EXPECTED_TABLES_V3.map { table ->
        when (table.name) {
            "feeds" -> table.withColumnAfter("favicon_url", opt("etag", TEXT))
                .withColumnAfter("etag", opt("last_modified", TEXT))
                .withColumnAfter("last_modified", req("error_count", INTEGER))
                .withColumnAfter("error_count", opt("last_error", TEXT))
            "tags" -> table.without("name_updated_at", "color_updated_at", "sort_order_updated_at", "v2_id")
            "folders" -> table.without("name_updated_at", "sort_order_updated_at", "v2_id")
            "articles" -> table.without("body_updated_at")
            else -> table
        }
    }

    /**
     * Registered expected tables (with column kinds and nullability) by
     * [works.merc.keryx.app.data.local.db.KeryxDatabase.Schema.version]. A version missing here is
     * undetermined, never "incompatible".
     */
    val EXPECTED_TABLES: Map<Long, List<TableSpec>> = mapOf(
        2L to EXPECTED_TABLES_V2,
        3L to EXPECTED_TABLES_V3,
    )

    /**
     * Registered expected schemas (table name to column names) by
     * [works.merc.keryx.app.data.local.db.KeryxDatabase.Schema.version], derived from
     * [EXPECTED_TABLES]. A version missing here makes `DatabaseMerger.validateSchema` return `null`
     * (undetermined) rather than `false` — an unregistered version must never be treated as
     * "definitely incompatible", which would offer a destructive cloud-data reset for what is really
     * just a forgotten registration.
     */
    val EXPECTED_SCHEMAS: Map<Long, Map<String, Set<String>>> =
        EXPECTED_TABLES.mapValues { (_, tables) -> tables.associate { it.name to it.columnNames.toSet() } }
}
