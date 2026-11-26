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
 * reference by id (`mergeFeedFolder`, `feedTags`) still see every cloud row. Tag and folder rows
 * are matched by id only — their ids are name-derived (`IdGenerator.tagId` / `folderId`), so two
 * devices that created the same name hold the same id. Rows that are one identity under several ids
 * (a pre-upgrade row renamed differently on two devices before they upgraded, linked by the identity
 * edges in `ks_v2_edge`) are first moved onto the identity's canonical id on both sides
 * ([V2IdentitySql]), so the per-field merge converges them instead of keeping both. A tag or folder
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

    private val FOLDER_COLUMNS = V2IdentitySql.FOLDER_COLUMNS
    private val TAG_COLUMNS = V2IdentitySql.TAG_COLUMNS
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

    // ---- tags / folders: per-field last-write-wins, matched by id ----

    /**
     * The per-field statements of a tags/folders-shaped [table], in order: one UPDATE per group, then
     * the INSERT of rows main does not have, the earliest `created_at`, and the greatest `v2_id`.
     *
     * They read the cloud rows from [V2IdentitySql.cloudTable] — `cloud.<table>` already mapped onto
     * canonical ids and folded into one row per id by [V2IdentitySql] — so a row is matched by its
     * canonical id only. The ids are name-derived (`IdGenerator.tagId` / `folderId`), so the same name
     * created on two devices carries the same id and per-field last-write-wins converges them into one
     * row; a row renamed differently on two devices before they upgraded is one identity through its
     * edges and arrives here under the same canonical id. A row whose id main does not hold is inserted
     * as-is — a name main holds under another id (two renames that landed on one name) is allowed
     * through, so same-named rows can coexist (the UI numbers them).
     *
     * `v2_id` decides nothing (the identity edges do), but it travels in sends, so it takes the
     * greatest non-NULL value of the two sides, as [ReduceSql] folds it — every device converges on the
     * same value.
     */
    private fun perFieldStatements(table: String, columns: List<String>, groups: List<LwwGroup>): List<String> {
        val target = "main.$table"
        val source = V2IdentitySql.cloudTable(table)

        val updates = groups.map { group ->
            """
            UPDATE $target
            SET ${group.columns.joinToString(", ") { "$it = c.$it" }}
            FROM $source c
            WHERE c.id = $target.id
              AND ${SyncLww.wins("c", target, group)};
            """.trimIndent()
        }
        val insert = """
            INSERT INTO $target (${columns.joinToString(", ")})
            SELECT ${columns.joinToString(", ") { "c.$it" }}
            FROM $source c
            WHERE NOT EXISTS (SELECT 1 FROM main.$table l WHERE l.id = c.id);
        """.trimIndent()
        val earliestCreatedAt = """
            UPDATE $target
            SET created_at = c.created_at
            FROM $source c
            WHERE c.id = $target.id AND c.created_at < $target.created_at;
        """.trimIndent()
        val greatestV2Id = """
            UPDATE $target
            SET v2_id = c.v2_id
            FROM $source c
            WHERE c.id = $target.id
              AND c.v2_id IS NOT NULL
              AND ($target.v2_id IS NULL OR c.v2_id > $target.v2_id);
        """.trimIndent()
        return updates + listOf(insert, earliestCreatedAt, greatestV2Id)
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
    // folder, and ks_feed_folder_raw_init gives it a NULL raw row), because feeds.folder_id is a
    // cache resolved from the feed's raw assignment — mergeFeedFolder records the raw version, and
    // the resolution triggers set folder_id from it, FK-safely (folders before feeds).
    // A feed's fetch state (feed_fetch_state: validators, error count) is device-local and never
    // merged: a feed new to this device starts with none, as if never fetched here.
    /** The canonical-id map [V2IdentitySql] builds for tags, through which `feed_tags` is read. */
    private val TAG_CANON = V2IdentitySql.canonTable(ID_REMAP_KIND_TAG)

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
     * ([SyncLww.FEED_FOLDER]). Runs after `folders` and `feeds`, so every folder and feed row the
     * file carries exists in `main`, and applies whether or not the `feeds` statement rewrote the row
     * — this is what lets a folder move propagate even when the local feed is newer for content
     * reasons.
     *
     * A feed's assignment lives in `main.ks_feed_folder_raw` — its **raw** version, the folder id and
     * stamp as the winning version wrote them — and every file carries raw versions too
     * (`DeltaExportSql`), so the merge ranks raw against raw, exactly as [ReduceSql]'s fold does, and
     * records the winner **as the file wrote it**: the folder id is never mapped onto a canonical id
     * ([V2IdentitySql]) or replaced, here or later, so a tie between two assignments is settled by the
     * same two values in every merge order and every fold. `main.feeds.folder_id` /
     * `folder_updated_at` are never written here: the schema's resolution triggers derive them from the
     * raw row through `ks_id_remap` and the folder's liveness (`ks_feed_folder_resolved` in
     * `sync_bookkeeping.sq`) whenever any of them changes, so the resolved value depends only on the
     * final raw assignment, the identity map and the folder state, whatever order the files arrive in.
     * `cloud.feeds` is keyed by id, so each feed meets at most one cloud version per merge.
     *
     * With [preDelta], a reference to a folder that neither has a row in main nor an entry in
     * `ks_id_remap` is recorded as NULL: an older build's database can hold such an orphan, and a raw
     * value no send could ever carry a row or an identity edge for would fail every later send's
     * check 9. A transport file is validated to carry, for every folder it references, the row or the
     * identity edges leading to one (check 9), so its value is kept as it is. A pre-delta file's raw
     * assignment is read from its `ks_feed_folder_raw` — the one device-local table of `cloud` the
     * merge reads — since the migration resolved its `feeds.folder_id` to NULL for a soft-deleted
     * folder, and recording that NULL would keep the feed out of the folder once it is revived.
     */
    private fun mergeFeedFolder(preDelta: Boolean): String {
        // A pre-delta file is migrated to the current schema, whose cloud.feeds.folder_id is a cache
        // resolved to NULL for a soft-deleted folder; its raw assignment is in cloud.ks_feed_folder_raw
        // (filled by the migration for every feed). A feed without a raw row falls back to its feeds
        // columns, both read from the same source.
        val (rawFolderId, rawStamp, rawJoin) = if (preDelta) {
            Triple(
                "CASE WHEN cr.feed_id IS NULL THEN c.folder_id ELSE cr.folder_id END",
                "CASE WHEN cr.feed_id IS NULL THEN c.folder_updated_at ELSE cr.folder_updated_at END",
                "LEFT JOIN cloud.ks_feed_folder_raw cr ON cr.feed_id = c.id",
            )
        } else {
            Triple("c.folder_id", "c.folder_updated_at", "")
        }
        val folderId = if (preDelta) {
            "CASE WHEN EXISTS (SELECT 1 FROM main.folders mf WHERE mf.id = $rawFolderId) " +
                "OR EXISTS (SELECT 1 FROM main.ks_id_remap mr WHERE mr.kind = '$ID_REMAP_KIND_FOLDER' AND mr.old_id = $rawFolderId) " +
                "THEN $rawFolderId END"
        } else {
            rawFolderId
        }
        return """
            INSERT INTO main.ks_feed_folder_raw (feed_id, folder_updated_at, folder_id)
            SELECT c.id, $rawStamp, $folderId
            FROM cloud.feeds c
            $rawJoin
            WHERE EXISTS (SELECT 1 FROM main.feeds l WHERE l.id = c.id)
            ON CONFLICT(feed_id) DO UPDATE SET
                ${SyncLww.upsertAssignments(SyncLww.FEED_FOLDER).joinToString(",\n                ")}
            WHERE ${SyncLww.wins("excluded", "", SyncLww.FEED_FOLDER)};
        """.trimIndent()
    }

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
     * order. The cloud's tag id is read through the canonical-id map ([V2IdentitySql]), so links to
     * aliases of one identity resolve to the same pair and fold by that same rule.
     */
    private val feedTags = """
        INSERT INTO main.feed_tags (feed_id, tag_id, deleted_at, updated_at)
        SELECT c.feed_id, c.tag_id, c.deleted_at, c.updated_at
        FROM (
            SELECT ft.feed_id,
                   COALESCE((SELECT m.canonical_id FROM $TAG_CANON m WHERE m.id = ft.tag_id), ft.tag_id) AS tag_id,
                   ft.deleted_at, ft.updated_at
            FROM cloud.feed_tags ft
        ) c
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

    // ---- identity edges: the grow-only record of which tag / folder ids are one identity ----

    /** `(table, ks_v2_edge kind, transport edge table)` of the two tables that carry `v2_id`. */
    private val EDGE_SOURCES = listOf(
        Triple("folders", ID_REMAP_KIND_FOLDER, "folder_v2_edges"),
        Triple("tags", ID_REMAP_KIND_TAG, "tag_v2_edges"),
    )

    /**
     * Adds to `main.ks_v2_edge` the `(v2_id, id)` of every `cloud` tag or folder row that carries a
     * `v2_id` — the edges a pre-delta file (which has no edge tables, but whose rows carry `v2_id`
     * after the migration's re-key) and every transport file's rows imply. Run before the identity
     * step ([V2IdentitySql]), which reads the edges.
     */
    private val recordCloudRowEdges: List<String> = EDGE_SOURCES.map { (table, kind, _) ->
        "INSERT OR IGNORE INTO main.ks_v2_edge (kind, v2_id, id) SELECT '$kind', v2_id, id FROM cloud.$table WHERE v2_id IS NOT NULL"
    }

    /**
     * Adds to `main.ks_v2_edge` every edge of a transport file's edge tables (`folder_v2_edges`,
     * `tag_v2_edges`, filled in full sends and bases). A pre-delta file has no such tables, so these
     * are only in [transport].
     */
    private val recordCloudTransportEdges: List<String> = EDGE_SOURCES.map { (_, kind, edgeTable) ->
        "INSERT OR IGNORE INTO main.ks_v2_edge (kind, v2_id, id) SELECT '$kind', v2_id, id FROM cloud.$edgeTable"
    }

    /**
     * Adds to `main.ks_feed_edge` every feed identity edge of a transport file's `feed_edges`
     * (version 3 on; an older file is migrated to an empty table first), normalized `a < b` and
     * without an edge of an id with itself. A pre-delta file has no such table, so this is only in
     * [transport]. The capture trigger records each edge new to this device in the outbox even inside
     * the merge (`ks_capture_feed_edge_insert`). Nothing in the merge reads these edges yet.
     */
    private val recordCloudFeedEdges: String =
        "INSERT OR IGNORE INTO main.ks_feed_edge (a, b) SELECT MIN(a, b), MAX(a, b) FROM cloud.feed_edges WHERE a <> b"

    /**
     * Adds to `main.ks_v2_edge` the `(v2_id, id)` of every `main` tag or folder row that carries a
     * `v2_id` (idempotent: the re-key and earlier merges recorded them already). Run before the
     * identity step, so it sees every edge main's own rows imply, and again last, so a `v2_id` this
     * merge brought into main is covered too.
     */
    private val recordMainRowEdges: List<String> = EDGE_SOURCES.map { (table, kind, _) ->
        "INSERT OR IGNORE INTO main.ks_v2_edge (kind, v2_id, id) SELECT '$kind', v2_id, id FROM main.$table WHERE v2_id IS NOT NULL"
    }

    /**
     * The merge statements, in order. They reference only `cloud` tables and columns present both in a
     * pre-delta database migrated to the current local schema and in a transport file, and no
     * device-local table of `cloud` — except that with [preDelta] a feed's raw folder assignment is
     * read from the migrated file's `ks_feed_folder_raw`; [preDelta] also picks how a feed's folder
     * reference main cannot resolve is recorded ([mergeFeedFolder]). A pre-delta file's tags and folders carry `v2_id` from the migration as well,
     * so their edges join the identity step like any other file's.
     *
     * Applied in FK-safe order (parents before children): the cloud rows' and main's rows' identity
     * edges, the identity step ([V2IdentitySql]: canonical ids, the cloud rows folded onto them, main's
     * rows moved onto them), folders, feeds, the folder merge and the other three `mergeFeed*`, tags,
     * articles (with the `ks_search_pending` / `ks_search_awaiting_body` registrations around it),
     * feed_tags, global_settings, and finally main's own rows' identity edges again.
     */
    private fun statements(preDelta: Boolean): List<String> =
        recordCloudRowEdges + recordMainRowEdges + V2IdentitySql.statements + folders + listOf(
            feeds, mergeFeedFolder(preDelta), mergeFeedSortOrder, mergeFeedCustomTitle, mergeFeedDeletedAt,
        ) + tags + listOf(
            registerChangedBodies, registerAwaitingBodies, articles, registerNewBodies, feedTags, globalSettings,
        ) + recordMainRowEdges

    /**
     * The merge statements for a pre-delta database migrated to the current local schema
     * (`DatabaseMerger.merge`) — see [statements].
     */
    val all: List<String> = statements(preDelta = true)

    /**
     * The merge statements for a transport file — a delta, a base, or the fold of several
     * (`DatabaseMerger.mergeTransport`): the identity edges of its edge tables
     * (`folder_v2_edges`, `tag_v2_edges`) and its feed identity edges (`feed_edges`), then the same list
     * as [all] — except that a feed's raw folder assignment is kept exactly as the file wrote it (see
     * [mergeFeedFolder]).
     */
    val transport: List<String> = recordCloudTransportEdges + recordCloudFeedEdges + statements(preDelta = false)
}
