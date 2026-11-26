package works.merc.keryx.app.domain

/**
 * How two versions of one synced row fold into one, in an `ON CONFLICT … DO UPDATE` — the rules the
 * fold of transport files ([ReduceSql]) and the identity steps that bring rows onto a canonical id
 * ([FeedIdentitySql], [V2IdentitySql]) share, kept in one place so they cannot drift apart: a fold
 * that differed from the merge would make a base built by folding disagree with merging its inputs
 * one by one.
 *
 * Every field group is resolved by [SyncLww]; the columns no group keys are folded by fixed rules:
 * `created_at` takes the earliest value, an article's `cached_at` and `updated_at` the latest, a tag's
 * or folder's `v2_id` the greatest non-NULL one, and an article's `summary` is dropped once it has
 * `content`.
 */
internal object RowFold {

    /** `created_at` takes the earlier of the two versions. */
    const val EARLIEST_CREATED_AT: String = "created_at = MIN(created_at, excluded.created_at)"

    /**
     * `v2_id` takes the greatest non-NULL of the two (NULL only when both are). The column decides
     * nothing — the identity edges do — so the rule only has to be deterministic.
     */
    const val GREATEST_V2_ID: String =
        "v2_id = CASE WHEN v2_id IS NULL THEN excluded.v2_id WHEN excluded.v2_id IS NULL THEN v2_id " +
            "ELSE MAX(v2_id, excluded.v2_id) END"

    /** The groups of a folder row. */
    val FOLDER_GROUPS: List<LwwGroup> = listOf(SyncLww.FOLDER_EXISTENCE, SyncLww.FOLDER_NAME, SyncLww.FOLDER_SORT_ORDER)

    /** The groups of a tag row. */
    val TAG_GROUPS: List<LwwGroup> =
        listOf(SyncLww.TAG_EXISTENCE, SyncLww.TAG_NAME, SyncLww.TAG_COLOR, SyncLww.TAG_SORT_ORDER)

    /**
     * The groups of a feed row's own columns. The folder assignment ([SyncLww.FEED_FOLDER]) is not
     * among them: in a full database it is folded on the feed's raw row (`ks_feed_folder_raw`), and a
     * transport file's feed row adds it to these.
     */
    val FEED_ROW_GROUPS: List<LwwGroup> = listOf(
        SyncLww.FEED_CONTENT, SyncLww.FEED_SORT_ORDER, SyncLww.FEED_CUSTOM_TITLE, SyncLww.FEED_DELETION,
    )

    /** The groups of an article row. */
    val ARTICLE_GROUPS: List<LwwGroup> = listOf(SyncLww.ARTICLE_BODY, SyncLww.ARTICLE_READ, SyncLww.ARTICLE_STAR, SyncLww.ARTICLE_DELETION)

    /** An article's columns no group keys: `created_at` the earliest, `cached_at` and `updated_at` the latest. */
    val ARTICLE_EXTRA_ASSIGNMENTS: List<String> = listOf(
        EARLIEST_CREATED_AT,
        "cached_at = MAX(cached_at, excluded.cached_at)",
        "updated_at = MAX(updated_at, excluded.updated_at)",
    )

    /** Every assignment that folds one article version into another. */
    val ARTICLE_ASSIGNMENTS: List<String> = ARTICLE_GROUPS.flatMap(SyncLww::upsertAssignments) + ARTICLE_EXTRA_ASSIGNMENTS

    /**
     * An article's `summary` as a version of the row in [alias] is folded in (`""` for the bare
     * column): dropped once the article has `content` ([SyncLww.normalizedSummary]).
     */
    fun articleSummary(alias: String): String = SyncLww.normalizedSummary(alias)
}
