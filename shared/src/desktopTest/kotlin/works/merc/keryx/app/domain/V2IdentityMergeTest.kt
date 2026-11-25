package works.merc.keryx.app.domain

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import works.merc.keryx.app.createSchemaV2
import works.merc.keryx.app.data.local.db.KeryxDatabase
import works.merc.keryx.app.domain.TransportFixtures.DEV_A
import works.merc.keryx.app.domain.TransportFixtures.DEV_B
import works.merc.keryx.app.domain.TransportFixtures.DEV_C
import works.merc.keryx.app.domain.TransportFixtures.connect
import works.merc.keryx.app.domain.TransportFixtures.deltaName
import works.merc.keryx.app.domain.TransportFixtures.dump
import works.merc.keryx.app.domain.TransportFixtures.exec
import works.merc.keryx.app.domain.TransportFixtures.feed
import works.merc.keryx.app.domain.TransportFixtures.feedTag
import works.merc.keryx.app.domain.TransportFixtures.fold
import works.merc.keryx.app.domain.TransportFixtures.folder
import works.merc.keryx.app.domain.TransportFixtures.localDb
import works.merc.keryx.app.domain.TransportFixtures.merge
import works.merc.keryx.app.domain.TransportFixtures.tag
import works.merc.keryx.app.domain.TransportFixtures.transportFile
import works.merc.keryx.app.platform.DatabaseMerger
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The identity step of every merge ([V2IdentitySql]): rows that are one identity — connected by a
 * shared `v2_id`, transitively — end on one canonical id (the smallest row id of the identity)
 * whatever order the files arrive in, however they were folded, and whether the rows were
 * received or already held; their children follow. Orderings are forced by permuting files, never
 * by timing.
 */
class V2IdentityMergeTest {

    // ---- helpers ----

    private fun permutations(items: List<Int>): List<List<Int>> =
        if (items.size <= 1) listOf(items) else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }

    private fun rows(file: File, sql: String): List<String> = connect(file).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).joinToString("|") { "${rs.getObject(it)}" }) }
            }
        }
    }

    /**
     * The synced tables, the raw folder assignments and the identity edges of a local database. Not
     * `ks_id_remap`: it records the moves this device made, which depend on whether it held a
     * non-canonical row when the edge arrived (see [remap]), so it legitimately differs by history.
     */
    private fun state(file: File): Map<String, List<String>> = dump(file, local = true) + mapOf(
        "ks_v2_edge" to rows(file, "SELECT kind, v2_id, id FROM ks_v2_edge ORDER BY 1, 2, 3"),
    )

    private fun remap(file: File) = rows(file, "SELECT kind, old_id, new_id FROM ks_id_remap ORDER BY 1, 2, 3")

    private fun tagIds(file: File) = rows(file, "SELECT id FROM tags ORDER BY id")
    private fun folderIds(file: File) = rows(file, "SELECT id FROM folders ORDER BY id")

    /** `feed_id|tag_id` of every link. */
    private fun links(file: File) = rows(file, "SELECT feed_id, tag_id FROM feed_tags ORDER BY 1, 2")

    /** `id|folder_id|raw folder id` of every feed. */
    private fun feedFolders(file: File) = rows(
        file,
        "SELECT f.id, f.folder_id, r.folder_id FROM feeds f JOIN ks_feed_folder_raw r ON r.feed_id = f.id ORDER BY f.id",
    )

    private fun foreignKeyViolations(file: File): List<String> = rows(file, "PRAGMA foreign_key_check")

    /** One delta of device [device] holding a folder, a tag, and a feed using both, all under the given ids. */
    private fun aliasFile(
        device: String,
        id: String,
        v2Id: String,
        stamp: Long,
        feedId: String = "f1",
    ): File = transportFile(
        deltaName(device, 1),
        folder("d-$id", "Folder $id", updatedAt = stamp, v2Id = v2Id),
        tag("t-$id", "Tag $id", updatedAt = stamp, v2Id = v2Id),
        feed(feedId, folderId = "d-$id", folderAt = stamp),
        feedTag(feedId, "t-$id", updatedAt = stamp),
    )

    private val devices = listOf(DEV_A, DEV_B, DEV_C)

    // ---- (a) the reviewer's scenario: an empty device receives two aliases ----

    @Test
    fun anEmptyDeviceReceivingTwoAliasesConvergesOnTheSmallerIdInEveryOrderAndFold() {
        fun fileA() = aliasFile(DEV_A, "a", "p", stamp = 100)
        fun fileB() = aliasFile(DEV_B, "b", "p", stamp = 200)
        val nameA = deltaName(DEV_A, 1)
        val nameB = deltaName(DEV_B, 1)

        val results = listOf(
            localDb().also { merge(it, fileA()); merge(it, fileB()) },
            localDb().also { merge(it, fileB()); merge(it, fileA()) },
            localDb().also { merge(it, fold(fileA() to nameA, fileB() to nameB)) },
            localDb().also { merge(it, fold(fileB() to nameB, fileA() to nameA)) },
        )
        for (db in results) {
            assertEquals(listOf("t-a"), tagIds(db))
            assertEquals(listOf("d-a"), folderIds(db))
            assertEquals(listOf("f1|t-a"), links(db))
            // The raw assignment is B's (the newer stamp) exactly as B wrote it; it resolves to the
            // canonical folder.
            assertEquals(listOf("f1|d-a|d-b"), feedFolders(db))
            assertEquals(emptyList(), foreignKeyViolations(db))
        }
        val reference = state(results.first())
        results.drop(1).forEach { assertEquals(reference, state(it)) }
        // Every non-canonical id seen is recorded, whether this device held its rows (B first) or only
        // a file used it (A first): the map is the same in every order.
        results.forEach { assertEquals(listOf("folder|d-b|d-a", "tag|t-b|t-a"), remap(it)) }
        // B's stamps are newer, so its values win on the one row.
        assertEquals(listOf("Tag b"), rows(results.first(), "SELECT name FROM tags"))
        assertEquals(listOf("Folder b"), rows(results.first(), "SELECT name FROM folders"))
    }

    /** The smaller id is chosen, not the first or last seen: the same with the alias roles swapped. */
    @Test
    fun theCanonicalIdIsTheSmallerWhicheverFileCarriesIt() {
        for ((first, second) in listOf("a" to "b", "b" to "a")) {
            val db = localDb()
            merge(db, aliasFile(DEV_A, first, "p", stamp = 100))
            merge(db, aliasFile(DEV_B, second, "p", stamp = 200))
            assertEquals(listOf("t-a"), tagIds(db), "first=$first")
            assertEquals(listOf("d-a"), folderIds(db), "first=$first")
        }
    }

    // ---- (b) three aliases; a chain through one id carrying two v2 ids ----

    @Test
    fun threeAliasesConvergeOnTheSmallestIdInEveryOrder() {
        fun file(i: Int) = when (i) {
            0 -> aliasFile(DEV_A, "c", "p", stamp = 100)
            1 -> aliasFile(DEV_B, "a", "p", stamp = 300)
            else -> aliasFile(DEV_C, "b", "p", stamp = 200)
        }
        val results = permutations(listOf(0, 1, 2)).map { order -> localDb().also { db -> order.forEach { merge(db, file(it)) } } }
        for (db in results) {
            assertEquals(listOf("t-a"), tagIds(db))
            assertEquals(listOf("d-a"), folderIds(db))
            assertEquals(listOf("f1|t-a"), links(db))
            assertEquals(listOf("f1|d-a|d-a"), feedFolders(db))
        }
        results.drop(1).forEach { assertEquals(state(results.first()), state(it)) }
    }

    @Test
    fun aChainThroughOneIdCarryingTwoV2IdsIsOneIdentity() {
        // Row "n" carries v2 p on one device and q on another; "m" has p, "k" has q: one component.
        for (ids in listOf(Triple("m", "n", "k"), Triple("k", "n", "m"), Triple("n", "k", "m"))) {
            val (viaP, shared, viaQ) = ids
            fun file(i: Int) = when (i) {
                0 -> transportFile(deltaName(DEV_A, 1), tag("t-$shared", "N", updatedAt = 100, v2Id = "p"), folder("d-$shared", "N", v2Id = "p"))
                1 -> transportFile(deltaName(DEV_B, 1), tag("t-$shared", "N", updatedAt = 100, v2Id = "q"), folder("d-$shared", "N", v2Id = "q"))
                2 -> transportFile(deltaName(DEV_C, 1), tag("t-$viaP", "M", updatedAt = 200, v2Id = "p"), folder("d-$viaP", "M", updatedAt = 200, v2Id = "p"))
                else -> transportFile(
                    deltaName(DEV_C, 2),
                    tag("t-$viaQ", "K", updatedAt = 300, v2Id = "q"), folder("d-$viaQ", "K", updatedAt = 300, v2Id = "q"),
                    feed("f1", folderId = "d-$viaQ", folderAt = 300), feedTag("f1", "t-$viaQ", updatedAt = 300),
                )
            }
            val canonical = listOf(viaP, shared, viaQ).min()
            val results = permutations(listOf(0, 1, 2, 3)).map { order ->
                localDb().also { db -> order.forEach { merge(db, file(it)) } }
            }
            for (db in results) {
                assertEquals(listOf("t-$canonical"), tagIds(db), "ids=$ids")
                assertEquals(listOf("d-$canonical"), folderIds(db), "ids=$ids")
                assertEquals(listOf("f1|t-$canonical"), links(db), "ids=$ids")
                assertEquals(listOf("f1|d-$canonical|d-$viaQ"), feedFolders(db), "ids=$ids")
            }
            // The edge set itself can differ by order: a row that took a greater v2_id in a fold is
            // recorded as an edge too (an extra edge inside the one component), so compare the rest.
            fun withoutEdges(db: File) = state(db) - "ks_v2_edge"
            results.drop(1).forEach { assertEquals(withoutEdges(results.first()), withoutEdges(it), "ids=$ids") }
        }
    }

    // ---- (c) fold equivalence and idempotence ----

    @Test
    fun foldingBeforeOrAfterMergingGivesTheSameStateForEveryGrouping() {
        val names = listOf(deltaName(DEV_A, 1), deltaName(DEV_B, 1), deltaName(DEV_C, 1))
        fun file(i: Int) = when (i) {
            0 -> aliasFile(DEV_A, "c", "p", stamp = 100)
            1 -> aliasFile(DEV_B, "a", "p", stamp = 300)
            else -> aliasFile(DEV_C, "b", "q", stamp = 200).also {
                // b also joins q to p through a second row, so the third file links the two v2 ids.
                exec(it, folder("d-b2", "Folder b2", v2Id = "p"), tag("t-b2", "Tag b2", v2Id = "p"))
                exec(it, "INSERT INTO tag_v2_edges (v2_id, id) VALUES ('q', 't-b2')", "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('q', 'd-b2')")
            }
        }
        val sequential = localDb().also { db -> (0..2).forEach { merge(db, file(it)) } }
        val allFolded = localDb().also { merge(it, fold(*(0..2).map { i -> file(i) to names[i] }.toTypedArray())) }
        val foldedPairThenOne = localDb().also {
            merge(it, fold(file(0) to names[0], file(1) to names[1]))
            merge(it, file(2))
        }
        val oneThenFoldedPair = localDb().also {
            merge(it, file(0))
            merge(it, fold(file(1) to names[1], file(2) to names[2]))
        }
        val reference = state(sequential)
        assertEquals(listOf("t-a"), tagIds(sequential))
        for (db in listOf(allFolded, foldedPairThenOne, oneThenFoldedPair)) assertEquals(reference, state(db))
    }

    @Test
    fun mergingTheSameFileTwiceChangesNothingAndMovesNoMainRow() {
        val db = localDb()
        fun fileB() = aliasFile(DEV_B, "b", "p", stamp = 200)
        merge(db, aliasFile(DEV_A, "a", "p", stamp = 100))
        merge(db, fileB())
        val afterFirst = state(db)
        val remapAfterFirst = remap(db)
        merge(db, fileB())
        assertEquals(afterFirst, state(db))
        // The alias b (only a file used it) is recorded once; a second merge records nothing more.
        assertEquals(listOf("folder|d-b|d-a", "tag|t-b|t-a"), remapAfterFirst)
        assertEquals(remapAfterFirst, remap(db))
        // A base of the same rows is just as quiet.
        merge(db, fold(fileB() to deltaName(DEV_B, 1)))
        assertEquals(afterFirst, state(db))
    }

    // ---- (d) main re-key ----

    /** Merges [cloud] with the capture suppressed exactly as `DeltaSync.mergeFolded` does. */
    private fun mergeWithMarker(local: File, cloud: File) {
        val statements = listOf("INSERT INTO main.sync_merging (flag) VALUES (1)") + MergeSql.transport + "DELETE FROM main.sync_merging"
        DatabaseMerger.mergeTransport(local.absolutePath, cloud.absolutePath, statements)
    }

    private fun outbox(file: File) = rows(file, "SELECT tbl, pk, COALESCE(pk2, '-') FROM sync_outbox ORDER BY seq")

    /**
     * Main holds the non-canonical tag/folder "b" (children and bookkeeping included) and receives a
     * file carrying the edge to the smaller id "a"; main either lacks "a" or already holds it.
     */
    private fun rekeyScenario(mainHoldsCanonical: Boolean) {
        val seed = buildList {
            add(folder("d-b", "Folder b", updatedAt = 200, nameAt = 200, sortOrder = 2, sortAt = 200, v2Id = "q"))
            add(tag("t-b", "Tag b", updatedAt = 200, nameAt = 200, color = "blue", colorAt = 50, v2Id = "p"))
            if (mainHoldsCanonical) {
                add(folder("d-a", "Folder a", updatedAt = 100, nameAt = 100, sortOrder = 1, sortAt = 300, createdAt = 10))
                add(tag("t-a", "Tag a", updatedAt = 100, nameAt = 100, color = "red", colorAt = 300, createdAt = 10))
            }
            add(feed("f1", folderId = "d-b", folderAt = 150))
            add(feed("f2"))
            add(feedTag("f1", "t-b", updatedAt = 300, deletedAt = 300))
            add(feedTag("f2", "t-b", updatedAt = 120))
            if (mainHoldsCanonical) add(feedTag("f1", "t-a", updatedAt = 150))
            add("INSERT INTO ks_id_remap (kind, old_id, new_id) VALUES ('tag', 'x', 't-b')")
            add("INSERT INTO ks_id_remap (kind, old_id, new_id) VALUES ('folder', 'xd', 'd-b')")
            add("INSERT INTO sync_outbox (tbl, pk, pk2, heavy) VALUES ('tags', 't-b', NULL, 0)")
            add("INSERT INTO sync_outbox (tbl, pk, pk2, heavy) VALUES ('folders', 'd-b', NULL, 0)")
            add("INSERT INTO sync_outbox (tbl, pk, pk2, heavy) VALUES ('feed_tags', 'f2', 't-b', 0)")
            add("INSERT INTO sync_outbox (tbl, pk, pk2, heavy) VALUES ('feeds', 'f1', NULL, 0)")
        }.toTypedArray()
        val local = localDb(*seed)
        assertEquals(emptyList(), foreignKeyViolations(local))
        // Recording on, so a merge that did not suppress it would show up in the outbox.
        exec(local, "UPDATE sync_capture SET enabled = 1")
        val outboxBefore = outbox(local).size

        val cloud = transportFile(
            deltaName(DEV_A, 1),
            "INSERT INTO tag_v2_edges (v2_id, id) VALUES ('p', 't-a')",
            "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('q', 'd-a')",
        )
        mergeWithMarker(local, cloud)

        assertEquals(listOf("t-a"), tagIds(local))
        assertEquals(listOf("d-a"), folderIds(local))
        assertEquals(emptyList(), foreignKeyViolations(local))
        // Links: f1's two links folded into one (the newer, deleted one wins), f2's moved.
        assertEquals(listOf("f1|t-a", "f2|t-a"), links(local))
        assertEquals(listOf("300|300", "120|null"), rows(local, "SELECT updated_at, deleted_at FROM feed_tags ORDER BY feed_id"))
        // The resolved folder follows; the raw assignment stays as it was written.
        assertEquals(listOf("f1|d-a|d-b", "f2|null|null"), feedFolders(local))
        // Values: the newer name from b; per group, the rest from whichever is newer.
        assertEquals(listOf("Tag b"), rows(local, "SELECT name FROM tags"))
        assertEquals(listOf("Folder b"), rows(local, "SELECT name FROM folders"))
        assertEquals(listOf(if (mainHoldsCanonical) "red" else "blue"), rows(local, "SELECT color FROM tags"))
        assertEquals(listOf(if (mainHoldsCanonical) "1" else "2"), rows(local, "SELECT sort_order FROM folders"))
        assertEquals(listOf(if (mainHoldsCanonical) "10" else "100"), rows(local, "SELECT created_at FROM tags"))
        // Moves recorded, the earlier chains folded onto the final id.
        assertEquals(
            listOf("folder|d-b|d-a", "folder|xd|d-a", "tag|t-b|t-a", "tag|x|t-a"),
            rows(local, "SELECT kind, old_id, new_id FROM ks_id_remap ORDER BY 1, 2"),
        )
        // The unsent entries were rewritten onto the new ids; the merge added none.
        assertEquals(outboxBefore, outbox(local).size)
        assertEquals(
            listOf("tags|t-a|-", "folders|d-a|-", "feed_tags|f2|t-a", "feeds|f1|-"),
            outbox(local),
        )
        // And a repeat moves nothing.
        val settled = state(local)
        mergeWithMarker(local, cloud)
        assertEquals(settled, state(local))
    }

    @Test
    fun mainRowsMoveToACanonicalIdMainDoesNotHold() = rekeyScenario(mainHoldsCanonical = false)

    @Test
    fun mainRowsFoldIntoACanonicalRowMainAlreadyHolds() = rekeyScenario(mainHoldsCanonical = true)

    // ---- (e) the pre-delta path ----

    /** A version-2 database with one tag, folder and feed using both, optionally migrated to the current schema. */
    private fun v2Device(prefix: String, tagName: String, folderName: String, stamp: Long, migrate: Boolean): File {
        val file = File.createTempFile(prefix, ".db").apply { deleteOnExit() }
        JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
            driver.createSchemaV2()
            listOf(
                "INSERT INTO folders (id, name, sort_order, updated_at, created_at) VALUES ('old-folder', '$folderName', 0, $stamp, 1)",
                "INSERT INTO tags (id, name, color, sort_order, updated_at, created_at) VALUES ('old-tag', '$tagName', NULL, 0, $stamp, 1)",
                "INSERT INTO feeds (id, url, title, etag, folder_id, folder_updated_at, updated_at, created_at) " +
                    "VALUES ('f1', 'https://a', 'Feed', 'e', 'old-folder', $stamp, 100, 1)",
                "INSERT INTO feed_tags (feed_id, tag_id, updated_at) VALUES ('f1', 'old-tag', $stamp)",
            ).forEach { driver.execute(null, it, 0) }
        }
        if (migrate) {
            JdbcSqliteDriver("jdbc:sqlite:${file.absolutePath}").use { driver ->
                KeryxDatabase.Schema.migrate(driver, 2, KeryxDatabase.Schema.version, *KERYX_SCHEMA_CALLBACKS)
                driver.execute(null, "PRAGMA user_version=${KeryxDatabase.Schema.version}", 0)
            }
        }
        return file
    }

    @Test
    fun aPreDeltaMergeConvergesBothWaysWithForeignKeysEnforced() {
        // X and Y renamed the same pre-upgrade rows differently; each is a migrated full database, and
        // each merges the other's copy as a pre-delta file (a full database written by an older build).
        val x = v2Device("xdev", "Job", "JobDir", stamp = 300, migrate = true)
        val y = v2Device("ydev", "Work", "WorkDir", stamp = 100, migrate = true)
        val xCopy = x.copyTo(File.createTempFile("xcopy", ".db").apply { deleteOnExit() }, overwrite = true)
        val yCopy = y.copyTo(File.createTempFile("ycopy", ".db").apply { deleteOnExit() }, overwrite = true)

        DatabaseMerger.merge(x.absolutePath, yCopy.absolutePath, KeryxDatabase.Schema.version, MergeSql.all)
        DatabaseMerger.merge(y.absolutePath, xCopy.absolutePath, KeryxDatabase.Schema.version, MergeSql.all)

        val tagId = minOf(IdGenerator.tagId("Job"), IdGenerator.tagId("Work"))
        val folderId = minOf(IdGenerator.folderId("JobDir"), IdGenerator.folderId("WorkDir"))
        for (db in listOf(x, y)) {
            assertEquals(listOf(tagId), tagIds(db))
            assertEquals(listOf(folderId), folderIds(db))
            assertEquals(listOf("f1|$tagId"), links(db))
            // The raw assignment is X's (the newer stamp), as X's migration wrote it.
            assertEquals(listOf("f1|$folderId|${IdGenerator.folderId("JobDir")}"), feedFolders(db))
            assertEquals(emptyList(), foreignKeyViolations(db))
        }
        assertEquals(dump(x, local = true), dump(y, local = true))
    }

    @Test
    fun aRealVersionTwoCloudFileMergesOntoAMigratedDeviceAndConverges() {
        val local = v2Device("local-", "Job", "JobDir", stamp = 300, migrate = true)
        val cloud = v2Device("cloud-", "Work", "WorkDir", stamp = 100, migrate = false)
        DatabaseMerger.merge(local.absolutePath, cloud.absolutePath, KeryxDatabase.Schema.version, MergeSql.all)

        val tagId = minOf(IdGenerator.tagId("Job"), IdGenerator.tagId("Work"))
        val folderId = minOf(IdGenerator.folderId("JobDir"), IdGenerator.folderId("WorkDir"))
        assertEquals(listOf(tagId), tagIds(local))
        assertEquals(listOf(folderId), folderIds(local))
        assertEquals(listOf("f1|$tagId"), links(local))
        assertEquals(emptyList(), foreignKeyViolations(local))
        assertEquals(listOf("Job"), rows(local, "SELECT name FROM tags")) // stamp 300 beats 100
        // The edges of both devices' derivations are recorded.
        assertEquals(
            setOf("tag|old-tag|${IdGenerator.tagId("Job")}", "tag|old-tag|${IdGenerator.tagId("Work")}"),
            rows(local, "SELECT kind, v2_id, id FROM ks_v2_edge WHERE kind = 'tag'").toSet(),
        )
    }

    // ---- (f) a main orphan link ----

    @Test
    fun aMainOrphanLinkToAMovingTagDoesNotFailTheMerge() {
        val local = localDb(
            tag("t-b", "Tag b", v2Id = "p"),
            feed("f1"),
            feedTag("f1", "t-b"),
        )
        // Seeded with foreign keys off (the connection default): a link whose feed does not exist.
        exec(local, feedTag("ghost-feed", "t-b", updatedAt = 50))
        assertTrue(foreignKeyViolations(local).isNotEmpty())

        val cloud = transportFile(deltaName(DEV_A, 1), "INSERT INTO tag_v2_edges (v2_id, id) VALUES ('p', 't-a')")
        mergeWithMarker(local, cloud)

        assertEquals(listOf("t-a"), tagIds(local))
        assertEquals(listOf("f1|t-a", "ghost-feed|t-a"), links(local))
        // The orphan stays what it was (a link without a feed); nothing new is violated.
        assertEquals(1, foreignKeyViolations(local).size)
    }

    // ---- (g) a feed's raw folder assignment is never rewritten ----

    /**
     * Two files assign one feed to different folders at the same stamp, and a third file's edges link
     * one of those folders to a smaller canonical id — so mapping would change the order of the two
     * values. The tie is settled on the values as the files wrote them, so every merge order and every
     * fold grouping stores the same raw value (one of the two files' values, never a mapped id), the
     * same stamp and the same resolved folder.
     */
    @Test
    fun aSameStampFolderTieIsSettledOnRawValuesWhateverTheEdgesSayAndInEveryOrderAndFold() {
        val names = listOf(deltaName(DEV_A, 1), deltaName(DEV_B, 1), deltaName(DEV_C, 1))
        // d-a < d-c < d-b: mapped, d-b becomes d-a and would sort below d-c; raw, it sorts above.
        fun file(i: Int) = when (i) {
            0 -> transportFile(names[0], folder("d-b", "Folder b"), feed("f1", folderId = "d-b", folderAt = 500))
            1 -> transportFile(names[1], folder("d-c", "Folder c"), feed("f1", folderId = "d-c", folderAt = 500))
            else -> transportFile(
                names[2],
                folder("d-a", "Folder a", v2Id = "p"),
                "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('p', 'd-b')",
                "INSERT INTO folder_v2_edges (v2_id, id) VALUES ('p', 'd-a')",
            )
        }
        val results = permutations(listOf(0, 1, 2)).flatMap { (a, b, c) ->
            listOf(
                "sequential $a,$b,$c" to localDb().also { db -> listOf(a, b, c).forEach { merge(db, file(it)) } },
                "fold($a,$b,$c)" to localDb().also { merge(it, fold(file(a) to names[a], file(b) to names[b], file(c) to names[c])) },
                "fold($a,$b) then $c" to localDb().also {
                    merge(it, fold(file(a) to names[a], file(b) to names[b]))
                    merge(it, file(c))
                },
                "$a then fold($b,$c)" to localDb().also {
                    merge(it, file(a))
                    merge(it, fold(file(b) to names[b], file(c) to names[c]))
                },
            )
        }
        val (referenceLabel, reference) = results.first()
        val referenceState = state(reference)
        val referenceFolders = feedFolders(reference).single().split("|")
        // The raw value is one of the file values, with the files' stamp — never a mapped id.
        assertTrue(referenceFolders[2] in setOf("d-b", "d-c"), "raw=${referenceFolders[2]}")
        assertEquals("500", rows(reference, "SELECT folder_updated_at FROM ks_feed_folder_raw").single())
        // d-b was re-keyed onto d-a, so it resolves there; d-c is its own.
        assertEquals(if (referenceFolders[2] == "d-b") "d-a" else "d-c", referenceFolders[1])
        assertEquals(listOf("d-a", "d-c"), folderIds(reference))
        for ((label, db) in results) {
            assertEquals(referenceState, state(db), "$label differs from $referenceLabel")
            assertEquals(feedFolders(reference), feedFolders(db), label)
            assertEquals(emptyList(), foreignKeyViolations(db), label)
        }
    }

    private fun resolvedFolder(file: File): String =
        rows(file, "SELECT COALESCE(folder_id, 'null'), folder_updated_at FROM feeds WHERE id = 'f1'").single()

    private fun setRaw(file: File, folderId: String, stamp: Long = 500) =
        exec(file, "UPDATE ks_feed_folder_raw SET folder_id = '$folderId', folder_updated_at = $stamp WHERE feed_id = 'f1'")

    /**
     * The resolved folder follows every change of `ks_id_remap` (insert, update of either side, delete,
     * and a move back recorded through [IdRemapSql]) without the raw assignment moving.
     */
    @Test
    fun theResolvedFolderFollowsEveryChangeOfAnIdRemapEntry() {
        val db = localDb(folder("X", "X"), folder("A", "A"), folder("B", "B"), feed("f1"))
        setRaw(db, "X")
        assertEquals("X|500", resolvedFolder(db))

        exec(db, "INSERT INTO ks_id_remap (kind, old_id, new_id) VALUES ('folder', 'X', 'A')")
        assertEquals("A|500", resolvedFolder(db), "insert")
        // A tag entry of the same ids changes nothing.
        exec(db, "INSERT INTO ks_id_remap (kind, old_id, new_id) VALUES ('tag', 'X', 'B')")
        assertEquals("A|500", resolvedFolder(db))

        exec(db, "DELETE FROM ks_id_remap WHERE kind = 'folder' AND old_id = 'X'")
        assertEquals("X|500", resolvedFolder(db), "delete re-resolves to the row the raw id names")

        exec(db, "INSERT INTO ks_id_remap (kind, old_id, new_id) VALUES ('folder', 'X', 'A')")
        exec(db, "UPDATE ks_id_remap SET new_id = 'B' WHERE kind = 'folder' AND old_id = 'X'")
        assertEquals("B|500", resolvedFolder(db), "update of the target")
        exec(db, "UPDATE ks_id_remap SET old_id = 'Y' WHERE kind = 'folder' AND old_id = 'X'")
        assertEquals("X|500", resolvedFolder(db), "update of the old id releases the raw id")
        exec(db, "UPDATE ks_id_remap SET old_id = 'X' WHERE kind = 'folder' AND old_id = 'Y'")
        assertEquals("B|500", resolvedFolder(db), "update of the old id onto the raw id")

        // A move back B -> X (the row of X is live again) drops the entry for X, step 1 of IdRemapSql.
        IdRemapSql.foldStatements("VALUES ('folder', 'B', 'X')").forEach { exec(db, it) }
        assertEquals("X|500", resolvedFolder(db), "a move back")
        assertEquals(listOf("folder|B|X", "tag|X|B"), remap(db))
        assertEquals(listOf("X"), rows(db, "SELECT folder_id FROM ks_feed_folder_raw"))
    }

    /** The canonical row arriving (or reviving) after the raw alias was stored resolves the feed to it. */
    @Test
    fun aCanonicalFolderRowInsertedOrRevivedAfterTheAliasWasStoredResolvesTheFeed() {
        val db = localDb(feed("f1"))
        setRaw(db, "X")
        assertEquals("null|500", resolvedFolder(db), "no folder row at all")
        exec(db, "INSERT INTO ks_id_remap (kind, old_id, new_id) VALUES ('folder', 'X', 'A')")
        assertEquals("null|500", resolvedFolder(db), "the target has no row yet")

        exec(db, folder("A", "A", updatedAt = 100, deletedAt = 100))
        assertEquals("null|500", resolvedFolder(db), "a deleted canonical row does not resolve")
        exec(db, "UPDATE folders SET deleted_at = NULL, updated_at = 200 WHERE id = 'A'")
        assertEquals("A|500", resolvedFolder(db), "revived")

        // And a plain insert: a second feed, a raw alias, then the canonical row.
        val other = localDb(feed("f1"))
        setRaw(other, "X")
        exec(other, "INSERT INTO ks_id_remap (kind, old_id, new_id) VALUES ('folder', 'X', 'A')", folder("A", "A"))
        assertEquals("A|500", resolvedFolder(other), "folders insert")
    }

    /**
     * A pre-delta (full database) file's reference to a folder it has no row for and no mapping knows
     * is recorded as no folder — the one place a raw value is cleaned, since a full send of this device
     * must not carry a reference nothing can resolve — and the device can then send (see
     * `DeltaExporterTest.aPreDeltaOrphanFolderReferenceIsRecordedAsNoFolderAndTheDeviceCanStillSend`).
     * The same reference in a transport file is stored as written
     * (`SyncAlgebraTest.aReferenceToAFolderMainLacksIsKeptRawAndResolvesToNoFolder`).
     */
    @Test
    fun aPreDeltaFileReferenceToAnUnknownFolderIsRecordedAsNoFolder() {
        val cloud = File.createTempFile("v2-orphan", ".db").apply { deleteOnExit() }
        JdbcSqliteDriver("jdbc:sqlite:${cloud.absolutePath}").use { driver ->
            driver.createSchemaV2()
            driver.execute(
                null,
                "INSERT INTO feeds (id, url, title, etag, folder_id, folder_updated_at, updated_at, created_at) " +
                    "VALUES ('f1', 'https://a', 'Feed', 'e', 'ghost', 500, 100, 1)",
                0,
            )
        }
        val local = localDb()
        DatabaseMerger.merge(local.absolutePath, cloud.absolutePath, KeryxDatabase.Schema.version, MergeSql.all)
        assertEquals(listOf("f1|null|null"), feedFolders(local))
        assertEquals(emptyList(), foreignKeyViolations(local))
    }
}
