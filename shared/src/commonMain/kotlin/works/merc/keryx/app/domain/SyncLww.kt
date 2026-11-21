package works.merc.keryx.app.domain

/**
 * One last-write-wins field group of a synced table: its stamp column(s) and the [values] they stamp.
 * [MergeSql] and [ReduceSql] resolve every group with [SyncLww.wins], so both order a group's
 * versions identically.
 *
 * @property stamps the group's stamp columns. Usually one; a group with several (an article's
 * retention group) is ranked by the greatest of them — `MAX(COALESCE(s1, 0), COALESCE(s2, 0), …)`,
 * present when any of them is — so the newest of its events wins the whole group.
 *
 * @property bodyFirst for an article's body group: ranks a version carrying `content` above every
 * version without, then one carrying any body (`content` or `summary`) above one carrying none —
 * before the stamp is even compared. So a light row (whose body is withheld, see "Local
 * Bookkeeping" in `docs/sync-architecture.md`) never replaces a body, and a summary never replaces
 * content (the content OR-merge). `summary` is ranked as [SyncLww.normalizedSummary], since a
 * summary is dropped once content exists.
 */
data class LwwGroup(val stamps: List<String>, val values: List<String>, val bodyFirst: Boolean = false) {
    constructor(stamp: String, values: List<String>, bodyFirst: Boolean = false) : this(listOf(stamp), values, bodyFirst)

    init {
        require(stamps.isNotEmpty()) { "A last-write-wins group needs a stamp" }
    }

    /** Every column the group writes: the stamps and their values. */
    val columns: List<String> get() = stamps + values
}

/**
 * The last-write-wins order shared by [MergeSql] and [ReduceSql].
 *
 * **The canonical comparison.** A group version's rank is the row value
 *
 * ```
 * ( [has_content, has_body,]  stamp IS NOT NULL, COALESCE(stamp, 0),  c1 IS NOT NULL, COALESCE(c1, 0),  … )
 * ```
 *
 * (the bracketed prefix only for [LwwGroup.bodyFirst]), where `c1, …` are every column of
 * [LwwGroup.columns] — the stamp column(s) themselves, then the values — and, for a group with
 * several stamps, `stamp IS NOT NULL` is "any of them is not NULL" and `COALESCE(stamp, 0)` is
 * `MAX(COALESCE(s1, 0), COALESCE(s2, 0), …)`,
 * compared lexicographically with SQLite's row-value `>` (BINARY collation; NULL-free by
 * construction, so the comparison is always true or false, never NULL). A version replaces another
 * only when its rank is strictly greater. So:
 *
 * - a stamped version beats an unstamped one (NULL = "no such event"), and the greater stamp wins;
 * - **a tie on the stamp is decided by the values**, NULL lowest and otherwise in SQLite's own order
 *   (integers numerically, below text; text byte-wise) — never by which side a version came from.
 *   The result is therefore the same whatever order the files are merged in, which makes the merge
 *   idempotent, commutative and associative per group ("Merge" in `docs/sync-architecture.md`).
 *   Identical versions never replace each other.
 */
object SyncLww {

    /** [expr]'s two rank elements: a non-NULL flag, then the value with NULL as 0. */
    private fun key(expr: String): String = "($expr IS NOT NULL), COALESCE($expr, 0)"

    private fun col(alias: String, column: String): String = if (alias.isEmpty()) column else "$alias.$column"

    /**
     * [group]'s rank for the version in [alias] (`""` for the unqualified existing row of an
     * `ON CONFLICT … DO UPDATE`).
     */
    fun rank(alias: String, group: LwwGroup): String {
        val elements = buildList {
            if (group.bodyFirst) {
                add("(${col(alias, "content")} IS NOT NULL)")
                add("(${col(alias, "content")} IS NOT NULL OR ${col(alias, "summary")} IS NOT NULL)")
            }
            if (group.stamps.size > 1) {
                add(group.stamps.joinToString(" OR ", prefix = "(", postfix = ")") { "${col(alias, it)} IS NOT NULL" })
                add(group.stamps.joinToString(", ", prefix = "MAX(", postfix = ")") { "COALESCE(${col(alias, it)}, 0)" })
            }
            group.columns.forEach { column ->
                add(key(if (group.bodyFirst && column == "summary") normalizedSummary(alias) else col(alias, column)))
            }
        }
        return elements.joinToString(", ", prefix = "(", postfix = ")")
    }

    /**
     * An article's `summary` as it is kept: NULL once the article has `content` (a summary is dead
     * weight next to content, see `ArticleRepository.prepareParsed`), so a stale summary riding along
     * with content is never stored, ranked or resurrected.
     */
    fun normalizedSummary(alias: String): String =
        "CASE WHEN ${col(alias, "content")} IS NOT NULL THEN NULL ELSE ${col(alias, "summary")} END"

    /** Whether the version in [challenger] replaces the one in [holder] for [group]. */
    fun wins(challenger: String, holder: String, group: LwwGroup): String =
        "${rank(challenger, group)} > ${rank(holder, group)}"

    /**
     * `SET` assignments for an `ON CONFLICT … DO UPDATE`: every column of [group] takes `excluded`'s
     * value when `excluded` wins, else keeps its own. SQLite evaluates every assignment against the
     * row as it was, so the group's columns move together.
     */
    fun upsertAssignments(group: LwwGroup): List<String> {
        val condition = wins("excluded", "", group)
        return group.columns.map { "$it = CASE WHEN $condition THEN excluded.$it ELSE $it END" }
    }

    // ---- The field groups, shared by MergeSql and ReduceSql. ----

    val FOLDER_EXISTENCE = LwwGroup("updated_at", listOf("deleted_at"))
    val FOLDER_NAME = LwwGroup("name_updated_at", listOf("name"))
    val FOLDER_SORT_ORDER = LwwGroup("sort_order_updated_at", listOf("sort_order"))

    val TAG_EXISTENCE = LwwGroup("updated_at", listOf("deleted_at"))
    val TAG_NAME = LwwGroup("name_updated_at", listOf("name"))
    val TAG_COLOR = LwwGroup("color_updated_at", listOf("color"))
    val TAG_SORT_ORDER = LwwGroup("sort_order_updated_at", listOf("sort_order"))

    val FEED_CONTENT = LwwGroup("updated_at", listOf("url", "site_url", "title", "description", "favicon_url"))
    val FEED_FOLDER = LwwGroup("folder_updated_at", listOf("folder_id"))
    val FEED_SORT_ORDER = LwwGroup("sort_order_updated_at", listOf("sort_order"))
    val FEED_CUSTOM_TITLE = LwwGroup("custom_title_updated_at", listOf("custom_title"))
    val FEED_DELETION = LwwGroup("deleted_updated_at", listOf("deleted_at"))

    /**
     * An article's fetched content: body first ([LwwGroup.bodyFirst]), then `body_updated_at` — a stamp
     * only a change of the body columns moves, so reading, starring or deleting an article never
     * makes its older body beat a newer one. `feed_id` and `guid` ride with it so a transport fold
     * picks them deterministically (the local merge never rewrites them on an existing row).
     */
    val ARTICLE_BODY = LwwGroup(
        "body_updated_at",
        listOf("content", "summary", "title", "url", "author", "published_at", "thumbnail_url", "feed_id", "guid"),
        bodyFirst = true,
    )
    val ARTICLE_READ = LwwGroup("read_at", listOf("is_read"))

    /**
     * An article's retention: its star and its deletion as **one** group, ranked by the newer of
     * `starred_at` and `deleted_updated_at` — the newer operation wins, and the whole group (star
     * flag, deletion and both stamps) comes from the version holding it. Two separate groups with a
     * "a newer star revives" rule on top would not be commutative (delete, star, unstar merged in
     * different orders would disagree). A local star or deletion stamps above both stamps of its own
     * row (`updateStarStatus`, `softDeleteExpired`), so it wins the group it changed.
     */
    val ARTICLE_RETENTION = LwwGroup(
        listOf("starred_at", "deleted_updated_at"),
        listOf("is_starred", "deleted_at"),
    )

    val FEED_TAG = LwwGroup("updated_at", listOf("deleted_at"))
    val GLOBAL_SETTING = LwwGroup("updated_at", listOf("value"))
}
