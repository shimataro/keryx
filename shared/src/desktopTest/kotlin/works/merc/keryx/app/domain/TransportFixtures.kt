package works.merc.keryx.app.domain

import works.merc.keryx.app.fileDb
import works.merc.keryx.app.platform.TransportFiles
import java.io.File
import java.sql.Connection
import java.sql.DriverManager

/**
 * Builds transport files row by row (every synced column spelled out) and merges / folds / dumps
 * them, for the merge-algebra and transport-file tests.
 */
internal object TransportFixtures {
    const val GEN = "0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e"
    const val DEV_A = "a1b2c3d4-e5f6-4789-8abc-def012345678"
    const val DEV_B = "b1b2c3d4-e5f6-4789-8abc-def012345678"
    const val DEV_C = "c1b2c3d4-e5f6-4789-8abc-def012345678"

    fun deltaName(device: String, seq: Long, hash: String = "0123456789ab", createdAt: Long = 1_000) =
        TransportFileName(GEN, TransportFileKind.DELTA, device, seq, createdAt, hash)

    fun baseName(device: String, n: Long, hash: String = "0123456789ab", createdAt: Long = 1_000) =
        TransportFileName(GEN, TransportFileKind.BASE, device, n, createdAt, hash)

    /** A new transport file named [name] (its `ks_meta.created_at` the name's) holding [rows] (INSERT statements). */
    fun transportFile(name: TransportFileName, vararg rows: String): File {
        val file = File.createTempFile("ks-transport-", ".db").apply { delete(); deleteOnExit() }
        TransportFiles.createEmpty(file.absolutePath, TransportMeta(name.generation, name.kind, name.device, name.number, name.createdAt))
        exec(file, *rows)
        return file
    }

    /** An empty base accumulator created at [createdAt]. */
    fun accumulator(createdAt: Long = 1_000): File = transportFile(baseName(DEV_A, 1, createdAt = createdAt))

    /** A fresh local database of the current schema, its SQLDelight driver already closed. */
    fun localDb(vararg rows: String): File {
        val (file, driver, _) = fileDb()
        driver.close()
        exec(file, *rows)
        return file
    }

    fun exec(file: File, vararg statements: String) {
        connect(file).use { conn -> conn.createStatement().use { st -> statements.forEach(st::execute) } }
    }

    fun connect(file: File): Connection = DriverManager.getConnection("jdbc:sqlite:${file.absolutePath}")

    /**
     * Merges the transport file [cloud] into the local database [local] with [MergeSql.transport] on one
     * connection, in one transaction, foreign keys on — the statements as `DatabaseMerger.merge`
     * runs them, without its pre-delta migration step (which does not apply to a transport file).
     */
    fun merge(local: File, cloud: File) {
        connect(local).use { conn ->
            conn.createStatement().use { st ->
                st.execute("PRAGMA foreign_keys=ON")
                st.execute("ATTACH DATABASE '${cloud.absolutePath.replace("'", "''")}' AS cloud")
            }
            conn.autoCommit = false
            conn.createStatement().use { st -> MergeSql.transport.forEach(st::execute) }
            conn.commit()
            conn.autoCommit = true
            conn.createStatement().use { it.execute("DETACH DATABASE cloud") }
        }
    }

    /** Folds [inputs] into a new accumulator, in order. */
    fun fold(vararg inputs: Pair<File, TransportFileName>): File =
        accumulator().also { acc -> inputs.forEach { (file, name) -> TransportFiles.fold(acc.absolutePath, file.absolutePath, name) } }

    /**
     * Every synced column of every synced table (plus the coverage tables when [coverage]), ordered by
     * key. With [local] (a local database only), also the merge's own state that later merges read:
     * `ks_feed_folder_raw`, each feed's raw folder assignment (what its resolved folder comes from).
     */
    fun dump(file: File, coverage: Boolean = false, local: Boolean = false): Map<String, List<String>> = connect(file).use { conn ->
        fun rows(sql: String, columnCount: Int): List<String> = conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList {
                    while (rs.next()) add((1..columnCount).joinToString("|") { "${rs.getObject(it)}" })
                }
            }
        }
        val tables = TransportSchema.SYNCED_TABLES + if (coverage) TransportSchema.META_TABLES.filter { it.name != "ks_meta" } else emptyList()
        val synced = tables.associate { table ->
            val sql = "SELECT ${table.columnNames.joinToString(", ")} FROM ${table.name} ORDER BY ${table.primaryKey.joinToString(", ")}"
            table.name to rows(sql, table.columnNames.size)
        }
        if (!local) synced
        else synced + ("ks_feed_folder_raw" to rows("SELECT feed_id, folder_updated_at, folder_id FROM ks_feed_folder_raw ORDER BY feed_id", 3))
    }

    /** Rows of [sql] on [file], each as its columns joined by `|` (NULL as `null`). */
    fun rows(file: File, sql: String): List<String> = connect(file).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).joinToString("|") { "${rs.getObject(it)}" }) }
            }
        }
    }

    /**
     * [dump] of a local database (raw folder assignments included) plus the feed identity state a merge
     * leaves behind: `ks_feed_edge` and `ks_id_remap`. Device-local `feed_fetch_state` is not part of it
     * (it follows which side held a row, so it legitimately differs by history).
     */
    fun identityState(file: File): Map<String, List<String>> = dump(file, local = true) + mapOf(
        "ks_feed_edge" to rows(file, "SELECT a, b FROM ks_feed_edge ORDER BY 1, 2"),
        "ks_id_remap" to rows(file, "SELECT kind, old_id, new_id FROM ks_id_remap ORDER BY 1, 2, 3"),
    )

    /** Article ids registered in `ks_search_pending` of a local database. */
    fun searchPendingIds(file: File): Set<String> = connect(file).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT a.id FROM ks_search_pending p JOIN articles a ON a.rowid = p.rowid").use { rs ->
                buildSet { while (rs.next()) add(rs.getString(1)) }
            }
        }
    }

    private fun lit(value: Any?): String = when (value) {
        null -> "NULL"
        is Number -> value.toString()
        else -> "'${value.toString().replace("'", "''")}'"
    }

    private fun insert(table: String, values: Map<String, Any?>): String =
        "INSERT INTO $table (${values.keys.joinToString(", ")}) VALUES (${values.values.joinToString(", ", transform = ::lit)})"

    fun folder(
        id: String, name: String, updatedAt: Long = 100, createdAt: Long = 100, deletedAt: Long? = null,
        nameAt: Long? = updatedAt, sortOrder: Long = 0, sortAt: Long? = updatedAt, v2Id: String? = null,
    ) = insert(
        "folders",
        mapOf(
            "id" to id, "name" to name, "sort_order" to sortOrder, "deleted_at" to deletedAt, "updated_at" to updatedAt,
            "created_at" to createdAt, "name_updated_at" to nameAt, "sort_order_updated_at" to sortAt, "v2_id" to v2Id,
        ),
    )

    fun tag(
        id: String, name: String, updatedAt: Long = 100, createdAt: Long = 100, deletedAt: Long? = null,
        nameAt: Long? = updatedAt, color: String? = null, colorAt: Long? = updatedAt, sortOrder: Long = 0,
        sortAt: Long? = updatedAt, v2Id: String? = null,
    ) = insert(
        "tags",
        mapOf(
            "id" to id, "name" to name, "color" to color, "sort_order" to sortOrder, "deleted_at" to deletedAt,
            "updated_at" to updatedAt, "created_at" to createdAt, "name_updated_at" to nameAt,
            "color_updated_at" to colorAt, "sort_order_updated_at" to sortAt, "v2_id" to v2Id,
        ),
    )

    fun feed(
        id: String, url: String = "https://feed/$id", title: String = "Feed $id", updatedAt: Long = 100, createdAt: Long = 100,
        folderId: String? = null, folderAt: Long? = null, sortOrder: Long = 0, sortAt: Long? = null,
        customTitle: String? = null, customAt: Long? = null, deletedAt: Long? = null, deletedUpdatedAt: Long? = null,
    ) = insert(
        "feeds",
        mapOf(
            "id" to id, "url" to url, "site_url" to null, "title" to title, "description" to null, "favicon_url" to null,
            "custom_title" to customTitle, "folder_id" to folderId, "deleted_at" to deletedAt, "updated_at" to updatedAt,
            "created_at" to createdAt, "sort_order" to sortOrder, "folder_updated_at" to folderAt,
            "sort_order_updated_at" to sortAt, "custom_title_updated_at" to customAt, "deleted_updated_at" to deletedUpdatedAt,
        ),
    )

    fun article(
        id: String, feedId: String, guid: String = "g-$id", title: String = "Article $id", updatedAt: Long = 100,
        createdAt: Long = 100, content: String? = null, summary: String? = null, isRead: Long = 0, readAt: Long? = null,
        isStarred: Long = 0, starredAt: Long? = null, cachedAt: Long = 100, deletedAt: Long? = null, deletedUpdatedAt: Long? = null,
        bodyAt: Long? = updatedAt,
    ) = insert(
        "articles",
        mapOf(
            "id" to id, "feed_id" to feedId, "guid" to guid, "url" to "https://a/$id", "title" to title,
            "summary" to summary, "content" to content, "author" to null, "published_at" to 50, "thumbnail_url" to null,
            "is_read" to isRead, "read_at" to readAt, "is_starred" to isStarred, "starred_at" to starredAt,
            "cached_at" to cachedAt, "updated_at" to updatedAt, "created_at" to createdAt,
            "deleted_at" to deletedAt, "deleted_updated_at" to deletedUpdatedAt, "body_updated_at" to bodyAt,
        ),
    )

    fun feedTag(feedId: String, tagId: String, updatedAt: Long = 100, deletedAt: Long? = null) =
        insert("feed_tags", mapOf("feed_id" to feedId, "tag_id" to tagId, "deleted_at" to deletedAt, "updated_at" to updatedAt))

    fun setting(key: String, value: String, updatedAt: Long = 100) =
        insert("global_settings", mapOf("key" to key, "value" to value, "updated_at" to updatedAt))
}
