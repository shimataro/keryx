package works.merc.keryx.app.domain

import works.merc.keryx.app.domain.TransportFixtures.DEV_A
import works.merc.keryx.app.domain.TransportFixtures.DEV_B
import works.merc.keryx.app.domain.TransportFixtures.DEV_C
import works.merc.keryx.app.domain.TransportFixtures.article
import works.merc.keryx.app.domain.TransportFixtures.deltaName
import works.merc.keryx.app.domain.TransportFixtures.dump
import works.merc.keryx.app.domain.TransportFixtures.feed
import works.merc.keryx.app.domain.TransportFixtures.feedTag
import works.merc.keryx.app.domain.TransportFixtures.fold
import works.merc.keryx.app.domain.TransportFixtures.folder
import works.merc.keryx.app.domain.TransportFixtures.localDb
import works.merc.keryx.app.domain.TransportFixtures.merge
import works.merc.keryx.app.domain.TransportFixtures.searchPendingIds
import works.merc.keryx.app.domain.TransportFixtures.setting
import works.merc.keryx.app.domain.TransportFixtures.tag
import works.merc.keryx.app.domain.TransportFixtures.transportFile
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The algebra the delta design relies on ("Design Philosophy" in `docs/sync-architecture.md`):
 * [MergeSql] and [ReduceSql] are idempotent, commutative and associative, and resolve duplicates and
 * ties deterministically.
 */
class SyncAlgebraTest {

    // Three deltas touching the same rows, with ties and non-ties in every field group.
    private val nameA = deltaName(DEV_A, 1)
    private val nameB = deltaName(DEV_B, 1)
    private val nameC = deltaName(DEV_C, 1)

    private fun fileA() = transportFile(
        nameA,
        folder("d1", "Work", updatedAt = 100, sortOrder = 1),
        tag("t1", "news", updatedAt = 100, color = "red"),
        feed(
            "f1", title = "Title A", updatedAt = 100, createdAt = 90, folderId = "d1", folderAt = 110,
            sortOrder = 1, sortAt = 120, customTitle = "Custom A", customAt = 130,
        ),
        feedTag("f1", "t1", updatedAt = 100),
        article("a1", "f1", updatedAt = 100, content = "body A", isRead = 1, readAt = 200, cachedAt = 100),
        article("a2", "f1", updatedAt = 100, content = "a2 body", isRead = 1, readAt = 300),
        setting("k1", "a", updatedAt = 100),
    )

    private fun fileB() = transportFile(
        nameB,
        folder("d2", "Home", updatedAt = 140, sortOrder = 2),
        tag("t1", "news", updatedAt = 100, color = "blue", colorAt = 140),
        feed(
            "f1", title = "Title B", updatedAt = 100, createdAt = 80, folderId = "d2", folderAt = 150,
            sortOrder = 7, sortAt = 120, customTitle = "Custom B", customAt = 90,
        ),
        feedTag("f1", "t1", updatedAt = 100, deletedAt = 100),
        // A light row: newer updated_at, body withheld, newer read and star.
        article("a1", "f1", updatedAt = 300, isRead = 0, readAt = 250, isStarred = 1, starredAt = 260, cachedAt = 400),
        article("a2", "f1", updatedAt = 100, content = "a2 body", isRead = 0, readAt = 300),
        setting("k1", "b", updatedAt = 100),
    )

    private fun fileC() = transportFile(
        nameC,
        tag("t2", "tech", updatedAt = 120),
        feed("f1", title = "Title C", updatedAt = 50, createdAt = 95, deletedAt = 170, deletedUpdatedAt = 170),
        article("a1", "f1", updatedAt = 150, content = "body C", summary = "stale", cachedAt = 50, deletedAt = 180, deletedUpdatedAt = 180),
        article("a3", "f1", updatedAt = 100, summary = "only a summary"),
        setting("k2", "c", updatedAt = 10),
    )

    private val seededLocal = arrayOf(
        folder("d1", "Work", updatedAt = 50),
        feed("f1", title = "Local", updatedAt = 60, createdAt = 85, sortOrder = 3, sortAt = 120),
        article("a1", "f1", updatedAt = 60, content = "local body", isRead = 1, readAt = 210),
        setting("k1", "local", updatedAt = 100),
    )

    private fun permutations(items: List<Int>): List<List<Int>> =
        if (items.size <= 1) listOf(items) else items.flatMap { first -> permutations(items - first).map { listOf(first) + it } }

    private fun mergedSequentially(order: List<Int>, local: Array<String> = emptyArray()): Map<String, List<String>> {
        val files = listOf(::fileA, ::fileB, ::fileC)
        val db = localDb(*local)
        order.forEach { merge(db, files[it]()) }
        return dump(db)
    }

    @Test
    fun mergeIsIdempotent() {
        for (local in listOf(emptyArray(), seededLocal)) {
            for (file in listOf(fileA(), fileB(), fileC())) {
                val once = localDb(*local).also { merge(it, file) }
                val twice = localDb(*local).also { merge(it, file); merge(it, file) }
                assertEquals(dump(once), dump(twice))
            }
        }
    }

    @Test
    fun mergeIsCommutativeOverEveryOrderOfThreeFiles() {
        for (local in listOf(emptyArray(), seededLocal)) {
            val results = permutations(listOf(0, 1, 2)).map { mergedSequentially(it, local) }
            results.drop(1).forEach { assertEquals(results.first(), it) }
        }
    }

    @Test
    fun mergeIsCommutativeForEveryPairIncludingTies() {
        for ((x, y) in listOf(0 to 1, 0 to 2, 1 to 2)) {
            assertEquals(mergedSequentially(listOf(x, y)), mergedSequentially(listOf(y, x)), "files $x, $y")
        }
    }

    @Test
    fun theMergedResultFollowsEachFieldGroupsWinner() {
        val result = localDb().also { db -> listOf(fileA(), fileB(), fileC()).forEach { merge(db, it) } }
        TransportFixtures.connect(result).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery(
                    "SELECT title, folder_id, sort_order, custom_title, deleted_at, created_at FROM feeds WHERE id = 'f1'",
                ).use { rs ->
                    rs.next()
                    assertEquals("Title B", rs.getString(1)) // content tie at 100: "Title B" > "Title A"
                    assertEquals("d2", rs.getString(2)) // folder 150 > 110
                    assertEquals(7L, rs.getLong(3)) // sort-order tie at 120: 7 > 1
                    assertEquals("Custom A", rs.getString(4)) // custom title 130 > 90
                    assertEquals(170L, rs.getLong(5)) // unsubscribed at 170
                    assertEquals(80L, rs.getLong(6)) // the earliest created_at
                }
                st.executeQuery(
                    "SELECT content, summary, is_read, read_at, is_starred, deleted_at, deleted_updated_at, cached_at, updated_at " +
                        "FROM articles WHERE id = 'a1'",
                ).use { rs ->
                    rs.next()
                    assertEquals("body C", rs.getString(1)) // the light row never replaces a body; 150 > 100
                    assertEquals(null, rs.getString(2)) // the stale summary is dropped next to content
                    assertEquals(0L, rs.getLong(3)) // read 250 > 200
                    assertEquals(250L, rs.getLong(4))
                    assertEquals(1L, rs.getLong(5))
                    assertEquals(null, rs.getObject(6)) // the star (260) is newer than the deletion (180): revived
                    assertEquals(180L, rs.getLong(7))
                    assertEquals(400L, rs.getLong(8)) // the latest cached_at
                    assertEquals(150L, rs.getLong(9)) // the body winner's updated_at
                }
                st.executeQuery("SELECT is_read FROM articles WHERE id = 'a2'").use { rs ->
                    rs.next()
                    assertEquals(1L, rs.getLong(1)) // read tie at 300: 1 > 0
                }
                st.executeQuery("SELECT color FROM tags WHERE id = 't1'").use { rs ->
                    rs.next()
                    assertEquals("blue", rs.getString(1))
                }
                st.executeQuery("SELECT deleted_at FROM feed_tags WHERE feed_id = 'f1' AND tag_id = 't1'").use { rs ->
                    rs.next()
                    assertEquals(100L, rs.getLong(1)) // tie at 100: a detach outranks an attach
                }
                st.executeQuery("SELECT value FROM global_settings WHERE key = 'k1'").use { rs ->
                    rs.next()
                    assertEquals("b", rs.getString(1)) // tie at 100: "b" > "a"
                }
            }
        }
    }

    @Test
    fun foldIsIdempotent() {
        val once = fold(fileA() to nameA, fileB() to nameB)
        val twice = fold(fileA() to nameA, fileB() to nameB, fileA() to nameA, fileB() to nameB)
        assertEquals(dump(once, coverage = true), dump(twice, coverage = true))
    }

    @Test
    fun foldIsCommutativeOverEveryOrderOfThreeFiles() {
        val inputs = listOf(::fileA to nameA, ::fileB to nameB, ::fileC to nameC)
        val results = permutations(listOf(0, 1, 2)).map { order ->
            dump(fold(*order.map { inputs[it].first() to inputs[it].second }.toTypedArray()), coverage = true)
        }
        results.drop(1).forEach { assertEquals(results.first(), it) }
    }

    @Test
    fun mergingAFoldEqualsMergingItsInputsOneByOne() {
        for (local in listOf(emptyArray(), seededLocal)) {
            val folded = fold(fileA() to nameA, fileB() to nameB, fileC() to nameC)
            val viaFold = localDb(*local).also { merge(it, folded) }
            assertEquals(mergedSequentially(listOf(0, 1, 2), local), dump(viaFold))
        }
    }

    @Test
    fun foldingFoldsIsAssociative() {
        val left = fold(fold(fileA() to nameA, fileB() to nameB) to TransportFixtures.baseName(DEV_A, 1), fileC() to nameC)
        val right = fold(fileA() to nameA, fold(fileB() to nameB, fileC() to nameC) to TransportFixtures.baseName(DEV_A, 1))
        assertEquals(dump(left), dump(right))
    }

    @Test
    fun foldKeepsTheRawDeletionWinnerAndLeavesTheReviveToTheMerge() {
        val folded = fold(fileB() to nameB, fileC() to nameC)
        TransportFixtures.connect(folded).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT deleted_at, is_starred, starred_at FROM articles WHERE id = 'a1'").use { rs ->
                    rs.next()
                    assertEquals(180L, rs.getLong(1))
                    assertEquals(1L, rs.getLong(2))
                    assertEquals(260L, rs.getLong(3))
                }
            }
        }
    }

    @Test
    fun aLightRowNeverErasesABody() {
        val light = transportFile(
            deltaName(DEV_B, 2),
            feed("f1"),
            article("a1", "f1", updatedAt = 900, isRead = 1, readAt = 900),
        )
        // Merge: the local body stays; the read state comes in.
        val local = localDb(feed("f1"), article("a1", "f1", updatedAt = 100, content = "<p>kept</p>"), "UPDATE articles SET search_text = 'kept'")
        merge(local, light)
        TransportFixtures.connect(local).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT content, is_read, updated_at FROM articles WHERE id = 'a1'").use { rs ->
                    rs.next()
                    assertEquals("<p>kept</p>", rs.getString(1))
                    assertEquals(1L, rs.getLong(2))
                    assertEquals(100L, rs.getLong(3))
                }
            }
        }
        assertEquals(emptySet(), searchPendingIds(local))

        // Fold, in either order.
        val heavy = transportFile(deltaName(DEV_A, 2), feed("f1"), article("a1", "f1", updatedAt = 100, content = "<p>kept</p>"))
        for (folded in listOf(fold(heavy to deltaName(DEV_A, 2), light to deltaName(DEV_B, 2)), fold(light to deltaName(DEV_B, 2), heavy to deltaName(DEV_A, 2)))) {
            TransportFixtures.connect(folded).use { conn ->
                conn.createStatement().use { st ->
                    st.executeQuery("SELECT content, is_read FROM articles WHERE id = 'a1'").use { rs ->
                        rs.next()
                        assertEquals("<p>kept</p>", rs.getString(1))
                        assertEquals(1L, rs.getLong(2))
                    }
                }
            }
        }
    }

    @Test
    fun mergedBodiesAreRegisteredForSearchText() {
        val local = localDb(
            feed("f1"),
            article("a1", "f1", updatedAt = 100, content = "old"),
            article("a2", "f1", updatedAt = 100, content = "same"),
            "UPDATE articles SET search_text = content", // as the local writes leave it
        )
        merge(
            local,
            transportFile(
                nameA,
                feed("f1"),
                article("a1", "f1", updatedAt = 200, content = "new"), // body changed
                article("a2", "f1", updatedAt = 200, content = "same"), // newer, same body
                article("a3", "f1", updatedAt = 200, summary = "fresh"), // new article
                article("a4", "f1", updatedAt = 200), // new light row: nothing to index
            ),
        )
        assertEquals(setOf("a1", "a3"), searchPendingIds(local))
    }

    @Test
    fun twoDevicesEditingDifferentFieldsOfATagOrFolderBothKeepTheirChange() {
        val rename = transportFile(
            nameA,
            tag("t1", "renamed", updatedAt = 100, nameAt = 200, color = "red", colorAt = 100),
            folder("d1", "Work 2", updatedAt = 100, nameAt = 200, sortOrder = 0, sortAt = 100),
        )
        val recolor = transportFile(
            nameB,
            tag("t1", "news", updatedAt = 100, nameAt = 100, color = "blue", colorAt = 200),
            folder("d1", "Work", updatedAt = 100, nameAt = 100, sortOrder = 9, sortAt = 200),
        )
        val base = arrayOf(tag("t1", "news", color = "red"), folder("d1", "Work"))
        val expected = listOf("renamed|blue", "Work 2|9")
        fun summary(file: File): List<String> = TransportFixtures.connect(file).use { conn ->
            conn.createStatement().use { st ->
                listOf(
                    st.executeQuery("SELECT name || '|' || color FROM tags WHERE id = 't1'").use { it.next(); it.getString(1) },
                    st.executeQuery("SELECT name || '|' || sort_order FROM folders WHERE id = 'd1'").use { it.next(); it.getString(1) },
                )
            }
        }
        assertEquals(expected, summary(localDb(*base).also { merge(it, rename); merge(it, recolor) }))
        assertEquals(expected, summary(localDb(*base).also { merge(it, recolor); merge(it, rename) }))
        assertEquals(expected, summary(fold(rename to nameA, recolor to nameB)))
        assertEquals(expected, summary(localDb(*base).also { merge(it, fold(recolor to nameB, rename to nameA)) }))
    }

    @Test
    fun duplicatesInsideAFoldAreResolvedToTheSameWinnerWhateverTheFoldOrder() {
        // Two devices created the same tag name, folder name and feed URL independently.
        val p = transportFile(
            nameA,
            tag("tp", "dup", updatedAt = 100),
            folder("dp", "Dup", updatedAt = 300),
            feed("fp", url = "https://same/feed", updatedAt = 100),
        )
        val q = transportFile(
            nameB,
            tag("tq", "dup", updatedAt = 200),
            folder("dq", "Dup", updatedAt = 100),
            feed("fq", url = "https://same/feed", updatedAt = 100),
        )
        val pq = fold(p to nameA, q to nameB)
        val qp = fold(q to nameB, p to nameA)
        // The fold keys by id only: both rows of each pair stay.
        assertEquals(dump(pq), dump(qp))
        assertEquals(2, dump(pq).getValue("tags").size)

        val viaPq = localDb().also { merge(it, pq) }
        val viaQp = localDb().also { merge(it, qp) }
        assertEquals(dump(viaPq), dump(viaQp))
        val merged = dump(viaPq)
        // Greatest updated_at, then greatest id.
        assertEquals(listOf("tq"), merged.getValue("tags").map { it.substringBefore('|') })
        assertEquals(listOf("dp"), merged.getValue("folders").map { it.substringBefore('|') })
        assertEquals(listOf("fq"), merged.getValue("feeds").map { it.substringBefore('|') })
    }

    @Test
    fun aTieIsDecidedByValueNotBySide() {
        val low = transportFile(nameA, setting("k", "alpha"))
        val high = transportFile(nameB, setting("k", "beta"))
        val viaLowFirst = localDb().also { merge(it, low); merge(it, high) }
        val viaHighFirst = localDb().also { merge(it, high); merge(it, low) }
        assertEquals(dump(viaLowFirst), dump(viaHighFirst))
        assertTrue(dump(viaLowFirst).getValue("global_settings").single().startsWith("k|beta|"))
        // A local value wins a tie it holds the greater value of, even against the cloud.
        val local = localDb(setting("k", "gamma"))
        merge(local, high)
        assertTrue(dump(local).getValue("global_settings").single().startsWith("k|gamma|"))
        assertNotEquals(dump(local), dump(viaLowFirst))
    }
}
