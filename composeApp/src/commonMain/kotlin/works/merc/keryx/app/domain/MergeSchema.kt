package works.merc.keryx.app.domain

/**
 * The tables and columns [MergeSql] needs, per schema version.
 *
 * Pure data — no DB driver is involved, so every platform's
 * `DatabaseMerger.validateSchema` actual reads the same expectations and only the reflection
 * mechanism (`PRAGMA table_info`) stays platform-specific.
 */
object MergeSchema {

    /**
     * Expected tables and columns for schema version 2.
     * Keep in sync with [MergeSql] and the `.sq` schema files.
     */
    private val EXPECTED_SCHEMA_V2 = mapOf(
        "folders" to setOf(
            "id", "name", "sort_order", "deleted_at", "updated_at", "created_at",
        ),
        "feeds" to setOf(
            "id", "url", "site_url", "title", "description", "favicon_url", "etag",
            "last_modified", "error_count", "last_error", "custom_title", "folder_id",
            "deleted_at", "updated_at", "created_at", "sort_order",
            "folder_updated_at", "sort_order_updated_at", "custom_title_updated_at", "deleted_updated_at",
        ),
        "tags" to setOf(
            "id", "name", "color", "sort_order", "deleted_at", "updated_at", "created_at",
        ),
        "articles" to setOf(
            "id", "feed_id", "guid", "url", "title", "summary", "content", "author",
            "published_at", "thumbnail_url", "is_read", "read_at", "is_starred",
            "starred_at", "cached_at", "search_text", "updated_at", "created_at",
            "deleted_at", "deleted_updated_at",
        ),
        "feed_tags" to setOf(
            "feed_id", "tag_id", "deleted_at", "updated_at",
        ),
        "global_settings" to setOf(
            "key", "value", "updated_at",
        ),
    )

    /**
     * Registered expected schemas by [works.merc.keryx.app.data.local.db.KeryxDatabase.Schema.version].
     * A version missing here makes `DatabaseMerger.validateSchema` return `null` (undetermined)
     * rather than `false` — an unregistered version must never be treated as "definitely
     * incompatible", which would offer a destructive cloud-data reset for what is really just a
     * forgotten registration.
     */
    val EXPECTED_SCHEMAS: Map<Long, Map<String, Set<String>>> = mapOf(2L to EXPECTED_SCHEMA_V2)
}
