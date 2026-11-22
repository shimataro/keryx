package works.merc.keryx.app.domain

import works.merc.keryx.app.domain.ColumnKind.BOOLEAN
import works.merc.keryx.app.domain.ColumnKind.COUNT
import works.merc.keryx.app.domain.ColumnKind.ID
import works.merc.keryx.app.domain.ColumnKind.INTEGER
import works.merc.keryx.app.domain.ColumnKind.STAMP
import works.merc.keryx.app.domain.ColumnKind.TEXT

/**
 * The schema of a transport file — a delta or a base (see "Transport schema" in
 * `docs/sync-architecture.md`).
 *
 * A transport file holds the six synced tables with their synced columns only (no `feeds` fetch
 * state, no `articles.search_text`), with primary keys and NOT NULL but **no UNIQUE or foreign-key
 * constraints** — a base folds rows from several devices, and two devices can legitimately hold
 * different ids for the same tag name — plus three metadata tables: `ks_meta`, `ks_vector` and
 * `ks_covered`. `KeryxDatabase.Schema.migrate` is never run on a transport file.
 *
 * Pure data; the DDL is generated from the same [TableSpec]s the validation reads, so the two
 * cannot drift.
 */
object TransportSchema {

    /** The transport format this build writes (`PRAGMA user_version` of a transport file). */
    const val VERSION: Long = 1L

    private val FOLDERS_V1 = TableSpec(
        "folders",
        listOf(
            req("id", ID), req("name", TEXT), req("sort_order", INTEGER), opt("deleted_at", STAMP),
            req("updated_at", STAMP), req("created_at", STAMP), opt("name_updated_at", STAMP),
            opt("sort_order_updated_at", STAMP),
        ),
        primaryKey = listOf("id"),
    )

    private val FEEDS_V1 = TableSpec(
        "feeds",
        listOf(
            req("id", ID), req("url", TEXT), opt("site_url", TEXT), req("title", TEXT), opt("description", TEXT),
            opt("favicon_url", TEXT), opt("custom_title", TEXT), opt("folder_id", ID), opt("deleted_at", STAMP),
            req("updated_at", STAMP), req("created_at", STAMP), req("sort_order", INTEGER),
            opt("folder_updated_at", STAMP), opt("sort_order_updated_at", STAMP),
            opt("custom_title_updated_at", STAMP), opt("deleted_updated_at", STAMP),
        ),
        primaryKey = listOf("id"),
    )

    private val TAGS_V1 = TableSpec(
        "tags",
        listOf(
            req("id", ID), req("name", TEXT), opt("color", TEXT), req("sort_order", INTEGER), opt("deleted_at", STAMP),
            req("updated_at", STAMP), req("created_at", STAMP), opt("name_updated_at", STAMP),
            opt("color_updated_at", STAMP), opt("sort_order_updated_at", STAMP),
        ),
        primaryKey = listOf("id"),
    )

    private val ARTICLES_V1 = TableSpec(
        "articles",
        listOf(
            req("id", ID), req("feed_id", ID), req("guid", TEXT), req("url", TEXT), req("title", TEXT),
            opt("summary", TEXT), opt("content", TEXT), opt("author", TEXT), opt("published_at", INTEGER),
            opt("thumbnail_url", TEXT), req("is_read", BOOLEAN), opt("read_at", STAMP), req("is_starred", BOOLEAN),
            opt("starred_at", STAMP), req("cached_at", STAMP), req("updated_at", STAMP), req("created_at", STAMP),
            opt("deleted_at", STAMP), opt("deleted_updated_at", STAMP), opt("body_updated_at", STAMP),
        ),
        primaryKey = listOf("id"),
    )

    private val FEED_TAGS_V1 = TableSpec(
        "feed_tags",
        listOf(req("feed_id", ID), req("tag_id", ID), opt("deleted_at", STAMP), req("updated_at", STAMP)),
        primaryKey = listOf("feed_id", "tag_id"),
    )

    private val GLOBAL_SETTINGS_V1 = TableSpec(
        "global_settings",
        listOf(req("key", ID), req("value", TEXT), req("updated_at", STAMP)),
        primaryKey = listOf("key"),
    )

    /** `ks_meta(key, value)`: the file's identity; keys are [MetaKeys]. */
    private val KS_META = TableSpec("ks_meta", listOf(req("key", ID), req("value", TEXT)), primaryKey = listOf("key"))

    /**
     * `ks_vector(device, seq)` (filled in bases only): per device, the highest `seq` such that every
     * delta `1..seq` of that device is covered.
     */
    private val KS_VECTOR = TableSpec("ks_vector", listOf(req("device", ID), req("seq", COUNT)), primaryKey = listOf("device"))

    /**
     * `ks_covered(name, created_at)` (filled in bases only): the delta file names this base folded
     * that its vector does not imply, each with the delta's creation time (its `ks_meta.created_at`;
     * NULL when unknown) — what garbage collection and vector extension measure a delta's age by.
     */
    private val KS_COVERED = TableSpec("ks_covered", listOf(req("name", ID), opt("created_at", STAMP)), primaryKey = listOf("name"))

    /**
     * The six synced tables of transport version 1, in FK-safe order. They are exactly local schema
     * version 3's synced columns minus `articles.search_text` (pinned by a test).
     */
    val SYNCED_TABLES_V1: List<TableSpec> =
        listOf(FOLDERS_V1, FEEDS_V1, TAGS_V1, ARTICLES_V1, FEED_TAGS_V1, GLOBAL_SETTINGS_V1)

    /** The metadata tables, present (possibly empty) in every transport file. */
    val META_TABLES: List<TableSpec> = listOf(KS_META, KS_VECTOR, KS_COVERED)

    /**
     * Every table a transport file of a given version must have, by `user_version`. A version
     * missing here is undetermined, never "corrupt" (an unregistered version must not lead to a
     * destructive reset).
     */
    val EXPECTED_TABLES: Map<Long, List<TableSpec>> = mapOf(
        1L to SYNCED_TABLES_V1 + META_TABLES,
    )

    /** The `CREATE TABLE` statements of a fresh transport file of [VERSION]. */
    val ddl: List<String> = EXPECTED_TABLES.getValue(VERSION).map(::createTable)

    /** One transport migration step: [statements] bring a file from [from] to [to]. */
    data class Migration(val from: Long, val to: Long, val statements: List<String>) {
        init {
            require(to > from) { "A migration must move forward" }
        }
    }

    /**
     * Transport migrations, in order. Empty while version 1 is the only version: a later version adds
     * its `from -> to` step here, together with its [EXPECTED_TABLES] entry.
     */
    val MIGRATIONS: List<Migration> = emptyList()

    /**
     * The chain of [MIGRATIONS] that brings a file from [from] up to [to] (empty when they are
     * equal), or `null` when no contiguous chain exists — undetermined, never "corrupt".
     */
    fun migrationPath(from: Long, to: Long = VERSION, migrations: List<Migration> = MIGRATIONS): List<Migration>? {
        if (from > to) return null
        val path = mutableListOf<Migration>()
        var current = from
        while (current < to) {
            val step = migrations.firstOrNull { it.from == current && it.to <= to } ?: return null
            path += step
            current = step.to
        }
        return path
    }

    /** `ks_meta` keys. */
    object MetaKeys {
        /** The transport format version the file was written in. */
        const val FORMAT = "format"

        /** The generation id (the `<gen>` of the file name). */
        const val GENERATION = "generation"

        /** The writing device's sync identity (the `<dev>` of the file name). */
        const val DEVICE = "device"

        /** When the file was created (Unix millis). */
        const val CREATED_AT = "created_at"

        /** `delta` or `base` ([TransportFileKind.metaValue]). */
        const val KIND = "kind"

        /** The delta's `seq` or the base's `n` (the number of the file name). */
        const val NUMBER = "number"

        /** Every key a valid file carries. */
        val ALL: List<String> = listOf(FORMAT, GENERATION, DEVICE, CREATED_AT, KIND, NUMBER)
    }

    private fun createTable(table: TableSpec): String {
        val columns = table.columns.joinToString(",\n    ") { column ->
            "\"${column.name}\" ${column.kind.declaredType}" + if (column.notNull) " NOT NULL" else ""
        }
        val primaryKey = table.primaryKey.joinToString(", ") { "\"$it\"" }
        return "CREATE TABLE \"${table.name}\" (\n    $columns,\n    PRIMARY KEY ($primaryKey)\n)"
    }
}

/** Whether a transport file is a delta or a base. */
enum class TransportFileKind(
    /** The kind's letter in the file name (`ks-<gen>-<token>-…`). */
    val token: String,
    /** The kind's `ks_meta.kind` value. */
    val metaValue: String,
) {
    DELTA("d", "delta"),
    BASE("b", "base"),
    ;

    companion object {
        fun fromToken(token: String): TransportFileKind? = entries.firstOrNull { it.token == token }
        fun fromMetaValue(value: String): TransportFileKind? = entries.firstOrNull { it.metaValue == value }
    }
}

/**
 * A transport file's name, `ks-<gen>-d-<dev>-<seq>-<createdAt>-<hash12>.db.gz` (delta) or
 * `ks-<gen>-b-<dev>-<n>-<createdAt>-<hash12>.db.gz` (base).
 *
 * `<gen>` and `<dev>` are lowercase UUIDs (8-4-4-4-12 hex digits), the number is a positive decimal
 * without leading zeros (at most 18 digits, so it always fits a Long), `<createdAt>` is the file's
 * `ks_meta.created_at` (Unix millis, a non-negative decimal without leading zeros, at most 18
 * digits) — so a file's age is known from its name alone ([BaseCoverage.covers]) — and `<hash12>` is
 * 12 lowercase hex digits. Construction enforces the same rules [parse] does, so a built name always
 * parses back, and only names that passed them are ever embedded in SQL.
 */
data class TransportFileName(
    val generation: String,
    val kind: TransportFileKind,
    val device: String,
    val number: Long,
    /** The file's creation time (its `ks_meta.created_at`), Unix millis. */
    val createdAt: Long,
    val hash12: String,
) {
    init {
        require(isSyncId(generation)) { "Not a generation id" }
        require(isSyncId(device)) { "Not a device id" }
        require(number in 1..MAX_NUMBER) { "File number out of range" }
        require(createdAt in 0..MAX_NUMBER) { "Creation time out of range" }
        require(HASH12.matches(hash12)) { "Not a 12-digit lowercase hex hash" }
    }

    /** The file name. */
    val fileName: String get() = "ks-$generation-${kind.token}-$device-$number-$createdAt-$hash12.db.gz"

    override fun toString(): String = fileName

    companion object {
        private const val UUID = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"
        private val SYNC_ID = Regex("^$UUID$")
        private val HASH12 = Regex("^[0-9a-f]{12}$")
        private val NAME = Regex("^ks-($UUID)-([db])-($UUID)-([1-9][0-9]{0,17})-(0|[1-9][0-9]{0,17})-([0-9a-f]{12})\\.db\\.gz$")

        /** The largest number or creation time a name may carry (18 decimal digits). */
        private const val MAX_NUMBER = 999_999_999_999_999_999L

        /** Whether [value] is a well-formed generation or device id (a lowercase UUID). */
        fun isSyncId(value: String): Boolean = SYNC_ID.matches(value)

        /** The listing prefix of every transport file of [generation]. */
        fun prefixOf(generation: String): String {
            require(isSyncId(generation)) { "Not a generation id" }
            return "ks-$generation-"
        }

        /** Parses [name], or returns `null` for anything that is not exactly a transport file name. */
        fun parse(name: String): TransportFileName? {
            val match = NAME.matchEntire(name) ?: return null
            val (generation, token, device, number, createdAt, hash12) = match.destructured
            return TransportFileName(
                generation = generation,
                kind = TransportFileKind.fromToken(token) ?: return null,
                device = device,
                number = number.toLongOrNull() ?: return null,
                createdAt = createdAt.toLongOrNull() ?: return null,
                hash12 = hash12,
            )
        }
    }
}

/** The identity written into a new transport file's `ks_meta` ([works.merc.keryx.app.platform.TransportFiles.createEmpty]). */
data class TransportMeta(
    val generation: String,
    val kind: TransportFileKind,
    val device: String,
    val number: Long,
    val createdAt: Long,
) {
    init {
        require(TransportFileName.isSyncId(generation)) { "Not a generation id" }
        require(TransportFileName.isSyncId(device)) { "Not a device id" }
        require(number >= 1) { "File number out of range" }
        require(createdAt >= 0) { "Negative creation time" }
    }

    /** The name of the file carrying this meta whose uncompressed content hashes to [hash12]. */
    fun fileName(hash12: String): TransportFileName = TransportFileName(generation, kind, device, number, createdAt, hash12)

    /** The `ks_meta` rows, key to value. */
    val rows: Map<String, String>
        get() = mapOf(
            TransportSchema.MetaKeys.FORMAT to TransportSchema.VERSION.toString(),
            TransportSchema.MetaKeys.GENERATION to generation,
            TransportSchema.MetaKeys.DEVICE to device,
            TransportSchema.MetaKeys.CREATED_AT to createdAt.toString(),
            TransportSchema.MetaKeys.KIND to kind.metaValue,
            TransportSchema.MetaKeys.NUMBER to number.toString(),
        )
}

/** `ks_vector` arithmetic for folding bases (see "Bases and Garbage Collection"). */
object TransportVector {
    /** Component-wise maximum of two vectors. */
    fun combine(a: Map<String, Long>, b: Map<String, Long>): Map<String, Long> =
        (a.keys + b.keys).associateWith { maxOf(a[it] ?: 0L, b[it] ?: 0L) }

    /**
     * Extends [vector] over the deltas in [covered] that continue it without a gap: for each device,
     * while the delta `vector[device] + 1` is covered, the component moves up by one. Covered deltas
     * past a gap leave the component where it is (they still count as covered names).
     */
    fun extend(vector: Map<String, Long>, covered: Collection<TransportFileName>): Map<String, Long> {
        val seqsByDevice = covered.filter { it.kind == TransportFileKind.DELTA }
            .groupBy({ it.device }, { it.number })
            .mapValues { (_, seqs) -> seqs.toSet() }
        val result = vector.toMutableMap()
        for ((device, seqs) in seqsByDevice) {
            var seq = result[device] ?: 0L
            while ((seq + 1) in seqs) seq++
            if (seq > 0) result[device] = seq
        }
        return result
    }
}
