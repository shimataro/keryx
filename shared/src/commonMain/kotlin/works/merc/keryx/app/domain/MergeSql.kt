package works.merc.keryx.app.domain

/**
 * The ATTACH-DATABASE merge statements. The cloud file — a folded transport file, or a validated
 * pre-delta database migrated to the current local schema — is attached as `cloud`; these merge it
 * into `main` per the conflict-resolution policy ("Merge" in `docs/sync-architecture.md`):
 *
 * - every field group is last-write-wins by its own stamp ([SyncLww]); feeds: content (`updated_at`),
 *   folder, sort order, custom title, subscription state; tags / folders: existence (`updated_at`),
 *   name, sort order (and color for tags); articles: body (`body_updated_at`, content always beating
 *   a summary and any body a light row), read, retention (star and deletion as one group: the newer
 *   operation wins); feed_tags and global_settings: their one group;
 * - **a tie on the stamp is decided by the values, never by the side** ([SyncLww]'s canonical
 *   comparison), so devices merging the same files in different orders converge;
 * - `created_at` takes the earliest value, articles' `cached_at` and `updated_at` the latest;
 * - `search_text` is never merged: an article whose body the merge changes is registered in
 *   `main.ks_search_pending`, and a new article starts with `''`; one inserted without a body is
 *   recorded in `main.ks_search_awaiting_body`, so it is not indexed before its body arrives (see
 *   "FTS5 Handling").
 *
 * Statements reference only columns present both in the transport schema ([TransportSchema]) and in
 * a full database of the current schema, so the same list merges deltas, bases and pre-delta files.
 * Columns are listed explicitly (avoiding `SELECT *`, which breaks on any schema drift).
 *
 * Guards keep a merge from ever aborting on main's own constraints: NOT-EXISTS guards skip rows that
 * would collide with main (a feed's URL or an article's `(feed_id, guid)` under a different id) or
 * reference a row main lacks, and **duplicates inside `cloud`** (a folded file can hold two rows with
 * the same unique key from different devices) are resolved first: a statement that matches by
 * `feeds.url` or `articles(feed_id, guid)` considers only the winner among the cloud rows sharing
 * that key — greatest `updated_at`, then greatest id ([winnerAmong]). Statements resolving a
 * reference by id (`mergeFeedFolderId`, `feedTags`) still see every cloud row. Tag and folder rows
 * are matched by id only — their ids are name-derived (`IdGenerator.tagId` / `folderId`), so two
 * devices that created the same name hold the same id. A row the v2→v3 migration re-keyed also
 * carries its pre-upgrade id in `v2_id`: rows that shared that id but were renamed differently on
 * two devices before they upgraded are reconciled onto one id first ([reconcileFolders] /
 * [reconcileTags]), so the per-field merge converges them instead of keeping both. A tag or folder
 * name two renames landed on simply imports both rows.
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
        "id", "name", "sort_order", "deleted_at", "updated_at", "created_at", "name_updated_at",
        "sort_order_updated_at", "v2_id",
    )
    private val TAG_COLUMNS = listOf(
        "id", "name", "color", "sort_order", "deleted_at", "updated_at", "created_at",
        "name_updated_at", "color_updated_at", "sort_order_updated_at", "v2_id",
    )
    private val FEED_COLUMNS = listOf(
        "id", "url", "site_url", "title", "description", "favicon_url", "custom_title", "folder_id",
        "deleted_at", "updated_at", "created_at", "sort_order",
        "folder_updated_at", "sort_order_updated_at", "custom_title_updated_at", "deleted_updated_at",
    )
    private val ARTICLE_COLUMNS = listOf(
        "id", "feed_id", "guid", "url", "title", "summary", "content", "author", "published_at",
        "thumbnail_url", "is_read", "read_at", "is_starred", "starred_at", "cached_at",
        "updated_at", "created_at", "deleted_at", "deleted_updated_at", "body_updated_at",
    )

    // ---- v2→v3 identity reconciliation: rows sharing a pre-upgrade id keep one identity ----

    /**
     * Before version 3 a tag's or folder's id was a random UUIDv4, and two devices that created the
     * same name held different ids. The v2→v3 migration re-keyed every row onto the id its
     * creation-time name derives from and kept the pre-upgrade id in `v2_id` ([TagFolderIdRekey]); a
     * row created after the migration has no `v2_id`. If one device renames such a row and the
     * rename has not propagated before both devices upgrade, each derives a different name-derived
     * id, and an id-only merge would keep both rows. These statements reconcile them first, in every
     * merge — delta, base and pre-delta — so the per-field last-write-wins merge then treats them as
     * one row.
     *
     * For every cloud row with a non-null `v2_id`:
     *
     * 1. Its group is the cloud rows sharing that `v2_id`; the group's canonical cloud row is the
     *    greatest `updated_at`, then greatest id ([canonicalSource]).
     * 2. Its target id is main's row carrying the same `v2_id` when one exists (that is the identity
     *    already established here), else the canonical cloud row's id ([reconcileMap]). A row whose
     *    id already equals its target maps to itself.
     * 3. The target row is copied into `cloud` when `cloud` lacks it, so the per-field statements
     *    have one row per identity to match by id.
     * 4. Child references move to the target id, then the rows mapped away are deleted. For tags,
     *    `cloud.feed_tags` is resolved first: its links to the merged ids are folded into one link
     *    per `(feed_id, tag_id)` by the same last-write-wins rule as any other link, so a feed linked
     *    to both ids of a reconciled tag keeps one link.
     *
     * A target id is considered free only after the reconciled cloud rows that would be deleted are
     * taken into account. When a target id is still occupied by a cloud row that is **not** part of
     * the reconciliation (a different identity with a NULL `v2_id`), the source row is kept at its
     * original id instead: both identities and their child references are preserved, and the
     * per-field merge handles them as distinct rows. This makes the reconciliation idempotent and
     * keeps merge/fold behavior deterministic.
     *
     * The order is FK-safe with foreign keys enforced (a pre-delta cloud file is migrated to the
     * full schema, which enforces them): a parent row is copied in before a child points at it, and
     * children are moved before their old parent is deleted. A transport file carries no foreign
     * keys, so the same statements simply run there. A dangling `cloud.feed_tags` row — one this
     * merge would skip anyway — is not re-inserted, so the rebuild does not fail on it.
     *
     * A reconciled row whose target id is currently occupied by *another* reconciled source row
     * (a swap/cycle of two pre-upgrade identities) is staged through a temporary copy: the source
     * rows are deleted before the target rows are inserted, so the insert does not collide. Feeds'
     * `folder_id` is nullable, so folder references are briefly cleared while the source folders are
     * removed and then restored to the target ids. Tag references live in `feed_tags`, which is
     * rebuilt after the source and target tag rows are in place.
     *
     * Running the statements twice changes nothing: after the first run every surviving cloud row's
     * id is its target, so it maps to itself. Neither the canonical choice (`updated_at`, then id)
     * nor [SyncLww]'s link comparison depends on the order the rows were folded into the file.
     */
    private const val TAG_SOURCE = "ks_tag_source"
    private const val TAG_MAP = "ks_tag_reconcile"
    private const val TAG_FREE = "ks_tag_reconcile_free"
    private const val FEED_TAG_MAP = "ks_feed_tag_reconcile"
    private const val FOLDER_SOURCE = "ks_folder_source"
    private const val FOLDER_MAP = "ks_folder_reconcile"
    private const val FOLDER_FREE = "ks_folder_reconcile_free"

    /**
     * The canonical cloud row per `v2_id` of [table] (greatest `updated_at`, then greatest id), into
     * the temp table [source] (`v2_id` PRIMARY KEY, `source_id`).
     */
    private fun canonicalSource(table: String, source: String): List<String> = listOf(
        "CREATE TEMP TABLE IF NOT EXISTS $source (v2_id TEXT PRIMARY KEY, source_id TEXT NOT NULL)",
        "DELETE FROM $source",
        """
        INSERT INTO $source (v2_id, source_id)
        SELECT v2_id, id FROM (
            SELECT v2_id, id, ROW_NUMBER() OVER (PARTITION BY v2_id ORDER BY updated_at DESC, id DESC) AS rn
            FROM cloud.$table
            WHERE v2_id IS NOT NULL
        ) WHERE rn = 1
        """.trimIndent(),
    )

    /**
     * The target id of every cloud row of [table] with a non-null `v2_id`, into the temp table [map]
     * (`cloud_id` PRIMARY KEY, `target_id`): main's row carrying the same `v2_id` when one exists,
     * else the canonical cloud row's id ([canonicalSource]).
     */
    private fun reconcileMap(table: String, source: String, map: String): List<String> = listOf(
        "CREATE TEMP TABLE IF NOT EXISTS $map (cloud_id TEXT PRIMARY KEY, target_id TEXT NOT NULL)",
        "DELETE FROM $map",
        """
        INSERT INTO $map (cloud_id, target_id)
        SELECT c.id, COALESCE(
            (SELECT MIN(m.id) FROM main.$table m WHERE m.v2_id = c.v2_id),
            (SELECT s.source_id FROM $source s WHERE s.v2_id = c.v2_id))
        FROM cloud.$table c
        WHERE c.v2_id IS NOT NULL
        """.trimIndent(),
    )

    private val reconcileFolders: List<String> =
        canonicalSource("folders", FOLDER_SOURCE) +
            reconcileMap("folders", FOLDER_SOURCE, FOLDER_MAP) + listOf(
            "CREATE TEMP TABLE IF NOT EXISTS $FOLDER_FREE (target_id TEXT PRIMARY KEY)",
            "DELETE FROM $FOLDER_FREE",
            """
            INSERT INTO $FOLDER_FREE (target_id)
            SELECT DISTINCT m.target_id
            FROM $FOLDER_MAP m
            WHERE m.cloud_id <> m.target_id
              AND NOT EXISTS (
                  SELECT 1 FROM cloud.folders x
                  WHERE x.id = m.target_id
                    AND x.id NOT IN (SELECT cloud_id FROM $FOLDER_MAP))
            """.trimIndent(),
            // Stage the canonical source rows that will move, and the child refs that point at them.
            "CREATE TEMP TABLE IF NOT EXISTS ks_folder_stage (source_id TEXT PRIMARY KEY, target_id TEXT NOT NULL, name TEXT NOT NULL, sort_order INTEGER NOT NULL, deleted_at INTEGER, updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL, name_updated_at INTEGER, sort_order_updated_at INTEGER, v2_id TEXT)",
            "DELETE FROM ks_folder_stage",
            """
            INSERT INTO ks_folder_stage (source_id, target_id, name, sort_order, deleted_at, updated_at, created_at, name_updated_at, sort_order_updated_at, v2_id)
            SELECT f.id, r.target_id, f.name, f.sort_order, f.deleted_at, f.updated_at, f.created_at, f.name_updated_at, f.sort_order_updated_at, f.v2_id
            FROM cloud.folders f
            JOIN $FOLDER_SOURCE s ON s.source_id = f.id
            JOIN $FOLDER_MAP r ON r.cloud_id = f.id
            JOIN $FOLDER_FREE free ON free.target_id = r.target_id
            WHERE r.cloud_id <> r.target_id
            """.trimIndent(),
            "CREATE TEMP TABLE IF NOT EXISTS ks_folder_ref (feed_id TEXT PRIMARY KEY, source_id TEXT NOT NULL)",
            "DELETE FROM ks_folder_ref",
            """
            INSERT INTO ks_folder_ref (feed_id, source_id)
            SELECT f.id, f.folder_id
            FROM cloud.feeds f
            JOIN $FOLDER_MAP m ON m.cloud_id = f.folder_id
            JOIN $FOLDER_FREE free ON free.target_id = m.target_id
            WHERE m.cloud_id <> m.target_id
            """.trimIndent(),
            // Clear the references so the source folders can be deleted, then restore them to the target ids.
            "UPDATE cloud.feeds SET folder_id = NULL WHERE folder_id IN (SELECT source_id FROM ks_folder_ref)",
            """
            DELETE FROM cloud.folders
            WHERE id IN (
                SELECT m.cloud_id FROM $FOLDER_MAP m
                JOIN $FOLDER_FREE free ON free.target_id = m.target_id
                WHERE m.cloud_id <> m.target_id)
            """.trimIndent(),
            """
            INSERT INTO cloud.folders (${FOLDER_COLUMNS.joinToString(", ")})
            SELECT target_id, name, sort_order, deleted_at, updated_at, created_at, name_updated_at, sort_order_updated_at, v2_id
            FROM ks_folder_stage
            """.trimIndent(),
            """
            UPDATE cloud.feeds
            SET folder_id = (
                SELECT m.target_id
                FROM $FOLDER_MAP m
                JOIN $FOLDER_FREE free ON free.target_id = m.target_id
                WHERE m.cloud_id = ref.source_id)
            FROM ks_folder_ref ref
            WHERE cloud.feeds.id = ref.feed_id
            """.trimIndent(),
        )

    private val reconcileTags: List<String> =
        canonicalSource("tags", TAG_SOURCE) +
            reconcileMap("tags", TAG_SOURCE, TAG_MAP) + listOf(
            "CREATE TEMP TABLE IF NOT EXISTS $TAG_FREE (target_id TEXT PRIMARY KEY)",
            "DELETE FROM $TAG_FREE",
            """
            INSERT INTO $TAG_FREE (target_id)
            SELECT DISTINCT m.target_id
            FROM $TAG_MAP m
            WHERE m.cloud_id <> m.target_id
              AND NOT EXISTS (
                  SELECT 1 FROM cloud.tags x
                  WHERE x.id = m.target_id
                    AND x.id NOT IN (SELECT cloud_id FROM $TAG_MAP))
            """.trimIndent(),
            // Stage the canonical source rows that will move, then delete them before inserting at the target ids.
            "CREATE TEMP TABLE IF NOT EXISTS ks_tag_stage (source_id TEXT PRIMARY KEY, target_id TEXT NOT NULL, name TEXT NOT NULL, color TEXT, sort_order INTEGER NOT NULL, deleted_at INTEGER, updated_at INTEGER NOT NULL, created_at INTEGER NOT NULL, name_updated_at INTEGER, color_updated_at INTEGER, sort_order_updated_at INTEGER, v2_id TEXT)",
            "DELETE FROM ks_tag_stage",
            """
            INSERT INTO ks_tag_stage (source_id, target_id, name, color, sort_order, deleted_at, updated_at, created_at, name_updated_at, color_updated_at, sort_order_updated_at, v2_id)
            SELECT t.id, r.target_id, t.name, t.color, t.sort_order, t.deleted_at, t.updated_at, t.created_at, t.name_updated_at, t.color_updated_at, t.sort_order_updated_at, t.v2_id
            FROM cloud.tags t
            JOIN $TAG_SOURCE s ON s.source_id = t.id
            JOIN $TAG_MAP r ON r.cloud_id = t.id
            JOIN $TAG_FREE free ON free.target_id = r.target_id
            WHERE r.cloud_id <> r.target_id
            """.trimIndent(),
            "CREATE TEMP TABLE IF NOT EXISTS $FEED_TAG_MAP (feed_id TEXT NOT NULL, tag_id TEXT NOT NULL, deleted_at INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY (feed_id, tag_id))",
            "DELETE FROM $FEED_TAG_MAP",
            """
            INSERT INTO $FEED_TAG_MAP (feed_id, tag_id, deleted_at, updated_at)
            SELECT ft.feed_id,
                   COALESCE((SELECT r.target_id FROM $TAG_MAP r JOIN $TAG_FREE free ON free.target_id = r.target_id WHERE r.cloud_id = ft.tag_id), ft.tag_id),
                   ft.deleted_at, ft.updated_at
            FROM cloud.feed_tags ft
            WHERE true
            ON CONFLICT(feed_id, tag_id) DO UPDATE SET
                ${SyncLww.upsertAssignments(SyncLww.FEED_TAG).joinToString(",\n                ")}
            WHERE ${SyncLww.wins("excluded", "", SyncLww.FEED_TAG)}
            """.trimIndent(),
            "DELETE FROM cloud.feed_tags",
            """
            DELETE FROM cloud.tags
            WHERE id IN (
                SELECT m.cloud_id FROM $TAG_MAP m
                JOIN $TAG_FREE free ON free.target_id = m.target_id
                WHERE m.cloud_id <> m.target_id)
            """.trimIndent(),
            """
            INSERT INTO cloud.tags (${TAG_COLUMNS.joinToString(", ")})
            SELECT target_id, name, color, sort_order, deleted_at, updated_at, created_at, name_updated_at, color_updated_at, sort_order_updated_at, v2_id
            FROM ks_tag_stage
            """.trimIndent(),
            """
            INSERT INTO cloud.feed_tags (feed_id, tag_id, deleted_at, updated_at)
            SELECT m.feed_id, m.tag_id, m.deleted_at, m.updated_at
            FROM $FEED_TAG_MAP m
            WHERE EXISTS (SELECT 1 FROM cloud.feeds f WHERE f.id = m.feed_id)
              AND EXISTS (SELECT 1 FROM cloud.tags t WHERE t.id = m.tag_id)
            """.trimIndent(),
        )

    // ---- tags / folders: per-field last-write-wins, matched by id ----

    /**
     * The per-field statements of a tags/folders-shaped [table], in order: one UPDATE per group, then
     * the INSERT of rows main does not have.
     *
     * Rows are matched by id only: a tag's or folder's id is derived from its creation-time name
     * (`IdGenerator.tagId` / `folderId`), so the same name created on two devices carries the same id
     * and per-field last-write-wins converges them into one row. A row whose id main does not hold is
     * inserted as-is — a name main holds under another id (two renames that landed on one name) is
     * allowed through, so same-named rows can coexist (the UI numbers them).
     */
    private fun perFieldStatements(table: String, columns: List<String>, groups: List<LwwGroup>): List<String> {
        val target = "main.$table"

        fun update(group: LwwGroup): String = """
            UPDATE $target
            SET ${group.columns.joinToString(", ") { "$it = c.$it" }}
            FROM cloud.$table c
            WHERE c.id = $target.id
              AND ${SyncLww.wins("c", target, group)};
        """.trimIndent()

        val insert = """
            INSERT INTO $target (${columns.joinToString(", ")})
            SELECT ${columns.joinToString(", ") { "c.$it" }}
            FROM cloud.$table c
            WHERE NOT EXISTS (SELECT 1 FROM main.$table l WHERE l.id = c.id);
        """.trimIndent()
        val earliestCreatedAt = """
            UPDATE $target
            SET created_at = c.created_at
            FROM cloud.$table c
            WHERE c.id = $target.id AND c.created_at < $target.created_at;
        """.trimIndent()
        return groups.map { update(it) } + listOf(insert, earliestCreatedAt)
    }

    private val folders: List<String> = perFieldStatements(
        "folders", FOLDER_COLUMNS, listOf(SyncLww.FOLDER_NAME, SyncLww.FOLDER_SORT_ORDER, SyncLww.FOLDER_EXISTENCE),
    )

    private val tags: List<String> = perFieldStatements(
        "tags", TAG_COLUMNS, listOf(SyncLww.TAG_NAME, SyncLww.TAG_COLOR, SyncLww.TAG_SORT_ORDER, SyncLww.TAG_EXISTENCE),
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

    /** The raw folder version each feed's resolved `folder_id` came from (`sync_bookkeeping.sq`). */
    private const val FOLDER_RAW = "main.ks_feed_folder_raw"

    /**
     * Merges a feed's folder assignment independently of its content row, by `folder_updated_at`
     * ([SyncLww.FEED_FOLDER]). Runs after `feeds` (so both the folder rows and the feed rows exist in
     * `main`), and applies whether or not the `feeds` statement rewrote the row — this is what lets a
     * folder move propagate even when the local feed is newer for content reasons.
     *
     * `main.feeds.folder_id` holds a **resolved** id — this device's folder, which can differ from the
     * id the winning file carried — while every file carries its writer's raw id. Ranking a cloud raw
     * id against a stored resolved one would compare two id spaces, so on an equal stamp the result
     * would depend on the merge order (and disagree with [ReduceSql]'s fold, which keeps the raw
     * maximum). So the raw winning version is kept beside the resolved one, in [FOLDER_RAW], and the
     * merge ranks raw against raw, in three statements:
     *
     * 1. [normalizeFeedFolderRaw]: for each feed `cloud` carries, a missing or stale raw row (its
     *    stamp is not main's — a local move happened since) is replaced by main's own value, which a
     *    local write stored unresolved.
     * 2. [mergeFeedFolderId]: the cloud version replaces the feed's folder when it outranks the raw
     *    row. The resolved folder_id: NULL when the cloud's folder isn't alive in `cloud.folders`;
     *    else the cloud's id when that folder is alive in `main` (a folder's id is name-derived, so
     *    the same folder carries the same id everywhere); else NULL.
     * 3. [recordFeedFolderRaw]: the raw row takes the cloud version under the same comparison. Step 2
     *    does not touch the raw rows, so both steps pick the same winners, and afterwards each raw row
     *    carries the stamp just written to its feed.
     *
     * A raw row is valid only while its stamp IS main's: a local folder write (`updateFolder`,
     * `updateFolderAndSortOrder`) stamps `MAX(now, previous + 1)`, strictly above the stamp it
     * replaces, and a merge only ever writes both together — so a stale row can never carry main's
     * stamp, and comparing the stamp alone detects it. `cloud.feeds` is keyed by id, so each feed
     * meets at most one cloud version per merge.
     */
    private val normalizeFeedFolderRaw = """
        INSERT INTO $FOLDER_RAW (feed_id, folder_updated_at, folder_id)
        SELECT m.id, m.folder_updated_at, m.folder_id
        FROM main.feeds m
        WHERE m.id IN (SELECT id FROM cloud.feeds)
        ON CONFLICT(feed_id) DO UPDATE SET
            folder_updated_at = excluded.folder_updated_at,
            folder_id = excluded.folder_id
        WHERE folder_updated_at IS NOT excluded.folder_updated_at;
    """.trimIndent()

    /** Step 2 of the folder merge (see [normalizeFeedFolderRaw]). */
    private val mergeFeedFolderId = """
        UPDATE main.feeds
        SET folder_id = (
                CASE
                    WHEN NOT EXISTS (SELECT 1 FROM cloud.folders cf WHERE cf.id = c.folder_id AND cf.deleted_at IS NULL)
                        THEN NULL
                    WHEN EXISTS (SELECT 1 FROM main.folders mf WHERE mf.id = c.folder_id AND mf.deleted_at IS NULL)
                        THEN c.folder_id
                    ELSE NULL
                END),
            folder_updated_at = c.folder_updated_at
        FROM cloud.feeds c
        INNER JOIN $FOLDER_RAW w ON w.feed_id = c.id
        WHERE main.feeds.id = c.id
          AND ${SyncLww.wins("c", "w", SyncLww.FEED_FOLDER)};
    """.trimIndent()

    /** Step 3 of the folder merge (see [normalizeFeedFolderRaw]). */
    private val recordFeedFolderRaw = """
        UPDATE $FOLDER_RAW
        SET folder_updated_at = c.folder_updated_at,
            folder_id = c.folder_id
        FROM cloud.feeds c
        WHERE c.id = $FOLDER_RAW.feed_id
          AND ${SyncLww.wins("c", FOLDER_RAW, SyncLww.FEED_FOLDER)};
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

    /**
     * Before the articles statement: records every article the merge is about to insert without a
     * body (a light row — its body travels in another file, which may arrive later). Such a row has
     * nothing for [registerNewBodies] to recompute, and `indexMissing()` never revisits an indexed
     * row, so indexing it now with `''` would leave its body unsearchable once that body arrives.
     * `indexMissing()` therefore skips it while it is still bodiless. Matched by id, since the row
     * has no rowid yet.
     */
    private val registerAwaitingBodies = """
        INSERT OR IGNORE INTO main.ks_search_awaiting_body (id)
        SELECT c.id
        FROM $cloudArticles
        WHERE $articleEligible
          AND c.content IS NULL AND c.summary IS NULL
          AND NOT EXISTS (SELECT 1 FROM main.articles l WHERE l.id = c.id);
    """.trimIndent()

    private val bodyColumns = listOf("url", "title", "summary", "content", "author", "published_at", "thumbnail_url", "body_updated_at")

    /** Every column the articles statement may rewrite on an existing row. */
    private val articleUpdatedColumns = bodyColumns + listOf(
        "is_read", "read_at", "is_starred", "starred_at", "cached_at", "updated_at", "created_at",
        "deleted_at", "deleted_updated_at",
    )

    // feed_id / guid are only ever set on insert: they make up the article's id, and rewriting them
    // on an existing row could only break UNIQUE(feed_id, guid) or the feed reference.
    // updated_at is the row's generic stamp and no group's key: it takes the greater of the two.
    private val articles = """
        INSERT INTO main.articles (
            id, feed_id, guid, url, title, summary, content, author, published_at,
            thumbnail_url, is_read, read_at, is_starred, starred_at, cached_at,
            updated_at, created_at, deleted_at, deleted_updated_at, body_updated_at
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
            ${winner(SyncLww.ARTICLE_RETENTION, "is_starred")},
            ${winner(SyncLww.ARTICLE_RETENTION, "starred_at")},
            CASE WHEN l.id IS NULL THEN c.cached_at ELSE MAX(c.cached_at, l.cached_at) END,
            CASE WHEN l.id IS NULL THEN c.updated_at ELSE MAX(c.updated_at, l.updated_at) END,
            CASE WHEN l.id IS NULL THEN c.created_at ELSE MIN(c.created_at, l.created_at) END,
            ${winner(SyncLww.ARTICLE_RETENTION, "deleted_at")},
            ${winner(SyncLww.ARTICLE_RETENTION, "deleted_updated_at")},
            ${winner(SyncLww.ARTICLE_BODY, "body_updated_at")}
        FROM $cloudArticles
        LEFT JOIN main.articles l ON l.id = c.id
        WHERE $articleEligible
        ON CONFLICT(id) DO UPDATE SET
            ${articleUpdatedColumns.joinToString(",\n            ") { "$it = excluded.$it" }}
        WHERE ${articleUpdatedColumns.joinToString("\n           OR ") { "excluded.$it IS NOT $it" }};
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
     * A link is imported only if its feed and its tag exist in main (both by id — a tag's id is
     * name-derived, so the same tag carries the same id on every device; a link whose tag main
     * entirely lacks is an orphan and is skipped). A link to a tag main holds only as a tombstone is
     * imported like any other: it rides its own `updated_at`, and the ON CONFLICT against the live
     * row settles two cloud links resolving to the same pair, so the result does not depend on their
     * order.
     */
    private val feedTags = """
        INSERT INTO main.feed_tags (feed_id, tag_id, deleted_at, updated_at)
        SELECT c.feed_id, c.tag_id, c.deleted_at, c.updated_at
        FROM cloud.feed_tags c
        WHERE EXISTS (SELECT 1 FROM main.feeds mf WHERE mf.id = c.feed_id)
          AND EXISTS (SELECT 1 FROM main.tags mt WHERE mt.id = c.tag_id)
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
     * The merge statements for every kind of cloud file — a delta, a base, or a pre-delta database
     * migrated to the current local schema. Transport files contain only the six synced tables plus
     * metadata, so this list does not reference device-local tables. A pre-delta file's tags and
     * folders carry `v2_id` from the migration as well, so the same reconciliation applies wherever
     * a pre-upgrade id matches.
     *
     * Applied in FK-safe order (parents before children): the v2→v3 identity reconciliation,
     * folders, feeds, the folder merge's three statements and the other three `mergeFeed*`, tags,
     * articles (with the `ks_search_pending` / `ks_search_awaiting_body` registrations around it),
     * feed_tags, global_settings.
     */
    val all: List<String> = reconcileFolders + reconcileTags + folders + listOf(
        feeds, normalizeFeedFolderRaw, mergeFeedFolderId, recordFeedFolderRaw, mergeFeedSortOrder, mergeFeedCustomTitle, mergeFeedDeletedAt,
    ) + tags + listOf(
        registerChangedBodies, registerAwaitingBodies, articles, registerNewBodies, feedTags, globalSettings,
    )
}
