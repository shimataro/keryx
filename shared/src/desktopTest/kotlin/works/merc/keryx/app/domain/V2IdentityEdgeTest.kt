package works.merc.keryx.app.domain

import works.merc.keryx.app.createSchemaV2
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.TransportFixtures.DEV_A
import works.merc.keryx.app.domain.TransportFixtures.DEV_B
import works.merc.keryx.app.domain.TransportFixtures.baseName
import works.merc.keryx.app.domain.TransportFixtures.connect
import works.merc.keryx.app.domain.TransportFixtures.deltaName
import works.merc.keryx.app.domain.TransportFixtures.exec
import works.merc.keryx.app.domain.TransportFixtures.fold
import works.merc.keryx.app.domain.TransportFixtures.folder
import works.merc.keryx.app.domain.TransportFixtures.localDb
import works.merc.keryx.app.domain.TransportFixtures.merge
import works.merc.keryx.app.domain.TransportFixtures.tag
import works.merc.keryx.app.domain.TransportFixtures.transportFile
import works.merc.keryx.app.platform.DatabaseMerger
import works.merc.keryx.app.platform.TransportFiles
import works.merc.keryx.app.platform.TransportValidation
import works.merc.keryx.app.platform.ValidationTarget
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The identity edges of tags and folders (`ks_v2_edge` locally, `folder_v2_edges` / `tag_v2_edges`
 * in transport files): recorded by every merge — delta, base and pre-delta — from the cloud file's
 * edge tables and from its rows' `v2_id`, folded by union, and never duplicated. These statements
 * only record and carry edges: the reconciliation itself is [MergeSql]'s and is tested elsewhere.
 */
class V2IdentityEdgeTest {

    private fun rows(file: File, sql: String): Set<String> = connect(file).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildSet { while (rs.next()) add((1..rs.metaData.columnCount).joinToString("|") { "${rs.getObject(it)}" }) }
            }
        }
    }

    /** A local database's `ks_v2_edge`, as `kind|v2_id|id`. */
    private fun localEdges(file: File): Set<String> = rows(file, "SELECT kind, v2_id, id FROM ks_v2_edge")

    /** A local database's `ks_v2_edge` row count (duplicates would show here, not in [localEdges]). */
    private fun localEdgeCount(file: File): Int = rows(file, "SELECT COUNT(*) FROM ks_v2_edge").single().toInt()

    /** A transport file's edge tables, as `folder|v2_id|id` / `tag|v2_id|id`. */
    private fun transportEdges(file: File): Set<String> =
        rows(file, "SELECT 'folder', v2_id, id FROM folder_v2_edges") + rows(file, "SELECT 'tag', v2_id, id FROM tag_v2_edges")

    private fun transportEdgeCount(file: File): Int =
        rows(file, "SELECT (SELECT COUNT(*) FROM folder_v2_edges) + (SELECT COUNT(*) FROM tag_v2_edges)").single().toInt()

    // ---- Fold ----

    /**
     * Two deltas whose rows share an id but carry different `v2_id`s: the fold's row keeps only the
     * greater `v2_id`, but both links survive in the edge tables, whichever order the inputs come in.
     */
    @Test
    fun aFoldKeepsTheEdgeOfEveryInputRowEvenWhereTheRowsV2IdKeepsOnlyOne() {
        val nameA = deltaName(DEV_A, 1)
        val nameB = deltaName(DEV_B, 1)
        fun fileA() = transportFile(nameA, folder("d1", "Work", v2Id = "p"), tag("t1", "news", v2Id = "p"))
        fun fileB() = transportFile(nameB, folder("d1", "Work", v2Id = "q"), tag("t1", "news", v2Id = "q"))
        val expected = setOf("folder|p|d1", "folder|q|d1", "tag|p|t1", "tag|q|t1")

        val ab = fold(fileA() to nameA, fileB() to nameB)
        val ba = fold(fileB() to nameB, fileA() to nameA)
        assertEquals(expected, transportEdges(ab))
        assertEquals(expected, transportEdges(ba))
        // The rows' own v2_id keeps only the greater one — the link the edge tables preserve.
        assertEquals(setOf("q"), rows(ab, "SELECT v2_id FROM folders"))
    }

    /** Edge tables fold by union, without duplicates, and a fold of folds keeps every input's edges. */
    @Test
    fun edgeTablesFoldByUnionWithoutDuplicates() {
        val base1 = baseName(DEV_A, 1)
        val base2 = baseName(DEV_B, 1)
        fun file1() = transportFile(
            base1,
            "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('p', 'd1')",
            "INSERT INTO tag_v2_edges (v2_id, id) VALUES ('p', 't1')",
        )
        fun file2() = transportFile(
            base2,
            "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('p', 'd1')",
            "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('q', 'd2')",
            "INSERT INTO tag_v2_edges (v2_id, id) VALUES ('p', 't2')",
        )
        val folded = fold(file1() to base1, file2() to base2)
        assertEquals(setOf("folder|p|d1", "folder|q|d2", "tag|p|t1", "tag|p|t2"), transportEdges(folded))
        assertEquals(4, transportEdgeCount(folded))

        // Folding the same inputs again changes nothing.
        TransportFiles.fold(folded.absolutePath, file2().absolutePath, base2)
        assertEquals(4, transportEdgeCount(folded))

        // A fold of the folded file (as a new base would be built) keeps them all, and still validates.
        val refolded = fold(folded to baseName(DEV_A, 1))
        assertEquals(transportEdges(folded), transportEdges(refolded))
        assertEquals(TransportValidation.Valid, TransportFiles.validate(refolded.absolutePath, ValidationTarget.Transport(baseName(DEV_A, 1))))
    }

    // ---- Merge ----

    /**
     * A delta's rows carry their `v2_id`: the merge records `(v2_id, id)` of every cloud row as it is in
     * the file — before the reconciliation moves the cloud row onto main's id — together with main's
     * own rows' edges.
     */
    @Test
    fun aDeltaMergeRecordsItsRowsEdgesAsInTheFileAndMainsOwn() {
        val local = localDb(folder("d-main", "Work", v2Id = "p"), tag("t-main", "news", v2Id = "r"))
        val name = deltaName(DEV_A, 1)
        // d-cloud shares main's v2 id p: the reconciliation maps it onto d-main.
        val cloud = transportFile(name, folder("d-cloud", "Job", v2Id = "p"), tag("t-cloud", "other", v2Id = "s"), tag("t-new", "new"))
        merge(local, cloud)

        assertEquals(
            setOf("folder|p|d-main", "folder|p|d-cloud", "tag|r|t-main", "tag|s|t-cloud"),
            localEdges(local),
        )
        // A second merge of the same file adds nothing.
        merge(local, cloud)
        assertEquals(4, localEdgeCount(local))
    }

    /** A base's edge tables reach the receiver's `ks_v2_edge`, edges of ids it holds no row of included. */
    @Test
    fun aBaseMergeRecordsItsEdgeTables() {
        val local = localDb()
        val name = baseName(DEV_A, 1)
        val cloud = transportFile(
            name,
            folder("d1", "Work", v2Id = "p"),
            "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('p', 'd1')",
            "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('p', 'd-elsewhere')",
            "INSERT INTO tag_v2_edges (v2_id, id) VALUES ('q', 't-elsewhere')",
        )
        merge(local, cloud)
        assertEquals(setOf("folder|p|d1", "folder|p|d-elsewhere", "tag|q|t-elsewhere"), localEdges(local))
        assertEquals(3, localEdgeCount(local))
    }

    /**
     * A version-1 transport file has no edge tables: `prepareForMerge` creates them (empty), and the
     * real `mergeTransport` with [MergeSql.transport] records the edges its rows imply.
     */
    @Test
    fun aVersionOneTransportFileIsMergedThroughTheRealMerger() {
        val local = localDb()
        val cloud = transportFile(deltaName(DEV_A, 1), folder("d1", "Work"), tag("t1", "news"))
        // Down to version 1: no edge tables and no v2_id columns.
        exec(
            cloud,
            "DROP TABLE folder_v2_edges",
            "DROP TABLE tag_v2_edges",
            "ALTER TABLE folders DROP COLUMN v2_id",
            "ALTER TABLE tags DROP COLUMN v2_id",
            "PRAGMA user_version = 1",
        )
        DatabaseMerger.mergeTransport(local.absolutePath, cloud.absolutePath, MergeSql.transport)
        assertEquals(setOf("0"), rows(cloud, "SELECT COUNT(*) FROM folder_v2_edges"))
        assertEquals(setOf("d1"), rows(local, "SELECT id FROM folders"))
        assertEquals(emptySet(), localEdges(local))
    }

    /**
     * The pre-delta merge: a version-2 cloud database has no edge tables, but its migration re-keys
     * its rows and keeps their pre-upgrade ids in `v2_id`, which the merge records as edges.
     */
    @Test
    fun aPreDeltaMergeRecordsTheEdgesOfTheMigratedRows() {
        val cloudFile = File.createTempFile("cloud-v2-", ".db").apply { deleteOnExit() }
        JdbcSqliteDriver("jdbc:sqlite:${cloudFile.absolutePath}").use { driver ->
            driver.createSchemaV2()
            listOf(
                "INSERT INTO tags (id, name, color, sort_order, updated_at, created_at) VALUES ('t-v2', 'Tag', NULL, 0, 600, 1)",
                "INSERT INTO folders (id, name, sort_order, updated_at, created_at) VALUES ('d-v2', 'Folder', 0, 700, 1)",
            ).forEach { driver.execute(null, it, 0) }
        }
        val local = localDb()

        DatabaseMerger.merge(local.absolutePath, cloudFile.absolutePath, KeryxDatabase.Schema.version, MergeSql.all)

        assertEquals(
            setOf("folder|d-v2|${IdGenerator.folderId("Folder")}", "tag|t-v2|${IdGenerator.tagId("Tag")}"),
            localEdges(local),
        )
    }
}
