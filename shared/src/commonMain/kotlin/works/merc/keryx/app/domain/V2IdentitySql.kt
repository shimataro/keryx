package works.merc.keryx.app.domain

/**
 * The identity step of every merge ([MergeSql]): which tag and folder ids are one identity, which of
 * them is its canonical id, and moving both sides onto it before the per-field merge — see
 * "Identities of tags and folders" in `docs/sync-architecture.md`.
 *
 * **The identity.** Before version 3 a tag's or folder's id was a random UUIDv4; the v2→v3 migration
 * re-keyed every row onto the id its name derives from and kept the pre-upgrade id in `v2_id`
 * ([TagFolderIdRekey]). Two devices that renamed the same pre-upgrade row differently before upgrading
 * therefore hold it under two ids. Every such link is an identity edge `(kind, v2_id, id)` in
 * `main.ks_v2_edge` — a grow-only set that every merge extends from the cloud file before this step
 * runs. The ids an undirected graph over these edges connects (two row ids sharing a `v2_id`, and so
 * on transitively; a row id is one node whatever row carries it, so rows sharing an id are trivially
 * one identity) form one identity. `main.ks_id_remap`'s moves are added as edges too: they normally
 * duplicate edges already present (every re-key happens along one), and keep an id this device moved
 * away from in the identity it moved to.
 *
 * **The canonical id** is the smallest row id of the identity (byte-wise) — over the ids the edges
 * name as row ids and the ids of the rows on either side; a pre-upgrade id is never a candidate. It
 * depends only on the set of edges seen, never on the order files arrived in, how they were folded, or
 * what one file happens to contain. Edges only ever grow, so the canonical id only ever moves to a
 * smaller id, and every device converges on the same one once it has seen the same edges — which every
 * device eventually does, since full sends and bases carry every edge.
 *
 * **Per kind (folders first, then tags), in order:**
 * 1. `ks_<kind>_canon(id, canonical_id)`: every id whose canonical id is another one.
 * 2. The cloud side is read through `ks_cloud_<table>` rather than rewritten: its rows mapped onto
 *    their canonical ids and folded into one row per id by the same per-group last-write-wins as any
 *    other merge ([SyncLww], `created_at` the earliest, `v2_id` the greatest). The per-field merge
 *    reads that table; `feedTags` maps `cloud.feed_tags.tag_id` through the canon table as it reads
 *    it. A feed's folder assignment is **not** mapped: it is compared and stored as the file wrote it,
 *    exactly as [ReduceSql]'s fold compares it, so a tie between two assignments is settled by the
 *    same values on every device and in every fold, however the canonical ids later move; only the
 *    resolved `feeds.folder_id` reads it through `ks_id_remap` (`ks_feed_folder_resolved`). Leaving the
 *    attached file untouched keeps a pre-delta file's own foreign keys and triggers (it is a full
 *    database) out of play.
 * 3. The main side is re-keyed: each main row whose id has another canonical id is merged into the
 *    canonical row (created from it when main lacks one) by the same per-group rules; its tag links
 *    follow (`feed_tags`: a link colliding with one the canonical tag already has folds into it by
 *    [SyncLww.FEED_TAG]), and so do the not-yet-sent `sync_outbox` entries naming the id. Every id of
 *    the kind whose canonical id is another one — main's, the aliases only the cloud file used, and
 *    the pre-upgrade ids only an edge names — is
 *    then recorded in `ks_id_remap` ([IdRemapSql]); for folders, the table's triggers re-resolve every
 *    feed whose raw assignment names a moving id onto the canonical folder (the raw assignment itself
 *    stays as it was). Then the old rows are deleted. Ids held outside the database follow once the
 *    merge commits (`TagFolderIdRemaps`).
 *
 * The order is foreign-key safe with foreign keys enforced: a canonical row exists before any child
 * points at it, every child has moved before its old parent is deleted, and a moved child changes only
 * the referencing column (SQLite checks only the foreign keys whose columns an `UPDATE` changes, so a
 * link that main already held orphaned by a missing feed is moved without tripping that feed's check).
 * Nothing here can fail on main's own state, so `MergeFailureClassifier`'s assumption — main can only
 * already violate FOREIGN KEY constraints — holds.
 *
 * Running the step twice changes nothing: once every main row sits at its canonical id no main row
 * moves, and the cloud fold maps onto the same ids. The capture triggers record nothing in a delta or
 * base merge (`sync_merging`), and every other device reaches the same canonical id from the same
 * edges, so nothing needs sending. A pre-delta merge runs with capture on, so the canonical rows it
 * writes are sent like any other row that merge changes.
 *
 * Statements reference only `cloud.folders`, `cloud.tags` (ids) and main's tables, so the same list
 * runs in delta, base and pre-delta merges.
 */
internal object V2IdentitySql {

    /** The columns of a folder row both a transport file and a full database carry. */
    internal val FOLDER_COLUMNS = listOf(
        "id", "name", "sort_order", "deleted_at", "updated_at", "created_at", "name_updated_at",
        "sort_order_updated_at", "v2_id",
    )

    /** The columns of a tag row both a transport file and a full database carry. */
    internal val TAG_COLUMNS = listOf(
        "id", "name", "color", "sort_order", "deleted_at", "updated_at", "created_at",
        "name_updated_at", "color_updated_at", "sort_order_updated_at", "v2_id",
    )

    /** One table that carries `v2_id`: its `ks_v2_edge` / `ks_id_remap` kind, columns and groups. */
    private class Kind(
        val table: String,
        val kind: String,
        val columns: List<String>,
        val columnTypes: List<String>,
        val groups: List<LwwGroup>,
        /** Statements run once the moves are recorded, before the old rows are deleted. */
        val afterRecorded: List<String> = emptyList(),
    ) {
        /** The temp table mapping an id onto its canonical id (movers only). */
        val canon: String = canonTable(kind)

        /** The temp table holding the cloud rows folded onto their canonical ids. */
        val cloudFold: String = cloudTable(table)
    }

    /** `ks_<kind>_canon`: every id whose canonical id is another one (see the object KDoc). */
    fun canonTable(kind: String): String = "ks_${kind}_canon"

    /** `ks_cloud_<table>`: `cloud.<table>`'s rows folded onto their canonical ids. */
    fun cloudTable(table: String): String = "ks_cloud_$table"

    private val FOLDERS = Kind(
        table = "folders",
        kind = ID_REMAP_KIND_FOLDER,
        columns = FOLDER_COLUMNS,
        columnTypes = listOf("TEXT", "TEXT", "INTEGER", "INTEGER", "INTEGER", "INTEGER", "INTEGER", "INTEGER", "TEXT"),
        groups = RowFold.FOLDER_GROUPS,
        // feeds.folder_id is resolved from the raw assignment through ks_id_remap, and recording the
        // moves has re-resolved every feed whose raw assignment names a moving id. A feed whose cache
        // still names a moving folder (one without a raw row) is resolved again here, so no feed points
        // at a folder about to be deleted.
        afterRecorded = listOf(
            """
            UPDATE main.feeds SET
                folder_id = (SELECT v.folder_id FROM main.ks_feed_folder_resolved v WHERE v.feed_id = main.feeds.id),
                folder_updated_at = (SELECT v.folder_updated_at FROM main.ks_feed_folder_resolved v WHERE v.feed_id = main.feeds.id)
            WHERE folder_id IN (SELECT id FROM ${canonTable(ID_REMAP_KIND_FOLDER)})
            """.trimIndent(),
        ),
    )

    private val TAGS = Kind(
        table = "tags",
        kind = ID_REMAP_KIND_TAG,
        columns = TAG_COLUMNS,
        columnTypes = listOf("TEXT", "TEXT", "TEXT", "INTEGER", "INTEGER", "INTEGER", "INTEGER", "INTEGER", "INTEGER", "INTEGER", "TEXT"),
        groups = RowFold.TAG_GROUPS,
    )

    private const val ADJACENCY = "ks_identity_adj"
    private const val CANDIDATE = "ks_identity_candidate"
    private const val FEED_TAG_FOLD = "ks_identity_feed_tag_fold"

    /**
     * The `ON CONFLICT` assignments that fold one version of a row into another, as [ReduceSql] folds
     * it ([RowFold]): every group by [SyncLww], the earliest `created_at`, and the greatest non-NULL `v2_id`.
     */
    private fun foldAssignments(kind: Kind): String =
        (kind.groups.flatMap(SyncLww::upsertAssignments) + listOf(RowFold.EARLIEST_CREATED_AT, RowFold.GREATEST_V2_ID))
            .joinToString(",\n    ")

    /** The identity graph of [kind] and its canonical ids, into [Kind.canon]. */
    private fun canonical(kind: Kind): List<String> = listOf(
        "CREATE TEMP TABLE IF NOT EXISTS $ADJACENCY (a TEXT NOT NULL, b TEXT NOT NULL, PRIMARY KEY (a, b)) WITHOUT ROWID",
        "DELETE FROM $ADJACENCY",
        "INSERT OR IGNORE INTO $ADJACENCY (a, b) SELECT v2_id, id FROM main.ks_v2_edge WHERE kind = '${kind.kind}'",
        "INSERT OR IGNORE INTO $ADJACENCY (a, b) SELECT id, v2_id FROM main.ks_v2_edge WHERE kind = '${kind.kind}'",
        "INSERT OR IGNORE INTO $ADJACENCY (a, b) SELECT old_id, new_id FROM main.ks_id_remap WHERE kind = '${kind.kind}'",
        "INSERT OR IGNORE INTO $ADJACENCY (a, b) SELECT new_id, old_id FROM main.ks_id_remap WHERE kind = '${kind.kind}'",
        // The row ids of the graph: an edge's row id, and the id of any row either side holds. A
        // pre-upgrade id alone never becomes canonical. Only ids on some edge matter: any other row is
        // an identity of its own, at its own id.
        "CREATE TEMP TABLE IF NOT EXISTS $CANDIDATE (id TEXT NOT NULL PRIMARY KEY) WITHOUT ROWID",
        "DELETE FROM $CANDIDATE",
        "INSERT OR IGNORE INTO $CANDIDATE (id) SELECT id FROM main.ks_v2_edge WHERE kind = '${kind.kind}'",
        "INSERT OR IGNORE INTO $CANDIDATE (id) SELECT id FROM main.${kind.table} WHERE id IN (SELECT a FROM $ADJACENCY)",
        "INSERT OR IGNORE INTO $CANDIDATE (id) SELECT id FROM cloud.${kind.table} WHERE id IN (SELECT a FROM $ADJACENCY)",
        "CREATE TEMP TABLE IF NOT EXISTS ${kind.canon} (id TEXT NOT NULL PRIMARY KEY, canonical_id TEXT NOT NULL) WITHOUT ROWID",
        "DELETE FROM ${kind.canon}",
        // Every node of the graph reaches its whole identity (UNION ends the recursion once nothing
        // new is reached); its canonical id is the smallest candidate reached. Every node is a start,
        // not just the candidates, so an id that appears only as an edge's pre-upgrade side (one a
        // feed's raw folder assignment can still name) is mapped onto its identity's canonical id
        // too — it is never canonical itself, since only candidates are. Only movers are kept.
        """
        WITH RECURSIVE ks_reach(start, node) AS (
            SELECT DISTINCT a, a FROM $ADJACENCY
            UNION
            SELECT r.start, j.b FROM ks_reach r JOIN $ADJACENCY j ON j.a = r.node
        )
        INSERT INTO ${kind.canon} (id, canonical_id)
        SELECT start, MIN(node) FROM ks_reach
        WHERE node IN (SELECT id FROM $CANDIDATE)
        GROUP BY start
        HAVING MIN(node) <> start
        """.trimIndent(),
    )

    /** `cloud.<table>` folded onto canonical ids, into [Kind.cloudFold] (see the object KDoc, step 2). */
    private fun cloudFold(kind: Kind): List<String> {
        val definitions = kind.columns.zip(kind.columnTypes).joinToString(", ") { (column, type) ->
            if (column == "id") "id TEXT NOT NULL PRIMARY KEY" else "$column $type"
        }
        val rest = kind.columns.drop(1)
        return listOf(
            "CREATE TEMP TABLE IF NOT EXISTS ${kind.cloudFold} ($definitions)",
            "DELETE FROM ${kind.cloudFold}",
            // `WHERE true` keeps SQLite from parsing ON CONFLICT as the join's constraint.
            """
            INSERT INTO ${kind.cloudFold} (${kind.columns.joinToString(", ")})
            SELECT COALESCE(m.canonical_id, c.id), ${rest.joinToString(", ") { "c.$it" }}
            FROM cloud.${kind.table} c LEFT JOIN ${kind.canon} m ON m.id = c.id
            WHERE true
            ON CONFLICT(id) DO UPDATE SET
                ${foldAssignments(kind)}
            """.trimIndent(),
        )
    }

    /**
     * Main's rows of [kind] moved onto their canonical ids (see the object KDoc, step 3), up to the
     * deletion of the old rows; [children] move the references in between.
     */
    private fun mainRekey(kind: Kind, children: List<String>): List<String> {
        val rest = kind.columns.drop(1)
        return listOf(
            // The canonical row first: created from the first row moving onto it, then every further one
            // folded in. SQLite reads the whole SELECT before writing, since it reads the target table.
            """
            INSERT INTO main.${kind.table} (${kind.columns.joinToString(", ")})
            SELECT m.canonical_id, ${rest.joinToString(", ") { "t.$it" }}
            FROM main.${kind.table} t JOIN ${kind.canon} m ON m.id = t.id
            WHERE true
            ON CONFLICT(id) DO UPDATE SET
                ${foldAssignments(kind)}
            """.trimIndent(),
        ) + children + listOf(
            "UPDATE main.sync_outbox SET pk = (SELECT m.canonical_id FROM ${kind.canon} m WHERE m.id = main.sync_outbox.pk) " +
                "WHERE tbl = '${kind.table}' AND pk IN (SELECT id FROM ${kind.canon})",
        ) + recordMoves(kind) + kind.afterRecorded + listOf(
            "DELETE FROM main.${kind.table} WHERE id IN (SELECT id FROM ${kind.canon})",
        )
    }

    /**
     * Records in `ks_id_remap` every id of [kind] whose canonical id is another one — the ids main's
     * rows had, and the aliases only the cloud file used or only an edge's pre-upgrade side names (a
     * feed's raw folder assignment can name one, and is stored as the file wrote it:
     * `ks_feed_folder_resolved` resolves it through this table).
     * Only entries that are new or changed are written, so an unchanged map writes nothing. Each maps an
     * id straight to its final canonical id (a canonical id never moves in the same run), as
     * [IdRemapSql.foldStatements] requires. Recorded before the old rows are deleted: for folders, the
     * table's triggers move every feed resolved to a moving folder onto the canonical one first.
     */
    private fun recordMoves(kind: Kind): List<String> = IdRemapSql.foldStatements(
        "SELECT '${kind.kind}', c.id, c.canonical_id FROM ${kind.canon} c " +
            "WHERE NOT EXISTS (SELECT 1 FROM main.ks_id_remap r " +
            "WHERE r.kind = '${kind.kind}' AND r.old_id = c.id AND r.new_id = c.canonical_id)",
    )

    /**
     * The children of a moving tag: its `feed_tags` links move to the canonical tag. Where a feed ends
     * up with several links to one canonical tag (its own, and one per moving alias), they fold into
     * one by [SyncLww.FEED_TAG] — computed first into [FEED_TAG_FOLD], then the surplus links deleted,
     * the remaining one per pair moved, and the folded values written. Only the moved link's `tag_id`
     * and the kept links' values change, never a `feed_id`.
     */
    private val tagChildren: List<String> = listOf(
        "CREATE TEMP TABLE IF NOT EXISTS $FEED_TAG_FOLD (feed_id TEXT NOT NULL, tag_id TEXT NOT NULL, " +
            "deleted_at INTEGER, updated_at INTEGER NOT NULL, PRIMARY KEY (feed_id, tag_id)) WITHOUT ROWID",
        "DELETE FROM $FEED_TAG_FOLD",
        """
        INSERT INTO $FEED_TAG_FOLD (feed_id, tag_id, deleted_at, updated_at)
        SELECT ft.feed_id, COALESCE(m.canonical_id, ft.tag_id), ft.deleted_at, ft.updated_at
        FROM main.feed_tags ft LEFT JOIN ${TAGS.canon} m ON m.id = ft.tag_id
        WHERE m.id IS NOT NULL
           OR EXISTS (SELECT 1 FROM main.feed_tags o JOIN ${TAGS.canon} mo ON mo.id = o.tag_id
                      WHERE o.feed_id = ft.feed_id AND mo.canonical_id = ft.tag_id)
        ON CONFLICT(feed_id, tag_id) DO UPDATE SET
            ${SyncLww.upsertAssignments(SyncLww.FEED_TAG).joinToString(",\n            ")}
        """.trimIndent(),
        // A moving link whose feed already has a link to the canonical tag: folded into that one.
        """
        DELETE FROM main.feed_tags
        WHERE tag_id IN (SELECT id FROM ${TAGS.canon})
          AND EXISTS (SELECT 1 FROM main.feed_tags k JOIN ${TAGS.canon} m ON m.canonical_id = k.tag_id
                      WHERE m.id = main.feed_tags.tag_id AND k.feed_id = main.feed_tags.feed_id)
        """.trimIndent(),
        // Of several moving links of one feed onto one canonical tag, the one with the smallest tag id
        // stays to be moved.
        """
        DELETE FROM main.feed_tags
        WHERE tag_id IN (SELECT id FROM ${TAGS.canon})
          AND EXISTS (SELECT 1 FROM main.feed_tags o
                      JOIN ${TAGS.canon} mo ON mo.id = o.tag_id
                      JOIN ${TAGS.canon} ms ON ms.id = main.feed_tags.tag_id
                      WHERE o.feed_id = main.feed_tags.feed_id AND mo.canonical_id = ms.canonical_id
                        AND o.tag_id < main.feed_tags.tag_id)
        """.trimIndent(),
        "UPDATE main.feed_tags SET tag_id = (SELECT m.canonical_id FROM ${TAGS.canon} m WHERE m.id = main.feed_tags.tag_id) " +
            "WHERE tag_id IN (SELECT id FROM ${TAGS.canon})",
        """
        UPDATE main.feed_tags SET deleted_at = f.deleted_at, updated_at = f.updated_at
        FROM $FEED_TAG_FOLD f
        WHERE f.feed_id = main.feed_tags.feed_id AND f.tag_id = main.feed_tags.tag_id
          AND (f.deleted_at IS NOT main.feed_tags.deleted_at OR f.updated_at IS NOT main.feed_tags.updated_at)
        """.trimIndent(),
        "UPDATE main.sync_outbox SET pk2 = (SELECT m.canonical_id FROM ${TAGS.canon} m WHERE m.id = main.sync_outbox.pk2) " +
            "WHERE tbl = 'feed_tags' AND pk2 IN (SELECT id FROM ${TAGS.canon})",
    )

    /**
     * The whole identity step, in order. Runs after the merge has recorded the cloud file's edges and
     * main's rows' edges in `main.ks_v2_edge`, and before the per-field statements of either table.
     */
    val statements: List<String> = buildList {
        for ((kind, children) in listOf(FOLDERS to emptyList(), TAGS to tagChildren)) {
            addAll(canonical(kind))
            addAll(cloudFold(kind))
            addAll(mainRekey(kind, children))
        }
    }
}
