package works.merc.keryx.app.domain

import app.cash.sqldelight.db.SqlDriver

/**
 * The feed and article identity step of every merge ([MergeSql]), run right after the tag/folder one
 * ([V2IdentitySql]): which feed ids are one feed, which article ids are one article, and moving both
 * sides onto the canonical ids before the per-field merge. [localStatements] runs the main-only half
 * when a refresh's permanent redirect lands on a URL another feed holds.
 *
 * **Feed identity.** A feed's id is derived from the URL it was first subscribed at
 * (`IdGenerator.feedId`) and kept across a permanent redirect, so a feed subscribed anew at a moved
 * feed's URL — on another device, or locally before the redirect was seen — gets another id for the
 * same URL. Two feed ids that held the same `url` at some point form an edge `(a, b)`, `a < b`, in
 * `main.ks_feed_edge`, a grow-only set. A merge adds every URL collision it observes: between main's
 * feeds and the cloud file's, and inside the cloud file ([recordUrlEdges]); a transport file's own
 * `feed_edges` were added already (`MergeSql.recordCloudFeedEdges`). `ks_id_remap`'s feed moves are
 * read as edges too: they duplicate edges that exist, and keep an id this device moved away from in
 * the identity it moved to. Edges are never derived from a URL's history (a feed's earlier URL may
 * have been subscribed anew by an unrelated feed), only from two ids holding one URL.
 *
 * **The canonical feed id** is the smallest (byte-wise) id of the connected component — every node is
 * a row id, so every node is a candidate. It depends only on the set of edges seen; edges only grow,
 * so it only moves to a smaller id, and every device reaches the same one once the same edges have
 * arrived (full sends, bases and deltas carry them; an edge this device found during a merge is
 * captured and sent too, `ks_capture_feed_edge_insert`). Whether a collision is observed at all can
 * depend on the order files arrive in (feed `a` may move off a URL before feed `b` takes it on one
 * device and not on another) — the documented limit; once an edge is observed anywhere it reaches
 * every device.
 *
 * **Article identity** needs no edges: an article's key is `(canonical feed id of its feed_id, guid)`,
 * and its canonical id is the smallest id among main's and the cloud file's rows with that key. An id
 * main holds keeps main's key even when the cloud file carries it under a feed this device does not
 * yet know to be the same feed (the edge may arrive later), so every id has exactly one key and two
 * keys never share a canonical id.
 *
 * **Per merge, in order:**
 * 1. URL collisions recorded as edges; `ks_feed_canon(id, canonical_id)` — every feed id whose
 *    canonical id is another one.
 * 2. The cloud side is read, not rewritten: [CLOUD_FEEDS] holds `cloud.feeds` mapped onto canonical
 *    ids and folded into one row per id by the per-group last-write-wins ([SyncLww]: content, sort
 *    order, custom title, subscription state and the raw folder assignment, `created_at` the
 *    earliest). The raw folder id is kept as the file wrote it, never mapped (see
 *    `MergeSql.mergeFeedFolder`); a pre-delta file's raw assignment is read from its
 *    `ks_feed_folder_raw`. `ks_article_canon(id, canonical_id)` maps every article id (main's and
 *    the cloud's) whose key holds a smaller id, and [cloudArticles] reads `cloud.articles` with
 *    ids and feed ids mapped and the rows sharing a canonical id folded exactly as [ReduceSql] folds
 *    them (body, read, star and deletion groups; `cached_at` / `updated_at` the latest, `created_at` the
 *    earliest; `summary` dropped once there is `content`). Rows the identity does not touch are read
 *    straight from `cloud.articles`, so a merge whose file holds no collision copies no body.
 * 3. Main is re-keyed ([mainRekey]).
 *
 * **Main's re-key and why its order cannot fail on main's own state** (foreign keys enforced; SQLite
 * checks a row's UNIQUE / PRIMARY KEY constraints as it writes it, and only the foreign keys whose
 * columns an `UPDATE` changes):
 * 1. Feeds. The moving rows are stashed and their `url` replaced by `'ks-rekey:' || id` — a value no
 *    subscription can have — so no URL is held twice when the canonical row takes one of theirs. The
 *    canonical row is then created from the moving rows, or folded into, by the per-group rules
 *    (`created_at` the earliest); every URL it can take is one a moving row just released, and each
 *    released URL belongs to exactly one canonical row. A new canonical row starts with no folder
 *    (`ks_feed_folder_raw_init` gives it a raw row); the moving rows' raw assignments are then folded
 *    into the canonical's raw row by [SyncLww.FEED_FOLDER] and deleted, and the resolution triggers
 *    derive `feeds.folder_id` from the result — always NULL or an alive folder, so the folder foreign
 *    key holds.
 * 2. Articles. A key can hold several main rows once their feeds are one feed. Of those, the row with
 *    the **largest rowid** survives: the others are folded into it (by the same rules as the cloud
 *    fold) and deleted, so a deleted rowid is never the table's largest and is never handed out
 *    again (`articles_fts` keys on the rowid, and the search joins `articles` by rowid, so the deleted
 *    rows' leftover index entries are never seen and the daily rebuild drops them). A survivor whose
 *    body changed is registered in `ks_search_pending`; the deleted rows' rowids leave it. Then each
 *    remaining row whose id is not its key's canonical id takes it in place (`UPDATE` keeps the
 *    rowid): that id is held by no other main row, since every main row holding an id of the key is
 *    the survivor or was just deleted, and no id belongs to two keys. Then the articles of moving
 *    feeds take the canonical feed id: each key has one main row left, so `UNIQUE(feed_id, guid)`
 *    holds, and the canonical feed exists. `ks_search_awaiting_body` follows the ids. Nothing
 *    references `articles.id` by foreign key.
 * 3. `feed_tags` move to the canonical feed, a link colliding with one the canonical feed already has
 *    folding into it by [SyncLww.FEED_TAG]; only `feed_id` changes, so a tag reference main already
 *    held orphaned is not checked. `feed_fetch_state` (device-local) keeps the canonical feed's row if
 *    it has one, else takes over one moving feed's (the smallest id's).
 * 4. The not-yet-sent `sync_outbox` entries naming a moving id are rewritten (`pk` of `feeds`,
 *    `feed_tags` and `articles`; a `feed_tags` entry's tag in `pk2` is untouched).
 * 5. Every feed and article id whose canonical id is another one — main's, and aliases only the cloud
 *    file used or only an edge names — is recorded in `ks_id_remap` ([IdRemapSql], chains folded), so
 *    a write issued with a stale id lands on the row it became.
 * 6. The old feed rows are deleted: every child moved off them in steps 2 and 3.
 * A child of a moving feed that main already held orphaned (its canonical feed exists nowhere — only
 * possible for an id that no row carries) is left as it is, so it stays the only kind of violation
 * main can hold (`MergeFailureClassifier`).
 *
 * **Subscription state stays plain last-write-wins** ([SyncLww.FEED_DELETION]): a feed folded with a
 * tombstone that carries a newer `deleted_updated_at` comes out unsubscribed — a fresh subscription has
 * no stamp. This is intentional: two devices holding one URL under the same id already behave so (an
 * unsubscribe propagates over a fresh subscribe), and the identity step only puts rows of one feed on
 * one id, so it must not invent a different rule. Only the local merge of a refresh's redirect
 * (`FeedRepository.applyFetch`) revives the merged feed when the user still had either side, as an
 * explicit, stamped re-subscribe.
 *
 * Running the step twice changes nothing: once every main row sits at its canonical id no row moves,
 * no key holds two main rows, and the unchanged `ks_id_remap` entries are not rewritten. A delta or
 * base merge records none of the re-key in the outbox (`sync_merging`), and none needs sending: every
 * device reaches the same canonical ids from the same edges. The pre-delta merge runs with capture on,
 * so what it writes is sent like any other row it changes.
 *
 * Statements reference only `cloud.feeds`, `cloud.articles`, a pre-delta file's
 * `cloud.ks_feed_folder_raw` and main's tables, so the same list runs in delta, base and pre-delta
 * merges.
 */
internal object FeedIdentitySql {

    /** The columns of a feed row both a transport file and a full database carry. */
    private val FEED_COLUMNS = listOf(
        "id", "url", "site_url", "title", "description", "favicon_url", "custom_title", "folder_id",
        "deleted_at", "updated_at", "created_at", "sort_order",
        "folder_updated_at", "sort_order_updated_at", "custom_title_updated_at", "deleted_updated_at",
    )

    /** The columns of an article row both a transport file and a full database carry. */
    private val ARTICLE_COLUMNS = listOf(
        "id", "feed_id", "guid", "url", "title", "summary", "content", "author", "published_at",
        "thumbnail_url", "is_read", "read_at", "is_starred", "starred_at", "cached_at",
        "updated_at", "created_at", "deleted_at", "deleted_updated_at", "body_updated_at",
    )

    /**
     * A feed row's own columns: [FEED_COLUMNS] less `folder_id` / `folder_updated_at`, a cache the
     * resolution triggers derive from `ks_feed_folder_raw`.
     */
    private val FEED_ROW_COLUMNS = FEED_COLUMNS - setOf("folder_id", "folder_updated_at")

    /** `ks_feed_canon`: every feed id whose canonical id is another one. */
    val FEED_CANON: String = V2IdentitySql.canonTable(ID_REMAP_KIND_FEED)

    /** `ks_article_canon`: every article id whose key holds a smaller id. */
    val ARTICLE_CANON: String = V2IdentitySql.canonTable(ID_REMAP_KIND_ARTICLE)

    /** `ks_cloud_feeds`: `cloud.feeds` folded onto canonical ids, raw folder assignment included. */
    val CLOUD_FEEDS: String = V2IdentitySql.cloudTable("feeds")

    private const val ADJACENCY = "ks_feed_identity_adj"
    private const val FEED_MOVE = "ks_feed_identity_move"
    private const val FEED_STASH = "ks_feed_identity_stash"
    private const val FEED_TAG_FOLD = "ks_feed_identity_feed_tag_fold"
    private const val ARTICLE_NODE = "ks_article_identity_node"
    private const val ARTICLE_SURVIVOR = "ks_article_identity_survivor"
    private const val ARTICLE_MAIN_FOLD = "ks_article_identity_main_fold"
    private const val CLOUD_ARTICLE_TOUCHED = "ks_cloud_article_touched"
    private const val CLOUD_ARTICLE_FOLDED = "ks_cloud_article_folded"

    /** The prefix a moving feed's `url` takes while the canonical row is written (see the KDoc, step 1). */
    private const val REKEY_URL_PREFIX = "ks-rekey:"

    /** The `ON CONFLICT` assignments that fold one article version into another, as [ReduceSql] does. */
    private val articleFoldAssignments: String = RowFold.ARTICLE_ASSIGNMENTS.joinToString(",\n    ")

    /** A temp table with [columns], `id` its primary key and every other column untyped. */
    private fun tempTable(name: String, columns: List<String>): String =
        "CREATE TEMP TABLE IF NOT EXISTS $name (" +
            columns.joinToString(", ") { if (it == "id") "id TEXT NOT NULL PRIMARY KEY" else it } + ")"

    // ---- 1. edges and canonical feed ids ----

    /**
     * Records every URL collision the merge can see as a feed edge (normalized `a < b`): a cloud feed
     * and a main feed with the same `url` under different ids, and two cloud feeds with the same `url`
     * (a folded file can hold both). `INSERT OR IGNORE`: an edge already known inserts nothing, so the
     * capture trigger records each new edge once.
     */
    private val recordUrlEdges: List<String> = listOf(
        "INSERT OR IGNORE INTO main.ks_feed_edge (a, b) SELECT MIN(m.id, c.id), MAX(m.id, c.id) " +
            "FROM cloud.feeds c JOIN main.feeds m ON m.url = c.url AND m.id <> c.id",
        "INSERT OR IGNORE INTO main.ks_feed_edge (a, b) SELECT c1.id, c2.id " +
            "FROM cloud.feeds c1 JOIN cloud.feeds c2 ON c2.url = c1.url AND c1.id < c2.id",
    )

    /**
     * The feed identity graph (`ks_feed_edge` and `ks_id_remap`'s feed moves, undirected) and its
     * canonical ids, into [FEED_CANON]: every node reaches its whole component, whose canonical id is
     * its smallest node. Only movers are kept.
     */
    private val canonical: List<String> = listOf(
        "CREATE TEMP TABLE IF NOT EXISTS $ADJACENCY (a TEXT NOT NULL, b TEXT NOT NULL, PRIMARY KEY (a, b)) WITHOUT ROWID",
        "DELETE FROM $ADJACENCY",
        "INSERT OR IGNORE INTO $ADJACENCY (a, b) SELECT a, b FROM main.ks_feed_edge WHERE a <> b",
        "INSERT OR IGNORE INTO $ADJACENCY (a, b) SELECT b, a FROM main.ks_feed_edge WHERE a <> b",
        "INSERT OR IGNORE INTO $ADJACENCY (a, b) SELECT old_id, new_id FROM main.ks_id_remap WHERE kind = '$ID_REMAP_KIND_FEED'",
        "INSERT OR IGNORE INTO $ADJACENCY (a, b) SELECT new_id, old_id FROM main.ks_id_remap WHERE kind = '$ID_REMAP_KIND_FEED'",
        "CREATE TEMP TABLE IF NOT EXISTS $FEED_CANON (id TEXT NOT NULL PRIMARY KEY, canonical_id TEXT NOT NULL) WITHOUT ROWID",
        "DELETE FROM $FEED_CANON",
        """
        WITH RECURSIVE ks_feed_reach(start, node) AS (
            SELECT DISTINCT a, a FROM $ADJACENCY
            UNION
            SELECT r.start, j.b FROM ks_feed_reach r JOIN $ADJACENCY j ON j.a = r.node
        )
        INSERT INTO $FEED_CANON (id, canonical_id)
        SELECT start, MIN(node) FROM ks_feed_reach
        GROUP BY start
        HAVING MIN(node) <> start
        """.trimIndent(),
    )

    // ---- 2. the cloud side ----

    /**
     * `cloud.feeds` folded onto canonical ids into [CLOUD_FEEDS] (see the object KDoc, step 2). Its
     * `folder_id` / `folder_updated_at` hold the **raw** assignment: a transport file's columns as
     * written, or — with [preDelta] — the migrated file's `ks_feed_folder_raw` row (its
     * `feeds.folder_id` is a cache resolved to NULL for a soft-deleted folder), falling back to the
     * feeds columns for a feed without one.
     */
    private fun cloudFeedFold(preDelta: Boolean): List<String> {
        val (rawFolderId, rawStamp, rawJoin) = if (preDelta) {
            Triple(
                "CASE WHEN cr.feed_id IS NULL THEN c.folder_id ELSE cr.folder_id END",
                "CASE WHEN cr.feed_id IS NULL THEN c.folder_updated_at ELSE cr.folder_updated_at END",
                "LEFT JOIN cloud.ks_feed_folder_raw cr ON cr.feed_id = c.id",
            )
        } else {
            Triple("c.folder_id", "c.folder_updated_at", "")
        }
        val selected = FEED_COLUMNS.joinToString(", ") {
            when (it) {
                "id" -> "COALESCE(m.canonical_id, c.id)"
                "folder_id" -> rawFolderId
                "folder_updated_at" -> rawStamp
                else -> "c.$it"
            }
        }
        val groups = RowFold.FEED_ROW_GROUPS + SyncLww.FEED_FOLDER
        return listOf(
            tempTable(CLOUD_FEEDS, FEED_COLUMNS),
            "DELETE FROM $CLOUD_FEEDS",
            // `WHERE true` keeps SQLite from parsing ON CONFLICT as the join's constraint.
            """
            INSERT INTO $CLOUD_FEEDS (${FEED_COLUMNS.joinToString(", ")})
            SELECT $selected
            FROM cloud.feeds c LEFT JOIN $FEED_CANON m ON m.id = c.id
            $rawJoin
            WHERE true
            ON CONFLICT(id) DO UPDATE SET
                ${(groups.flatMap(SyncLww::upsertAssignments) + RowFold.EARLIEST_CREATED_AT).joinToString(",\n                ")}
            """.trimIndent(),
        )
    }

    /**
     * The article nodes — `(id, main rowid or NULL, canonical feed id, guid)` — whose key can hold more
     * than one id, and their canonical ids into [ARTICLE_CANON]: the articles of every moving feed, with
     * [cloud] every cloud article whose id main does not hold, and then every main article sharing a key
     * with one of those. An id main holds is a node only as main's row, so it has main's key alone.
     */
    private fun articleCanonical(cloud: Boolean): List<String> = buildList {
        add(
            "CREATE TEMP TABLE IF NOT EXISTS $ARTICLE_NODE (id TEXT NOT NULL PRIMARY KEY, rid INTEGER, " +
                "feed_key TEXT NOT NULL, guid TEXT NOT NULL)",
        )
        add("CREATE INDEX IF NOT EXISTS temp.${ARTICLE_NODE}_key ON $ARTICLE_NODE (feed_key, guid)")
        add("DELETE FROM $ARTICLE_NODE")
        add(
            "INSERT OR IGNORE INTO $ARTICLE_NODE (id, rid, feed_key, guid) " +
                "SELECT a.id, a.rowid, m.canonical_id, a.guid FROM $FEED_CANON m JOIN main.articles a ON a.feed_id = m.id",
        )
        if (cloud) {
            add(
                "INSERT OR IGNORE INTO $ARTICLE_NODE (id, rid, feed_key, guid) " +
                    "SELECT c.id, NULL, COALESCE(m.canonical_id, c.feed_id), c.guid " +
                    "FROM cloud.articles c LEFT JOIN $FEED_CANON m ON m.id = c.feed_id " +
                    "WHERE NOT EXISTS (SELECT 1 FROM main.articles l WHERE l.id = c.id)",
            )
        }
        // A main article of a feed that does not move has the key (feed_id, guid) itself.
        add(
            "INSERT OR IGNORE INTO $ARTICLE_NODE (id, rid, feed_key, guid) " +
                "SELECT a.id, a.rowid, a.feed_id, a.guid " +
                "FROM (SELECT DISTINCT feed_key, guid FROM $ARTICLE_NODE) n " +
                "JOIN main.articles a ON a.feed_id = n.feed_key AND a.guid = n.guid",
        )
        add("CREATE TEMP TABLE IF NOT EXISTS $ARTICLE_CANON (id TEXT NOT NULL PRIMARY KEY, canonical_id TEXT NOT NULL) WITHOUT ROWID")
        add("DELETE FROM $ARTICLE_CANON")
        add(
            """
            INSERT INTO $ARTICLE_CANON (id, canonical_id)
            SELECT n.id, k.min_id
            FROM $ARTICLE_NODE n
            JOIN (SELECT feed_key, guid, MIN(id) AS min_id FROM $ARTICLE_NODE GROUP BY feed_key, guid HAVING COUNT(*) > 1) k
              ON k.feed_key = n.feed_key AND k.guid = n.guid
            WHERE n.id <> k.min_id
            """.trimIndent(),
        )
    }

    /**
     * The cloud articles the identity touches — whose id moves, is a canonical id another id moves
     * onto, or whose feed moves — folded by canonical id into [CLOUD_ARTICLE_FOLDED]; [cloudArticles]
     * reads them in place of their `cloud.articles` rows.
     */
    private val cloudArticleFold: List<String> = listOf(
        "CREATE TEMP TABLE IF NOT EXISTS $CLOUD_ARTICLE_TOUCHED (id TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID",
        "DELETE FROM $CLOUD_ARTICLE_TOUCHED",
        "INSERT OR IGNORE INTO $CLOUD_ARTICLE_TOUCHED (id) SELECT c.id FROM cloud.articles c " +
            "WHERE c.id IN (SELECT id FROM $ARTICLE_CANON) OR c.id IN (SELECT canonical_id FROM $ARTICLE_CANON) " +
            "OR c.feed_id IN (SELECT id FROM $FEED_CANON)",
        tempTable(CLOUD_ARTICLE_FOLDED, ARTICLE_COLUMNS),
        "DELETE FROM $CLOUD_ARTICLE_FOLDED",
        """
        INSERT INTO $CLOUD_ARTICLE_FOLDED (${ARTICLE_COLUMNS.joinToString(", ")})
        SELECT ${articleSelect("c", id = "COALESCE(ac.canonical_id, c.id)", feedId = "COALESCE(fc.canonical_id, c.feed_id)")}
        FROM cloud.articles c
        JOIN $CLOUD_ARTICLE_TOUCHED t ON t.id = c.id
        LEFT JOIN $ARTICLE_CANON ac ON ac.id = c.id
        LEFT JOIN $FEED_CANON fc ON fc.id = c.feed_id
        WHERE true
        ON CONFLICT(id) DO UPDATE SET
            $articleFoldAssignments
        """.trimIndent(),
    )

    /** [ARTICLE_COLUMNS] of the row in [alias], with [id] and [feedId] given and `summary` normalized. */
    private fun articleSelect(alias: String, id: String, feedId: String): String =
        ARTICLE_COLUMNS.joinToString(", ") {
            when (it) {
                "id" -> id
                "feed_id" -> feedId
                "summary" -> RowFold.articleSummary(alias)
                else -> "$alias.$it"
            }
        }

    /**
     * `cloud.articles` as the merge reads it: ids and feed ids on their canonical ids and the rows
     * sharing one folded (see the object KDoc, step 2), as a subquery with [ARTICLE_COLUMNS].
     */
    val cloudArticles: String = "(" +
        "SELECT ${ARTICLE_COLUMNS.joinToString(", ") { "u.$it" }} FROM cloud.articles u " +
        "WHERE u.id NOT IN (SELECT id FROM $CLOUD_ARTICLE_TOUCHED) " +
        "UNION ALL SELECT ${ARTICLE_COLUMNS.joinToString(", ")} FROM $CLOUD_ARTICLE_FOLDED)"

    // ---- 3. main's re-key ----

    /** The rows a moving main feed folds into its canonical row: per-group, `created_at` the earliest. */
    private val feedRowFoldAssignments: String =
        (RowFold.FEED_ROW_GROUPS.flatMap(SyncLww::upsertAssignments) + RowFold.EARLIEST_CREATED_AT).joinToString(",\n    ")

    /** Step 1 of the object KDoc's main re-key: the feed rows and their raw folder assignments. */
    private val mainFeeds: List<String> = listOf(
        "CREATE TEMP TABLE IF NOT EXISTS $FEED_STASH (canonical_id TEXT NOT NULL, " +
            FEED_ROW_COLUMNS.joinToString(", ") { if (it == "id") "id TEXT NOT NULL PRIMARY KEY" else it } + ")",
        "DELETE FROM $FEED_STASH",
        "INSERT INTO $FEED_STASH (canonical_id, ${FEED_ROW_COLUMNS.joinToString(", ")}) " +
            "SELECT m.canonical_id, ${FEED_ROW_COLUMNS.joinToString(", ") { "f.$it" }} " +
            "FROM main.feeds f JOIN $FEED_CANON m ON m.id = f.id",
        "UPDATE main.feeds SET url = '$REKEY_URL_PREFIX' || id WHERE id IN (SELECT id FROM $FEED_STASH)",
        """
        INSERT INTO main.feeds (${FEED_ROW_COLUMNS.joinToString(", ")})
        SELECT ${FEED_ROW_COLUMNS.joinToString(", ") { if (it == "id") "canonical_id" else it }}
        FROM $FEED_STASH
        WHERE true
        ON CONFLICT(id) DO UPDATE SET
            $feedRowFoldAssignments
        """.trimIndent(),
        // The moves whose canonical feed exists in main: every moving row's (just created if need be).
        // A child pointing at a moving id whose canonical feed exists nowhere is an orphan main already
        // held, and stays as it is.
        "CREATE TEMP TABLE IF NOT EXISTS $FEED_MOVE (id TEXT NOT NULL PRIMARY KEY, canonical_id TEXT NOT NULL) WITHOUT ROWID",
        "DELETE FROM $FEED_MOVE",
        "INSERT INTO $FEED_MOVE (id, canonical_id) SELECT m.id, m.canonical_id FROM $FEED_CANON m " +
            "WHERE EXISTS (SELECT 1 FROM main.feeds f WHERE f.id = m.canonical_id)",
        // A moving feed's raw assignment folds into the canonical feed's by FEED_FOLDER, raw against raw
        // (never mapped), as the merge and the fold rank it; the resolution triggers then derive the
        // canonical feed's folder_id. The moving raw rows go with their feeds.
        // SQLite reads the whole SELECT before writing, since it reads the target table.
        """
        INSERT INTO main.ks_feed_folder_raw (feed_id, folder_updated_at, folder_id)
        SELECT m.canonical_id, r.folder_updated_at, r.folder_id
        FROM main.ks_feed_folder_raw r JOIN $FEED_MOVE m ON m.id = r.feed_id
        WHERE true
        ON CONFLICT(feed_id) DO UPDATE SET
            ${SyncLww.upsertAssignments(SyncLww.FEED_FOLDER).joinToString(",\n            ")}
        """.trimIndent(),
        "DELETE FROM main.ks_feed_folder_raw WHERE feed_id IN (SELECT id FROM $FEED_MOVE)",
    )

    /** Step 2 of the object KDoc's main re-key: the articles. */
    private val mainArticles: List<String> = listOf(
        // Every main row of a key holding several main rows, mapped to the key's survivor: the row with
        // the largest rowid.
        "CREATE TEMP TABLE IF NOT EXISTS $ARTICLE_SURVIVOR (id TEXT NOT NULL PRIMARY KEY, survivor_id TEXT NOT NULL) WITHOUT ROWID",
        "DELETE FROM $ARTICLE_SURVIVOR",
        """
        INSERT INTO $ARTICLE_SURVIVOR (id, survivor_id)
        SELECT n.id, s.id
        FROM $ARTICLE_NODE n
        JOIN (SELECT feed_key, guid, MAX(rid) AS rid FROM $ARTICLE_NODE WHERE rid IS NOT NULL
              GROUP BY feed_key, guid HAVING COUNT(*) > 1) k
          ON k.feed_key = n.feed_key AND k.guid = n.guid
        JOIN main.articles s ON s.rowid = k.rid
        WHERE n.rid IS NOT NULL
        """.trimIndent(),
        tempTable(ARTICLE_MAIN_FOLD, ARTICLE_COLUMNS),
        "DELETE FROM $ARTICLE_MAIN_FOLD",
        // The feed id is ranked on its canonical value, as the cloud fold ranks it (it is rewritten
        // below anyway).
        """
        INSERT INTO $ARTICLE_MAIN_FOLD (${ARTICLE_COLUMNS.joinToString(", ")})
        SELECT ${articleSelect("a", id = "sv.survivor_id", feedId = "COALESCE(fc.canonical_id, a.feed_id)")}
        FROM $ARTICLE_SURVIVOR sv
        JOIN main.articles a ON a.id = sv.id
        LEFT JOIN $FEED_CANON fc ON fc.id = a.feed_id
        WHERE true
        ON CONFLICT(id) DO UPDATE SET
            $articleFoldAssignments
        """.trimIndent(),
        "DELETE FROM main.ks_search_pending WHERE rowid IN (SELECT a.rowid FROM $ARTICLE_SURVIVOR sv " +
            "JOIN main.articles a ON a.id = sv.id WHERE sv.id <> sv.survivor_id)",
        "DELETE FROM main.articles WHERE id IN (SELECT id FROM $ARTICLE_SURVIVOR WHERE id <> survivor_id)",
        "INSERT OR IGNORE INTO main.ks_search_pending (rowid) SELECT a.rowid FROM $ARTICLE_MAIN_FOLD f " +
            "JOIN main.articles a ON a.id = f.id WHERE f.content IS NOT a.content OR f.summary IS NOT a.summary",
        mainFoldUpdate(),
        "UPDATE main.articles SET id = (SELECT c.canonical_id FROM $ARTICLE_CANON c WHERE c.id = main.articles.id) " +
            "WHERE id IN (SELECT id FROM $ARTICLE_CANON)",
        "UPDATE main.articles SET feed_id = (SELECT m.canonical_id FROM $FEED_MOVE m WHERE m.id = main.articles.feed_id) " +
            "WHERE feed_id IN (SELECT id FROM $FEED_MOVE)",
        "INSERT OR IGNORE INTO main.ks_search_awaiting_body (id) SELECT c.canonical_id " +
            "FROM main.ks_search_awaiting_body w JOIN $ARTICLE_CANON c ON c.id = w.id",
        "DELETE FROM main.ks_search_awaiting_body WHERE id IN (SELECT id FROM $ARTICLE_CANON)",
    )

    /** Writes the folded values into each survivor (every column but its key and id), where they differ. */
    private fun mainFoldUpdate(): String {
        val columns = ARTICLE_COLUMNS - setOf("id", "feed_id", "guid")
        return """
            UPDATE main.articles
            SET ${columns.joinToString(", ") { "$it = f.$it" }}
            FROM $ARTICLE_MAIN_FOLD f
            WHERE f.id = main.articles.id
              AND (${columns.joinToString(" OR ") { "f.$it IS NOT main.articles.$it" }})
        """.trimIndent()
    }

    /**
     * Step 3 of the object KDoc's main re-key: a moving feed's tag links and fetch state. Links folding
     * onto one `(canonical feed, tag)` are computed first into [FEED_TAG_FOLD], then the surplus links
     * deleted, the remaining one per pair moved and the folded values written.
     */
    private val mainChildren: List<String> = listOf(
        "CREATE TEMP TABLE IF NOT EXISTS $FEED_TAG_FOLD (feed_id TEXT NOT NULL, tag_id TEXT NOT NULL, " +
            "deleted_at INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY (feed_id, tag_id)) WITHOUT ROWID",
        "DELETE FROM $FEED_TAG_FOLD",
        """
        INSERT INTO $FEED_TAG_FOLD (feed_id, tag_id, deleted_at, updated_at)
        SELECT COALESCE(m.canonical_id, ft.feed_id), ft.tag_id, ft.deleted_at, ft.updated_at
        FROM main.feed_tags ft LEFT JOIN $FEED_MOVE m ON m.id = ft.feed_id
        WHERE m.id IS NOT NULL
           OR EXISTS (SELECT 1 FROM main.feed_tags o JOIN $FEED_MOVE mo ON mo.id = o.feed_id
                      WHERE o.tag_id = ft.tag_id AND mo.canonical_id = ft.feed_id)
        ON CONFLICT(feed_id, tag_id) DO UPDATE SET
            ${SyncLww.upsertAssignments(SyncLww.FEED_TAG).joinToString(",\n            ")}
        """.trimIndent(),
        // A moving link whose tag the canonical feed already has a link to: folded into that one.
        """
        DELETE FROM main.feed_tags
        WHERE feed_id IN (SELECT id FROM $FEED_MOVE)
          AND EXISTS (SELECT 1 FROM main.feed_tags k JOIN $FEED_MOVE m ON m.canonical_id = k.feed_id
                      WHERE m.id = main.feed_tags.feed_id AND k.tag_id = main.feed_tags.tag_id)
        """.trimIndent(),
        // Of several moving links of one tag onto one canonical feed, the smallest feed id's stays.
        """
        DELETE FROM main.feed_tags
        WHERE feed_id IN (SELECT id FROM $FEED_MOVE)
          AND EXISTS (SELECT 1 FROM main.feed_tags o
                      JOIN $FEED_MOVE mo ON mo.id = o.feed_id
                      JOIN $FEED_MOVE ms ON ms.id = main.feed_tags.feed_id
                      WHERE o.tag_id = main.feed_tags.tag_id AND mo.canonical_id = ms.canonical_id
                        AND o.feed_id < main.feed_tags.feed_id)
        """.trimIndent(),
        "UPDATE main.feed_tags SET feed_id = (SELECT m.canonical_id FROM $FEED_MOVE m WHERE m.id = main.feed_tags.feed_id) " +
            "WHERE feed_id IN (SELECT id FROM $FEED_MOVE)",
        """
        UPDATE main.feed_tags SET deleted_at = f.deleted_at, updated_at = f.updated_at
        FROM $FEED_TAG_FOLD f
        WHERE f.feed_id = main.feed_tags.feed_id AND f.tag_id = main.feed_tags.tag_id
          AND (f.deleted_at IS NOT main.feed_tags.deleted_at OR f.updated_at IS NOT main.feed_tags.updated_at)
        """.trimIndent(),
        // feed_fetch_state is device-local: the canonical feed's own row wins, else the smallest moving id's.
        "DELETE FROM main.feed_fetch_state WHERE feed_id IN (SELECT id FROM $FEED_MOVE) " +
            "AND EXISTS (SELECT 1 FROM main.feed_fetch_state k JOIN $FEED_MOVE m ON m.canonical_id = k.feed_id " +
            "WHERE m.id = main.feed_fetch_state.feed_id)",
        "DELETE FROM main.feed_fetch_state WHERE feed_id IN (SELECT id FROM $FEED_MOVE) " +
            "AND EXISTS (SELECT 1 FROM main.feed_fetch_state o JOIN $FEED_MOVE mo ON mo.id = o.feed_id " +
            "JOIN $FEED_MOVE ms ON ms.id = main.feed_fetch_state.feed_id " +
            "WHERE mo.canonical_id = ms.canonical_id AND o.feed_id < main.feed_fetch_state.feed_id)",
        "UPDATE main.feed_fetch_state SET feed_id = (SELECT m.canonical_id FROM $FEED_MOVE m WHERE m.id = main.feed_fetch_state.feed_id) " +
            "WHERE feed_id IN (SELECT id FROM $FEED_MOVE)",
    )

    /** Steps 4 to 6 of the object KDoc's main re-key: the outbox, the recorded moves, the old feed rows. */
    private val mainFinish: List<String> =
        listOf(
            "UPDATE main.sync_outbox SET pk = (SELECT m.canonical_id FROM $FEED_CANON m WHERE m.id = main.sync_outbox.pk) " +
                "WHERE tbl IN ('feeds', 'feed_tags') AND pk IN (SELECT id FROM $FEED_CANON)",
            "UPDATE main.sync_outbox SET pk = (SELECT c.canonical_id FROM $ARTICLE_CANON c WHERE c.id = main.sync_outbox.pk) " +
                "WHERE tbl = 'articles' AND pk IN (SELECT id FROM $ARTICLE_CANON)",
        ) + recordMoves(ID_REMAP_KIND_FEED, FEED_CANON) + recordMoves(ID_REMAP_KIND_ARTICLE, ARTICLE_CANON) + listOf(
            "DELETE FROM main.feeds WHERE id IN (SELECT id FROM $FEED_CANON)",
        )

    /**
     * Records in `ks_id_remap` every id of [canon] (kind [kind]) mapped onto its canonical id; only new
     * or changed entries are written. Each maps straight to its final id (a canonical id never moves
     * in the same run), as [IdRemapSql.foldStatements] requires.
     */
    private fun recordMoves(kind: String, canon: String): List<String> = IdRemapSql.foldStatements(
        "SELECT '$kind', c.id, c.canonical_id FROM $canon c " +
            "WHERE NOT EXISTS (SELECT 1 FROM main.ks_id_remap r " +
            "WHERE r.kind = '$kind' AND r.old_id = c.id AND r.new_id = c.canonical_id)",
    )

    /** Main's whole re-key, in the order the object KDoc argues. */
    private val mainRekey: List<String> = mainFeeds + mainArticles + mainChildren + mainFinish

    /**
     * The merge's identity step, in order. [preDelta] picks where a feed's raw folder assignment is
     * read from ([cloudFeedFold]). Runs after the transport file's `feed_edges` have been recorded and
     * after [V2IdentitySql], so tag ids are already canonical when the feeds' links move.
     */
    fun statements(preDelta: Boolean): List<String> =
        recordUrlEdges + canonical + cloudFeedFold(preDelta) + articleCanonical(cloud = true) + cloudArticleFold + mainRekey

    /**
     * The main-only identity step, for a database with no cloud file attached: the canonical ids from
     * the edges main holds, and main re-keyed onto them. Wrapped in `sync_merging`, so, as in a delta
     * merge, the re-key itself records nothing in the outbox — every device that holds the same edges
     * reaches the same rows — while a newly added edge is still recorded (`ks_capture_feed_edge_insert`
     * has no `sync_merging` guard), which is what brings the other devices to the same merge.
     */
    val localStatements: List<String> =
        listOf("INSERT INTO main.sync_merging (flag) VALUES (1)") +
            canonical + articleCanonical(cloud = false) + mainRekey +
            listOf("DELETE FROM main.sync_merging")

    /**
     * Merges the feeds [idA] and [idB] — which hold, or are about to hold, the same URL — on [driver]:
     * records their edge in `ks_feed_edge` and runs [localStatements], moving both (and every feed of
     * their identity) onto the canonical id. Run it inside the caller's transaction, so the edge and
     * the re-key commit with the write that needed them.
     */
    fun mergeLocally(driver: SqlDriver, idA: String, idB: String) {
        if (idA == idB) return
        // Normalized by SQLite's own comparison (BINARY), the order every other edge writer uses.
        driver.execute(null, "INSERT OR IGNORE INTO ks_feed_edge (a, b) VALUES (MIN(?, ?), MAX(?, ?))", 4) {
            bindString(0, idA)
            bindString(1, idB)
            bindString(2, idA)
            bindString(3, idB)
        }
        for (sql in localStatements) driver.execute(null, sql, 0)
    }
}
