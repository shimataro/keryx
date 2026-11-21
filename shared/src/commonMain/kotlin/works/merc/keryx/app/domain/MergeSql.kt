package works.merc.keryx.app.domain

/**
 * The ATTACH-DATABASE merge statements. The cloud file — a folded transport file, or a validated
 * pre-delta database migrated to the current local schema — is attached as `cloud`; these merge it
 * into `main` per the conflict-resolution policy ("Merge" in `docs/sync-architecture.md`):
 *
 * - every field group is last-write-wins by its own stamp ([SyncLww]); feeds: content (`updated_at`),
 *   folder, sort order, custom title, subscription state; tags / folders: existence (`updated_at`),
 *   name, sort order (and color for tags); articles: body (`updated_at`, a body always beating a light
 *   row), read, star, deletion; feed_tags and global_settings: their one group;
 * - **a tie on the stamp is decided by the values, never by the side** ([SyncLww]'s canonical
 *   comparison), so devices merging the same files in different orders converge;
 * - `created_at` takes the earliest value, articles' `cached_at` the latest; a star newer than the
 *   winning deletion revives an article;
 * - `search_text` is never merged: an article whose body the merge changes is registered in
 *   `main.ks_search_pending`, and a new article starts with `''` (see "FTS5 Handling").
 *
 * Statements reference only columns present both in the transport schema ([TransportSchema]) and in
 * a full database of the current schema, so the same list merges deltas, bases and pre-delta files.
 * Columns are listed explicitly (avoiding `SELECT *`, which breaks on any schema drift).
 *
 * Guards keep a merge from ever aborting on main's own constraints: NOT-EXISTS guards skip rows that
 * would collide with main (same URL / guid / name under a different id) or reference a row main lacks,
 * and **duplicates inside `cloud`** (a folded file can hold two rows with the same unique key from
 * different devices) are resolved first: a statement that matches by `feeds.url`,
 * `articles(feed_id, guid)` or a tag/folder name considers only the winner among the cloud rows
 * sharing that key — greatest `updated_at`, then greatest id ([winnerAmong]). Statements resolving a
 * reference by id (`mergeFeedFolderId`, `feedTags`) still see every cloud row.
 */
object MergeSql {

    /** The rank column [winnerAmong] adds; 1 marks the winner of its key. */
    private const val RANK = "ks_rank"

    /**
     * `cloud.<table>`'s [columns] plus [RANK], 1 for the winner among the rows sharing [key]
     * (greatest `updated_at`, then greatest id).
     */
    private fun winnerAmong(table: String, columns: List<String>, key: String): String =
        "(SELECT ${columns.joinToString(", ")}, " +
            "ROW_NUMBER() OVER (PARTITION BY $key ORDER BY updated_at DESC, id DESC) AS $RANK " +
            "FROM cloud.$table)"

    private val FOLDER_COLUMNS = listOf(
        "id", "name", "sort_order", "deleted_at", "updated_at", "created_at", "name_updated_at", "sort_order_updated_at",
    )
    private val TAG_COLUMNS = listOf(
        "id", "name", "color", "sort_order", "deleted_at", "updated_at", "created_at",
        "name_updated_at", "color_updated_at", "sort_order_updated_at",
    )
    private val FEED_COLUMNS = listOf(
        "id", "url", "site_url", "title", "description", "favicon_url", "custom_title", "folder_id",
        "deleted_at", "updated_at", "created_at", "sort_order",
        "folder_updated_at", "sort_order_updated_at", "custom_title_updated_at", "deleted_updated_at",
    )
    private val ARTICLE_COLUMNS = listOf(
        "id", "feed_id", "guid", "url", "title", "summary", "content", "author", "published_at",
        "thumbnail_url", "is_read", "read_at", "is_starred", "starred_at", "cached_at",
        "updated_at", "created_at", "deleted_at", "deleted_updated_at",
    )

    // ---- tags / folders: per-field last-write-wins, matched by id, else by name ----

    /**
     * The per-field statements of a tags/folders-shaped [table], in order: one UPDATE per group, then
     * the INSERT of rows main does not have.
     *
     * A main row is matched to the cloud row with its id; failing that, to the winning cloud row of
     * its name whose id main does not have (two devices that created the same name independently
     * converge on this device's row). The two ways never match the same pair twice, and each name is
     * matched by at most one cloud row, so no main row is updated from two cloud rows.
     *
     * A name change is applied only from the winner of its name and only while no other main row holds
     * that name, so it can never violate `UNIQUE(name)`; the insert likewise skips a name main already
     * holds (and runs last, after renames may have freed one).
     */
    private fun perFieldStatements(table: String, columns: List<String>, nameGroup: LwwGroup, otherGroups: List<LwwGroup>): List<String> {
        val cloud = winnerAmong(table, columns, "name")
        val target = "main.$table"
        val matched = """
            (c.id = $target.id
             OR (c.$RANK = 1 AND c.name = $target.name
                 AND NOT EXISTS (SELECT 1 FROM main.$table m WHERE m.id = c.id)
                 AND NOT EXISTS (SELECT 1 FROM cloud.$table c3 WHERE c3.id = $target.id)))
        """.trimIndent()

        fun update(group: LwwGroup, extraCondition: String = ""): String = """
            UPDATE $target
            SET ${group.columns.joinToString(", ") { "$it = c.$it" }}
            FROM $cloud c
            WHERE $matched
              AND ${SyncLww.wins("c", target, group)}$extraCondition;
        """.trimIndent()

        val nameUpdate = update(
            nameGroup,
            "\n  AND c.$RANK = 1" +
                "\n  AND NOT EXISTS (SELECT 1 FROM main.$table m2 WHERE m2.name = c.name AND m2.id <> $target.id)",
        )
        val insert = """
            INSERT INTO $target (${columns.joinToString(", ")})
            SELECT ${columns.joinToString(", ") { "c.$it" }}
            FROM $cloud c
            WHERE c.$RANK = 1
              AND NOT EXISTS (SELECT 1 FROM main.$table l WHERE l.id = c.id)
              AND NOT EXISTS (SELECT 1 FROM main.$table l2 WHERE l2.name = c.name);
        """.trimIndent()
        val earliestCreatedAt = """
            UPDATE $target
            SET created_at = c.created_at
            FROM cloud.$table c
            WHERE c.id = $target.id AND c.created_at < $target.created_at;
        """.trimIndent()
        return listOf(nameUpdate) + otherGroups.map { update(it) } + listOf(insert, earliestCreatedAt)
    }

    private val folders: List<String> = perFieldStatements(
        "folders", FOLDER_COLUMNS, SyncLww.FOLDER_NAME, listOf(SyncLww.FOLDER_SORT_ORDER, SyncLww.FOLDER_EXISTENCE),
    )

    private val tags: List<String> = perFieldStatements(
        "tags", TAG_COLUMNS, SyncLww.TAG_NAME, listOf(SyncLww.TAG_COLOR, SyncLww.TAG_SORT_ORDER, SyncLww.TAG_EXISTENCE),
    )

    // ---- feeds ----

    // The user-editable fields folder_id / sort_order / custom_title / deleted_at are intentionally
    // NOT merged in the ON CONFLICT here — each rides its own last-wins timestamp and is handled by
    // a dedicated mergeFeed* statement, so a content refresh that makes this row "newer" can never
    // clobber a folder move / reorder / rename / unsubscribe from another device. sort_order /
    // custom_title / deleted_at are still carried in the INSERT's column list so a brand-new feed
    // gets its initial values. folder_id / folder_updated_at are the one exception: they are
    // deliberately absent from the column list entirely (a brand-new feed is inserted with no
    // folder), because their value can't just be copied verbatim like the others — cloud.folder_id
    // may reference a folder this device has never seen, and only mergeFeedFolderId's FK-safe
    // resolution (folders before feeds, mergeFeedFolderId after both) is allowed to set it.
    // A feed's fetch state (feed_fetch_state: validators, error count) is device-local and never
    // merged: a feed new to this device starts with none, as if never fetched here.
    private val feeds = """
        INSERT INTO main.feeds (
            id, url, site_url, title, description, favicon_url,
            custom_title, deleted_at, updated_at, created_at, sort_order,
            sort_order_updated_at, custom_title_updated_at, deleted_updated_at
        )
        SELECT c.id, c.url, c.site_url, c.title, c.description, c.favicon_url,
               c.custom_title, c.deleted_at, c.updated_at, c.created_at, c.sort_order,
               c.sort_order_updated_at, c.custom_title_updated_at, c.deleted_updated_at
        FROM ${winnerAmong("feeds", FEED_COLUMNS, "url")} c
        WHERE c.$RANK = 1
          AND NOT EXISTS (SELECT 1 FROM main.feeds l2 WHERE l2.url = c.url AND l2.id <> c.id)
        ON CONFLICT(id) DO UPDATE SET
            ${SyncLww.upsertAssignments(SyncLww.FEED_CONTENT).joinToString(",\n            ")},
            created_at = MIN(created_at, excluded.created_at)
        WHERE ${SyncLww.wins("excluded", "", SyncLww.FEED_CONTENT)}
           OR excluded.created_at < created_at;
    """.trimIndent()

    /**
     * Merges a feed's folder assignment independently of its content row, by `folder_updated_at`
     * ([SyncLww.FEED_FOLDER], the cloud's raw `folder_id` against the local one). Runs after `feeds`
     * (so both the folder rows and the feed rows exist in `main`), and applies whether or not the
     * `feeds` statement rewrote the row — this is what lets a folder move propagate even when the
     * local feed is newer for content reasons.
     *
     * The resolved folder_id: NULL when the cloud's folder isn't alive in `cloud.folders`; else the
     * cloud's id when that folder is alive in `main`; else the alive `main` folder of the same name;
     * else NULL.
     */
    private val mergeFeedFolderId = """
        UPDATE main.feeds
        SET folder_id = (
                CASE
                    WHEN NOT EXISTS (SELECT 1 FROM cloud.folders cf WHERE cf.id = c.folder_id AND cf.deleted_at IS NULL)
                        THEN NULL
                    WHEN EXISTS (SELECT 1 FROM main.folders mf WHERE mf.id = c.folder_id AND mf.deleted_at IS NULL)
                        THEN c.folder_id
                    WHEN c.folder_id IS NOT NULL THEN (
                        SELECT mf.id FROM main.folders mf
                        INNER JOIN cloud.folders cf ON cf.name = mf.name
                        WHERE cf.id = c.folder_id
                          AND cf.deleted_at IS NULL
                          AND mf.deleted_at IS NULL
                        LIMIT 1
                    )
                    ELSE NULL
                END),
            folder_updated_at = c.folder_updated_at
        FROM cloud.feeds c
        WHERE main.feeds.id = c.id
          AND ${SyncLww.wins("c", "main.feeds", SyncLww.FEED_FOLDER)};
    """.trimIndent()

    /** A feed's per-field statement for [group], matched by id. */
    private fun mergeFeedField(group: LwwGroup): String = """
        UPDATE main.feeds
        SET ${group.columns.joinToString(", ") { "$it = c.$it" }}
        FROM cloud.feeds c
        WHERE main.feeds.id = c.id
          AND ${SyncLww.wins("c", "main.feeds", group)};
    """.trimIndent()

    /** Reorder position (`sort_order`) by `sort_order_updated_at`. */
    private val mergeFeedSortOrder = mergeFeedField(SyncLww.FEED_SORT_ORDER)

    /** Manual rename (`custom_title`) by `custom_title_updated_at`. */
    private val mergeFeedCustomTitle = mergeFeedField(SyncLww.FEED_CUSTOM_TITLE)

    /**
     * Subscription state (`deleted_at`) by `deleted_updated_at` — so an unsubscribe propagates even
     * when the other device kept refreshing the feed, and a re-subscribe (newer stamp) overrides it.
     */
    private val mergeFeedDeletedAt = mergeFeedField(SyncLww.FEED_DELETION)

    // ---- articles ----

    /** The winners among cloud articles sharing `(feed_id, guid)`, as `c`. */
    private val cloudArticles = winnerAmong("articles", ARTICLE_COLUMNS, "feed_id, guid") + " c"

    /** Which cloud articles the merge considers (also what the ks_search_pending statements consider). */
    private val articleEligible = """
        c.$RANK = 1
          AND NOT EXISTS (
              SELECT 1 FROM main.articles l2
              WHERE l2.feed_id = c.feed_id AND l2.guid = c.guid AND l2.id <> c.id
          )
          AND EXISTS (SELECT 1 FROM main.feeds mf WHERE mf.id = c.feed_id)
    """.trimIndent()

    /** Whether the cloud version of [group] wins (always, for an article main does not have). */
    private fun cloudWins(group: LwwGroup): String = "(l.id IS NULL OR ${SyncLww.wins("c", "l", group)})"

    private fun winner(group: LwwGroup, column: String): String =
        "CASE WHEN ${cloudWins(group)} THEN c.$column ELSE l.$column END"

    /**
     * Before the articles statement: registers every existing article whose body the merge is about
     * to change (the body group goes to the cloud with a different content or summary).
     */
    private val registerChangedBodies = """
        INSERT OR IGNORE INTO main.ks_search_pending (rowid)
        SELECT l.rowid
        FROM $cloudArticles
        INNER JOIN main.articles l ON l.id = c.id
        WHERE $articleEligible
          AND ${SyncLww.wins("c", "l", SyncLww.ARTICLE_BODY)}
          AND (c.content IS NOT l.content OR ${SyncLww.normalizedSummary("c")} IS NOT l.summary);
    """.trimIndent()

    private val bodyColumns = listOf("url", "title", "summary", "content", "author", "published_at", "thumbnail_url")

    // feed_id / guid are only ever set on insert: they make up the article's id, and rewriting them
    // on an existing row could only break UNIQUE(feed_id, guid) or the feed reference.
    private val articles = """
        INSERT INTO main.articles (
            id, feed_id, guid, url, title, summary, content, author, published_at,
            thumbnail_url, is_read, read_at, is_starred, starred_at, cached_at,
            updated_at, created_at, deleted_at, deleted_updated_at
        )
        SELECT
            c.id, c.feed_id, c.guid,
            ${winner(SyncLww.ARTICLE_BODY, "url")},
            ${winner(SyncLww.ARTICLE_BODY, "title")},
            CASE WHEN ${cloudWins(SyncLww.ARTICLE_BODY)} THEN ${SyncLww.normalizedSummary("c")} ELSE ${SyncLww.normalizedSummary("l")} END,
            ${winner(SyncLww.ARTICLE_BODY, "content")},
            ${winner(SyncLww.ARTICLE_BODY, "author")},
            ${winner(SyncLww.ARTICLE_BODY, "published_at")},
            ${winner(SyncLww.ARTICLE_BODY, "thumbnail_url")},
            ${winner(SyncLww.ARTICLE_READ, "is_read")},
            ${winner(SyncLww.ARTICLE_READ, "read_at")},
            ${winner(SyncLww.ARTICLE_STAR, "is_starred")},
            ${winner(SyncLww.ARTICLE_STAR, "starred_at")},
            CASE WHEN l.id IS NULL THEN c.cached_at ELSE MAX(c.cached_at, l.cached_at) END,
            ${winner(SyncLww.ARTICLE_BODY, "updated_at")},
            CASE WHEN l.id IS NULL THEN c.created_at ELSE MIN(c.created_at, l.created_at) END,
            -- deleted_at: last-wins by deleted_updated_at, but revived (NULL) when the winning star is
            -- newer than the winning deletion event (cache cleanup only deletes non-starred articles,
            -- so a later star means the user wants to keep it).
            CASE
                WHEN (${winner(SyncLww.ARTICLE_STAR, "is_starred")}) = 1
                     AND COALESCE(${winner(SyncLww.ARTICLE_STAR, "starred_at")}, 0)
                         > COALESCE(${winner(SyncLww.ARTICLE_DELETION, "deleted_updated_at")}, 0)
                    THEN NULL
                ELSE ${winner(SyncLww.ARTICLE_DELETION, "deleted_at")}
            END,
            ${winner(SyncLww.ARTICLE_DELETION, "deleted_updated_at")}
        FROM $cloudArticles
        LEFT JOIN main.articles l ON l.id = c.id
        WHERE $articleEligible
        ON CONFLICT(id) DO UPDATE SET
            ${(bodyColumns + listOf("is_read", "read_at", "is_starred", "starred_at", "cached_at", "updated_at", "created_at", "deleted_at", "deleted_updated_at")).joinToString(",\n            ") { "$it = excluded.$it" }}
        WHERE ${(bodyColumns + listOf("is_read", "read_at", "is_starred", "starred_at", "cached_at", "updated_at", "created_at", "deleted_at", "deleted_updated_at")).joinToString("\n           OR ") { "excluded.$it IS NOT $it" }};
    """.trimIndent()

    /**
     * After the articles statement: registers every merged article that has a body but no
     * `search_text` yet — a new article starts with `''`. (An existing article whose body converts to
     * `''` is registered again; recomputing it is harmless.)
     */
    private val registerNewBodies = """
        INSERT OR IGNORE INTO main.ks_search_pending (rowid)
        SELECT a.rowid
        FROM cloud.articles c
        INNER JOIN main.articles a ON a.id = c.id
        WHERE a.search_text = ''
          AND (a.content IS NOT NULL OR a.summary IS NOT NULL);
    """.trimIndent()

    // ---- feed_tags / global_settings ----

    /**
     * A link is imported only if its feed exists in main. Its tag is used as-is if it is alive in main,
     * else looked up by name through `cloud.tags` (by id, so every cloud tag row is seen); a link whose
     * tag resolves to neither is skipped. Two cloud links resolving to the same local pair are settled
     * by the ON CONFLICT against the live row, so the result does not depend on their order.
     */
    private val feedTags = """
        INSERT INTO main.feed_tags (feed_id, tag_id, deleted_at, updated_at)
        SELECT resolved.feed_id, resolved.resolved_tag_id, resolved.deleted_at, resolved.updated_at
        FROM (
            SELECT c.feed_id,
                   CASE
                       WHEN EXISTS (SELECT 1 FROM main.tags mt WHERE mt.id = c.tag_id AND mt.deleted_at IS NULL)
                           THEN c.tag_id
                       WHEN EXISTS (SELECT 1 FROM cloud.tags ct WHERE ct.id = c.tag_id) THEN (
                           SELECT mt.id FROM main.tags mt
                           INNER JOIN cloud.tags ct ON ct.name = mt.name
                           WHERE ct.id = c.tag_id
                           LIMIT 1
                       )
                       ELSE NULL
                   END AS resolved_tag_id,
                   c.deleted_at, c.updated_at
            FROM cloud.feed_tags c
            WHERE EXISTS (SELECT 1 FROM main.feeds mf WHERE mf.id = c.feed_id)
        ) resolved
        WHERE resolved.resolved_tag_id IS NOT NULL
        ON CONFLICT(feed_id, tag_id) DO UPDATE SET
            ${SyncLww.upsertAssignments(SyncLww.FEED_TAG).joinToString(",\n            ")}
        WHERE ${SyncLww.wins("excluded", "", SyncLww.FEED_TAG)};
    """.trimIndent()

    private val globalSettings = """
        INSERT INTO main.global_settings (key, value, updated_at)
        SELECT c.key, c.value, c.updated_at
        FROM cloud.global_settings c
        WHERE true
        ON CONFLICT(key) DO UPDATE SET
            ${SyncLww.upsertAssignments(SyncLww.GLOBAL_SETTING).joinToString(",\n            ")}
        WHERE ${SyncLww.wins("excluded", "", SyncLww.GLOBAL_SETTING)};
    """.trimIndent()

    /**
     * Applied in FK-safe order (parents before children): folders, feeds, the four `mergeFeed*`,
     * tags, articles (with the `ks_search_pending` registrations around it), feed_tags,
     * global_settings.
     */
    val all: List<String> = folders + listOf(
        feeds, mergeFeedFolderId, mergeFeedSortOrder, mergeFeedCustomTitle, mergeFeedDeletedAt,
    ) + tags + listOf(
        registerChangedBodies, articles, registerNewBodies, feedTags, globalSettings,
    )
}
