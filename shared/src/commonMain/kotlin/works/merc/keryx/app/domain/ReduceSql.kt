package works.merc.keryx.app.domain

/**
 * Folds one transport file, attached as `src`, into an accumulator transport file (`main`) — see
 * "Receiving" and "Bases and Garbage Collection" in `docs/sync-architecture.md`.
 *
 * Rows are matched **by primary key only**: there are no local guards and no name or URL
 * resolution here (those belong to each device's own merge, [MergeSql]), so two rows with the same
 * tag name but different ids simply both stay. Every field group is resolved with the same
 * last-write-wins order and value tie-break as [MergeSql] ([SyncLww]), which makes folding
 * idempotent, commutative and associative, and merging a fold equal to merging its inputs one by
 * one.
 *
 * There are no special cases on top of the groups: an article's star and deletion are one group
 * ([SyncLww.ARTICLE_RETENTION]), resolved like any other. `created_at` takes the earliest value,
 * an article's `cached_at` and `updated_at` (no group's key) the latest, and an article's `summary` is
 * dropped once it has `content`.
 *
 * The identity edge tables (`folder_v2_edges`, `tag_v2_edges`) are a grow-only set and fold by
 * union ([edges]): every edge of `src`, plus the `(v2_id, id)` of every `src` tag or folder row that
 * carries a `v2_id`. The rows' own `v2_id` keeps only one value per id (the greatest), so without the
 * row-derived edges a fold of two files whose rows share an id but carry different `v2_id`s would
 * lose one of the two links; with them, a base built by folding keeps every input's edges.
 *
 * The feed identity edges (`feed_edges`, two feed ids that shared a `url`, `a < b`) fold by union as
 * well, plus every URL collision the fold sees ([feedUrlEdges]): two distinct feed ids sharing a `url`
 * between `src` and the accumulator, or within `src`. They are recorded before the feeds upsert can
 * change a `url`, since the fold still matches feeds by primary key only and keeps one `url` per id —
 * without them, folding `a@X`, then `a@Y`, then `b@X` would lose the collision a merge of the same
 * files one by one sees.
 *
 * Columns are listed explicitly; both sides must be transport files of [TransportSchema.VERSION].
 */
object ReduceSql {

    private fun upsert(table: TableSpec, groups: List<LwwGroup>, extra: List<String> = emptyList()): String {
        val columns = table.columnNames.joinToString(", ")
        // An article's summary is dropped once it has content, as the merge does.
        val selected = table.columnNames.joinToString(", ") {
            if (table.name == "articles" && it == "summary") "${SyncLww.normalizedSummary("")} AS summary" else it
        }
        val assignments = (groups.flatMap(SyncLww::upsertAssignments) + extra).joinToString(",\n    ")
        // `WHERE true` keeps SQLite from parsing ON CONFLICT as a join constraint of the SELECT.
        return """
            INSERT INTO main.${table.name} ($columns)
            SELECT $selected FROM src.${table.name} WHERE true
            ON CONFLICT(${table.primaryKey.joinToString(", ")}) DO UPDATE SET
                $assignments;
        """.trimIndent()
    }

    private fun table(name: String): TableSpec = TransportSchema.SYNCED_TABLES.first { it.name == name }

    private const val EARLIEST_CREATED_AT = "created_at = MIN(created_at, excluded.created_at)"

    private val folders = upsert(
        table("folders"),
        listOf(SyncLww.FOLDER_EXISTENCE, SyncLww.FOLDER_NAME, SyncLww.FOLDER_SORT_ORDER),
        listOf(
            EARLIEST_CREATED_AT,
            "v2_id = CASE WHEN v2_id IS NULL THEN excluded.v2_id WHEN excluded.v2_id IS NULL THEN v2_id ELSE MAX(v2_id, excluded.v2_id) END",
        ),
    )

    private val feeds = upsert(
        table("feeds"),
        listOf(
            SyncLww.FEED_CONTENT, SyncLww.FEED_FOLDER, SyncLww.FEED_SORT_ORDER,
            SyncLww.FEED_CUSTOM_TITLE, SyncLww.FEED_DELETION,
        ),
        listOf(EARLIEST_CREATED_AT),
    )

    private val tags = upsert(
        table("tags"),
        listOf(SyncLww.TAG_EXISTENCE, SyncLww.TAG_NAME, SyncLww.TAG_COLOR, SyncLww.TAG_SORT_ORDER),
        listOf(
            EARLIEST_CREATED_AT,
            "v2_id = CASE WHEN v2_id IS NULL THEN excluded.v2_id WHEN excluded.v2_id IS NULL THEN v2_id ELSE MAX(v2_id, excluded.v2_id) END",
        ),
    )

    private val articles = upsert(
        table("articles"),
        listOf(SyncLww.ARTICLE_BODY, SyncLww.ARTICLE_READ, SyncLww.ARTICLE_RETENTION),
        listOf(
            EARLIEST_CREATED_AT,
            "cached_at = MAX(cached_at, excluded.cached_at)",
            "updated_at = MAX(updated_at, excluded.updated_at)",
        ),
    )

    private val feedTags = upsert(table("feed_tags"), listOf(SyncLww.FEED_TAG))

    private val globalSettings = upsert(table("global_settings"), listOf(SyncLww.GLOBAL_SETTING))

    /**
     * Unions `src`'s identity edges into `main`'s: its edge tables, and the `(v2_id, id)` of its
     * tag / folder rows that carry a `v2_id` (see the class comment). The edge tables' primary key is
     * the whole row, so `INSERT OR IGNORE` keeps one copy of each edge.
     */
    val edges: List<String> = listOf("folders" to "folder_v2_edges", "tags" to "tag_v2_edges").flatMap { (rows, edgeTable) ->
        listOf(
            "INSERT OR IGNORE INTO main.$edgeTable (v2_id, id) SELECT v2_id, id FROM src.$edgeTable",
            "INSERT OR IGNORE INTO main.$edgeTable (v2_id, id) SELECT v2_id, id FROM src.$rows WHERE v2_id IS NOT NULL",
        )
    }

    /**
     * Records into `main.feed_edges` every pair of distinct feed ids sharing a `url` — between `src`'s
     * feeds and the accumulator's, and within `src`'s — normalized `a < b` (see the class comment). Run
     * before [feeds], which may change the accumulator's `url`s. A collision within the accumulator was
     * recorded when its second feed was folded in (the accumulator starts empty).
     */
    val feedUrlEdges: List<String> = listOf(
        "INSERT OR IGNORE INTO main.feed_edges (a, b) SELECT MIN(s.id, m.id), MAX(s.id, m.id) " +
            "FROM src.feeds s JOIN main.feeds m ON m.url = s.url AND m.id <> s.id",
        "INSERT OR IGNORE INTO main.feed_edges (a, b) SELECT s1.id, s2.id " +
            "FROM src.feeds s1 JOIN src.feeds s2 ON s2.url = s1.url AND s1.id < s2.id",
    )

    /**
     * Unions `src`'s feed identity edges into `main`'s. Normalized `a < b` (and an edge of an id with
     * itself dropped), so a file's edge is kept once whichever way round it was written.
     */
    val feedEdges: String =
        "INSERT OR IGNORE INTO main.feed_edges (a, b) SELECT MIN(a, b), MAX(a, b) FROM src.feed_edges WHERE a <> b"

    /** Folds `src`'s synced rows and identity edges into `main`'s. */
    val all: List<String> =
        listOf(folders) + feedUrlEdges + listOf(feeds, tags, articles, feedTags, globalSettings) + edges + feedEdges

    /**
     * Combines `src`'s coverage into `main`'s: the vector takes the component-wise maximum and the
     * covered names their union (each with its earliest known creation time). Extending the vector over contiguous covered deltas is
     * [TransportVector.extend]'s job, done after these.
     */
    val combineCoverage: List<String> = listOf(
        """
            INSERT INTO main.ks_vector (device, seq)
            SELECT device, seq FROM src.ks_vector WHERE true
            ON CONFLICT(device) DO UPDATE SET seq = MAX(seq, excluded.seq);
        """.trimIndent(),
        """
            INSERT INTO main.ks_covered (name, created_at)
            SELECT name, created_at FROM src.ks_covered WHERE true
            ON CONFLICT(name) DO UPDATE SET created_at = $EARLIEST_KNOWN_COVERED_AT;
        """.trimIndent(),
    )

    /**
     * The `ON CONFLICT` assignment that keeps the earliest known creation time of a covered delta
     * (NULL meaning unknown).
     */
    const val EARLIEST_KNOWN_COVERED_AT: String =
        "CASE WHEN created_at IS NULL THEN excluded.created_at WHEN excluded.created_at IS NULL THEN created_at " +
            "ELSE MIN(created_at, excluded.created_at) END"
}
