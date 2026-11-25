package works.merc.keryx.app.domain

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver

/** The `ks_id_remap.kind` / `ks_v2_edge.kind` of a tag row's record. */
internal const val ID_REMAP_KIND_TAG = "tag"

/** The `ks_id_remap.kind` / `ks_v2_edge.kind` of a folder row's record. */
internal const val ID_REMAP_KIND_FOLDER = "folder"

/**
 * The schema-migration callbacks every migration of this app's database runs: the local factories
 * (desktop's `DatabaseDriverFactory.createDriver`, Android's `AndroidSqliteDriver.Callback`, Apple's
 * `NativeSqliteDriver`) and `DatabaseMerger`'s migration of a downloaded pre-delta cloud file, on
 * every platform. Version 3's final step re-keys tags and folders onto their name-derived ids; see
 * [rekeyTagAndFolderIds].
 */
val KERYX_SCHEMA_CALLBACKS: Array<AfterVersion> = arrayOf(
    AfterVersion(2) { driver -> rekeyTagAndFolderIds(driver) },
)

/**
 * Re-keys every tag and folder onto the id its name derives from ([IdGenerator.tagId] /
 * [IdGenerator.folderId]) — the final step of the v2->v3 migration, run by [KERYX_SCHEMA_CALLBACKS]
 * after `2.sqm` has rebuilt the two tables (per-field stamps in, `UNIQUE(name)` out).
 *
 * Before version 3 a row's id was a random UUIDv4, so two devices that created the same name held
 * different ids and the merge had to match rows by name. With the id derived from the name, the
 * merge matches by id alone. The version-4 and version-5 nibbles differ, so no existing id can
 * equal the derived one; and a version-2 database (where names were still UNIQUE) cannot hold two
 * rows of the same name, so its rows' derived ids are distinct and the copy-insert below cannot
 * collide.
 *
 * For every row whose id is not already the derived one: the row is copied under the new id, its
 * children (`feed_tags.tag_id`, or `feeds.folder_id` and the feeds' raw folder assignments in
 * `ks_feed_folder_raw`, which `2.sqm` has just filled) are moved, the old row is deleted, and the
 * move is recorded in `ks_id_remap` ([IdRemapSql.record]) so `SettingsRepository` can rewrite the
 * same ids in `local_settings.json` at startup. The row's `v2_id` (set by `2.sqm` to its pre-upgrade id)
 * is copied along, so `MergeSql` can reconcile two devices that renamed the same pre-upgrade row
 * differently before upgrading. Every row that carries a `v2_id` afterwards — re-keyed here or already
 * at its derived id — also gets its identity edge `(kind, v2_id, id)` in `ks_v2_edge`, the grow-only
 * record of which ids are one identity that full sends and bases carry. The insert-children-delete order
 * keeps every foreign key satisfied with foreign keys enforced, as they are on every platform's
 * migration connection. Capture is not recording yet (`2.sqm` seeds `sync_capture` disabled), so
 * none of this enters `sync_outbox`; and `sync_outbox` is empty on any database this runs on (it is
 * created by the very migration), so there is nothing else to move. Moving a raw row re-resolves its
 * feed (`ks_feed_folder_resolve_raw_update`) under the copy, which carries the old row's
 * `deleted_at`, so the resolved folder is the same as before, under the new id.
 * A pre-delta cloud file migrated by `DatabaseMerger` runs the same step on the downloaded copy; its
 * rows then meet an already-migrated device under the same `v2_id`, and `MergeSql` reconciles rows
 * that were renamed before the migration.
 * Running again changes nothing: every row's id is already the derived one.
 */
fun rekeyTagAndFolderIds(driver: SqlDriver) {
    // `2.sqm` creates both tables; the IF NOT EXISTS keeps this self-contained if it is ever reached
    // without the full migration behind it.
    driver.execute(
        null,
        """
        CREATE TABLE IF NOT EXISTS ks_id_remap (
            kind   TEXT NOT NULL,
            old_id TEXT NOT NULL,
            new_id TEXT NOT NULL,
            PRIMARY KEY (kind, old_id)
        )
        """.trimIndent(),
        0,
    )
    driver.execute(
        null,
        """
        CREATE TABLE IF NOT EXISTS ks_v2_edge (
            kind  TEXT NOT NULL,
            v2_id TEXT NOT NULL,
            id    TEXT NOT NULL,
            PRIMARY KEY (kind, v2_id, id)
        )
        """.trimIndent(),
        0,
    )
    rekey(
        driver,
        table = "tags",
        kind = ID_REMAP_KIND_TAG,
        columns = listOf(
            "id", "name", "color", "sort_order", "deleted_at", "updated_at", "created_at",
            "name_updated_at", "color_updated_at", "sort_order_updated_at", "v2_id",
        ),
        derivedId = IdGenerator::tagId,
        moveChildrenSql = listOf("UPDATE feed_tags SET tag_id = ? WHERE tag_id = ?"),
    )
    rekey(
        driver,
        table = "folders",
        kind = ID_REMAP_KIND_FOLDER,
        columns = listOf(
            "id", "name", "sort_order", "deleted_at", "updated_at", "created_at",
            "name_updated_at", "sort_order_updated_at", "v2_id",
        ),
        derivedId = IdGenerator::folderId,
        moveChildrenSql = listOf(
            "UPDATE feeds SET folder_id = ? WHERE folder_id = ?",
            "UPDATE ks_feed_folder_raw SET folder_id = ? WHERE folder_id = ?",
        ),
    )
}

/** The re-key of one table (see [rekeyTagAndFolderIds] for the whole step). */
private fun rekey(
    driver: SqlDriver,
    table: String,
    kind: String,
    columns: List<String>,
    derivedId: (String) -> String,
    moveChildrenSql: List<String>,
) {
    val rows: List<Pair<String, String>> = driver.executeQuery(
        identifier = null,
        sql = "SELECT id, name FROM $table",
        mapper = { cursor ->
            QueryResult.Value(
                buildList {
                    while (cursor.next().value) add(cursor.getString(0)!! to cursor.getString(1)!!)
                },
            )
        },
        parameters = 0,
    ).value
    val id = columns.first()
    for ((oldId, name) in rows) {
        val newId = derivedId(name)
        if (oldId == newId) continue
        // Copy the row under the new id (the children still point at the old one), move the
        // children, then drop the old row — FK-safe with foreign keys enforced.
        driver.execute(
            null,
            "INSERT INTO $table (${columns.joinToString(", ")}) " +
                "SELECT ?, ${columns.drop(1).joinToString(", ")} FROM $table WHERE $id = ?",
            2,
        ) {
            bindString(0, newId)
            bindString(1, oldId)
        }
        for (sql in moveChildrenSql) {
            driver.execute(null, sql, 2) {
                bindString(0, newId)
                bindString(1, oldId)
            }
        }
        driver.execute(null, "DELETE FROM $table WHERE $id = ?", 1) {
            bindString(0, oldId)
        }
        IdRemapSql.record(driver, kind, oldId, newId)
    }
    // Every row carrying a pre-upgrade id now sits at its derived id: record its identity edge.
    driver.execute(
        null,
        "INSERT OR IGNORE INTO ks_v2_edge (kind, v2_id, id) SELECT ?, v2_id, $id FROM $table WHERE v2_id IS NOT NULL",
        1,
    ) {
        bindString(0, kind)
    }
}
