package works.merc.keryx.app.domain

/**
 * The SQL [works.merc.keryx.app.platform.DeltaExporter] runs to build a delta or a full send — see
 * "Sending" in `docs/sync-architecture.md`.
 *
 * Every statement runs on one connection whose `main` is the new, empty transport file
 * ([works.merc.keryx.app.platform.TransportFiles.createEmpty]) with the source database — the live
 * database for a delta, a `VACUUM INTO` copy of it for a full send — attached as [SOURCE] and, for a
 * full send, the file to leave out attached as [EXCEPT]. Only the transport columns
 * ([TransportSchema.SYNCED_TABLES_V1]) are copied, listed explicitly, so `search_text`, the fetch
 * state and the bookkeeping never leave the device.
 *
 * Both kinds end with [closureStatements]: every row a written row references (a feed's folder, an
 * article's feed, a `feed_tags` row's feed and tag) is written too, so the file passes "Validating
 * Cloud Files" check 9 and the receiving merge never finds a referenced row missing.
 *
 * Pure data, so both the statements and their order are the same on every platform.
 */
object DeltaExportSql {

    /** The alias the source database is attached under. */
    const val SOURCE = "src"

    /** The alias a full send's except-file is attached under. */
    const val EXCEPT = "ex"

    private val tables: Map<String, TableSpec> = TransportSchema.SYNCED_TABLES_V1.associateBy { it.name }

    private fun table(name: String): TableSpec = tables.getValue(name)

    /** `ATTACH` of [path] as [alias]. SQLite has no bind parameter for it, so quotes are escaped. */
    fun attach(path: String, alias: String): String = "ATTACH DATABASE '${path.replace("'", "''")}' AS $alias"

    fun detach(alias: String): String = "DETACH DATABASE $alias"

    /**
     * The outbox high-water mark `S` of the source: `MAX(sync_outbox.seq)`, NULL (no row) when the
     * outbox is empty. Read inside the export's transaction, so it and the rows come from one snapshot.
     */
    const val HIGH_WATER_MARK_QUERY: String = "SELECT MAX(seq) FROM $SOURCE.sync_outbox"

    /** The row count of [table] in the transport file. */
    fun countQuery(table: String): String {
        require(table in tables) { "Not a synced table: $table" }
        return "SELECT COUNT(*) FROM main.$table"
    }

    /** The synced tables, in the order [countQuery] is reported. */
    val tableNames: List<String> = TransportSchema.SYNCED_TABLES_V1.map { it.name }

    private fun columns(table: TableSpec, alias: String? = null): String =
        table.columnNames.joinToString(", ") { if (alias == null) it else "$alias.$it" }

    /** The keys of [tbl]'s outbox entries up to [s] (`pk`; [column] picks `pk2` for `feed_tags`' tag). */
    private fun outboxKeys(tbl: String, s: Long, column: String = "pk"): String =
        "SELECT $column FROM $SOURCE.sync_outbox WHERE tbl = '$tbl' AND seq <= $s"

    /**
     * An ordinary delta up to the high-water mark [s]: the **current** row of every key with an
     * outbox entry `seq <= s`, then [closureStatements]. An article with no `heavy = 1` entry up to
     * [s] is written as a light row — `content` and `summary` NULL, every other column (its
     * `body_updated_at` included) as stored: [SyncLww.ARTICLE_BODY] ranks any body above none
     * before comparing stamps, so a light row never replaces a body.
     *
     * A key whose row does not exist (never expected: synced rows are never physically deleted) is
     * simply not written.
     */
    fun deltaStatements(s: Long): List<String> {
        require(s >= 0) { "Negative high-water mark" }
        return buildList {
            for (name in listOf("folders", "feeds", "tags")) {
                val spec = table(name)
                add(
                    "INSERT INTO main.$name (${columns(spec)}) " +
                        "SELECT ${columns(spec)} FROM $SOURCE.$name WHERE id IN (${outboxKeys(name, s)})",
                )
            }
            add(deltaArticles(s))
            val feedTags = table("feed_tags")
            add(
                "INSERT INTO main.feed_tags (${columns(feedTags)}) " +
                    "SELECT ${columns(feedTags, "ft")} FROM $SOURCE.feed_tags ft " +
                    "JOIN (SELECT DISTINCT pk, pk2 FROM $SOURCE.sync_outbox WHERE tbl = 'feed_tags' AND seq <= $s) k " +
                    "ON ft.feed_id = k.pk AND ft.tag_id = k.pk2",
            )
            val settings = table("global_settings")
            add(
                "INSERT INTO main.global_settings (${columns(settings)}) " +
                    "SELECT ${columns(settings)} FROM $SOURCE.global_settings WHERE key IN (${outboxKeys("global_settings", s)})",
            )
            addAll(closureStatements)
        }
    }

    private fun deltaArticles(s: Long): String {
        val spec = table("articles")
        val selected = spec.columnNames.joinToString(", ") {
            when (it) {
                "content", "summary" -> "CASE WHEN k.heavy = 1 THEN a.$it ELSE NULL END AS $it"
                else -> "a.$it"
            }
        }
        // One aggregate over the outbox rather than an EXISTS per article: the outbox has no index on
        // (tbl, pk).
        return "INSERT INTO main.articles (${columns(spec)}) " +
            "SELECT $selected FROM " +
            "(SELECT pk, MAX(heavy) AS heavy FROM $SOURCE.sync_outbox WHERE tbl = 'articles' AND seq <= $s GROUP BY pk) k " +
            "JOIN $SOURCE.articles a ON a.id = k.pk"
    }

    /**
     * A full send: every synced row of the source, with bodies, then [closureStatements]. With
     * [withExcept], a row identical on every transport column to the [EXCEPT] file's is left out
     * (`EXCEPT` compares NULLs as equal) — the receivers already get it from that file.
     */
    fun fullStatements(withExcept: Boolean): List<String> = buildList {
        for (spec in TransportSchema.SYNCED_TABLES_V1) {
            val except = if (withExcept) " EXCEPT SELECT ${columns(spec)} FROM $EXCEPT.${spec.name}" else ""
            add("INSERT INTO main.${spec.name} (${columns(spec)}) SELECT ${columns(spec)} FROM $SOURCE.${spec.name}$except")
        }
        addAll(closureStatements)
    }

    /**
     * Writes every row the written rows reference and the file does not hold yet: feeds (of the
     * articles and `feed_tags` rows), tags (of the `feed_tags` rows), then folders (of the feeds,
     * including those just added) — in that order, since a feed added here can bring its folder.
     */
    val closureStatements: List<String> = run {
        val feeds = table("feeds")
        val tags = table("tags")
        val folders = table("folders")
        listOf(
            "INSERT INTO main.feeds (${columns(feeds)}) SELECT ${columns(feeds)} FROM $SOURCE.feeds " +
                "WHERE id IN (SELECT feed_id FROM main.articles UNION SELECT feed_id FROM main.feed_tags) " +
                "AND id NOT IN (SELECT id FROM main.feeds)",
            "INSERT INTO main.tags (${columns(tags)}) SELECT ${columns(tags)} FROM $SOURCE.tags " +
                "WHERE id IN (SELECT tag_id FROM main.feed_tags) AND id NOT IN (SELECT id FROM main.tags)",
            "INSERT INTO main.folders (${columns(folders)}) SELECT ${columns(folders)} FROM $SOURCE.folders " +
                "WHERE id IN (SELECT folder_id FROM main.feeds WHERE folder_id IS NOT NULL) " +
                "AND id NOT IN (SELECT id FROM main.folders)",
        )
    }

    /**
     * Run last: tables a platform's connection adds to every file it opens for writing — requery's
     * `android_metadata`, which would carry the device's locale into the cloud — are dropped from the
     * transport file. A no-op elsewhere.
     */
    val platformCleanupStatements: List<String> = listOf("DROP TABLE IF EXISTS main.android_metadata")

    /**
     * Check 9 on the transport file itself ([SyncValidationSql.closureChecks], qualified to `main`):
     * a dangling reference left after [closureStatements] means the source holds a reference to a
     * row it does not have, and receivers would reject the file as corrupt.
     */
    val closureChecks: List<SyncValidationSql.ClosureCheck> = SyncValidationSql.closureChecks.map { check ->
        check.copy(query = qualifyMain(check.query))
    }

    private fun qualifyMain(query: String): String =
        listOf("feeds", "folders", "articles", "feed_tags", "tags").fold(query) { sql, name ->
            sql.replace(Regex("\\bFROM $name\\b"), "FROM main.$name")
        }
}
