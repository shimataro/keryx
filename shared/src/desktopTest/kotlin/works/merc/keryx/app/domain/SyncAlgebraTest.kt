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
import works.merc.keryx.app.domain.TransportFixtures.identityState
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
        return dump(db, local = true)
    }

    @Test
    fun mergeIsIdempotent() {
        for (local in listOf(emptyArray(), seededLocal)) {
            for (file in listOf(fileA(), fileB(), fileC())) {
                val once = localDb(*local).also { merge(it, file) }
                val twice = localDb(*local).also { merge(it, file); merge(it, file) }
                assertEquals(dump(once, local = true), dump(twice, local = true))
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
                    assertEquals(1L, rs.getLong(5)) // star 260, fileB
                    // The deletion is a group of its own: fileC's cleanup (180) wins it whatever the
                    // star says. The article stays visible because it is starred.
                    assertEquals(180L, rs.getLong(6))
                    assertEquals(180L, rs.getLong(7))
                    assertEquals(400L, rs.getLong(8)) // the latest cached_at
                    assertEquals(300L, rs.getLong(9)) // updated_at is no group's key: the latest
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
            assertEquals(mergedSequentially(listOf(0, 1, 2), local), dump(viaFold, local = true))
        }
    }

    @Test
    fun foldingFoldsIsAssociative() {
        val left = fold(fold(fileA() to nameA, fileB() to nameB) to TransportFixtures.baseName(DEV_A, 1), fileC() to nameC)
        val right = fold(fileA() to nameA, fold(fileB() to nameB, fileC() to nameC) to TransportFixtures.baseName(DEV_A, 1))
        assertEquals(dump(left), dump(right))
    }

    @Test
    fun foldTakesTheStarAndTheDeletionEachFromItsOwnNewestVersion() {
        val folded = fold(fileB() to nameB, fileC() to nameC)
        TransportFixtures.connect(folded).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT deleted_at, deleted_updated_at, is_starred, starred_at FROM articles WHERE id = 'a1'").use { rs ->
                    rs.next()
                    assertEquals(180L, rs.getLong(1)) // fileC's cleanup
                    assertEquals(180L, rs.getLong(2))
                    assertEquals(1L, rs.getLong(3)) // fileB's star, kept beside the deletion
                    assertEquals(260L, rs.getLong(4))
                }
            }
        }
    }

    // ---- Star and deletion: delete @D, star @S > D, unstar @U > S on three devices ----
    //
    // Each file is what that device's own local writes leave behind (articles.sq): a star touches only
    // the star group, and an unstar of a deleted row also stamps an undelete. This is the counterexample
    // that broke the old "a newer star revives" rule; with two plain last-write-wins groups every merge
    // and fold order agrees.

    private val deleteName = deltaName(DEV_A, 7)
    private val starName = deltaName(DEV_B, 7)
    private val unstarName = deltaName(DEV_C, 7)

    private fun retentionFiles(): List<Pair<File, TransportFileName>> = listOf(
        // Device A's cache cleanup deleted the article.
        transportFile(deleteName, feed("f1"), article("a1", "f1", deletedAt = 100, deletedUpdatedAt = 100)) to deleteName,
        // Device B, after merging that deletion, starred it: the deletion stays, the star keeps it visible.
        transportFile(starName, feed("f1"), article("a1", "f1", isStarred = 1, starredAt = 200, deletedAt = 100, deletedUpdatedAt = 100)) to starName,
        // Device C, after merging the star, unstarred it: an unstar of a deleted row stamps an undelete.
        transportFile(unstarName, feed("f1"), article("a1", "f1", isStarred = 0, starredAt = 300, deletedUpdatedAt = 300)) to unstarName,
    )

    private fun retention(file: File): String = TransportFixtures.connect(file).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery("SELECT is_starred, starred_at, deleted_at, deleted_updated_at FROM articles WHERE id = 'a1'").use { rs ->
                rs.next()
                (1..4).joinToString("|") { "${rs.getObject(it)}" }
            }
        }
    }

    @Test
    fun deleteStarAndUnstarOnThreeDevicesConvergeInEveryMergeAndFoldOrder() {
        // The unstar (300) wins the star group, its undelete (300) the deletion group: not starred, not
        // deleted — visible until the next cache cleanup deletes it again.
        val expected = "0|300|null|300"
        val inputs = retentionFiles()
        for (local in listOf(emptyArray(), arrayOf(feed("f1"), article("a1", "f1")))) {
            for (order in permutations(listOf(0, 1, 2))) {
                val sequential = localDb(*local).also { db -> order.forEach { merge(db, inputs[it].first) } }
                assertEquals(expected, retention(sequential), "merge order $order")
                val folded = fold(*order.map { inputs[it] }.toTypedArray())
                assertEquals(expected, retention(folded), "fold order $order")
                assertEquals(expected, retention(localDb(*local).also { merge(it, folded) }), "merged fold, order $order")
            }
            // Folds of folds, both groupings.
            val (d, s, u) = inputs
            val left = fold(fold(d, s) to TransportFixtures.baseName(DEV_A, 1), u)
            val right = fold(d, fold(s, u) to TransportFixtures.baseName(DEV_A, 1))
            assertEquals(dump(left), dump(right))
            assertEquals(expected, retention(localDb(*local).also { merge(it, left) }))
        }
    }

    @Test
    fun aDeletionNewerThanTheStarKeepsTheStar() {
        // Finding #1: a cleanup on a device that never saw the star (deletion 250) is newer than the
        // star (150), but the two are separate groups — the star stays, and keeps the article visible.
        val delete = transportFile(nameB, feed("f1"), article("a1", "f1", deletedAt = 250, deletedUpdatedAt = 250))
        val star = transportFile(nameA, feed("f1"), article("a1", "f1", isStarred = 1, starredAt = 150))
        for (order in listOf(listOf(star, delete), listOf(delete, star))) {
            val db = localDb()
            order.forEach { merge(db, it) }
            assertEquals("1|150|250|250", retention(db))
            assertEquals("1|150|250|250", retention(fold(*order.map { it to (if (it == star) nameA else nameB) }.toTypedArray())))
        }
    }

    @Test
    fun anUnstarAndANewerDeletionLeaveTheArticleUnstarredAndDeleted() {
        // The user unstarred it (200); another device's cleanup (250) deleted it: neither group keeps it.
        val unstar = transportFile(nameA, feed("f1"), article("a1", "f1", isStarred = 0, starredAt = 200))
        val delete = transportFile(nameB, feed("f1"), article("a1", "f1", isStarred = 0, starredAt = 200, deletedAt = 250, deletedUpdatedAt = 250))
        for (order in listOf(listOf(unstar, delete), listOf(delete, unstar))) {
            val db = localDb(feed("f1"), article("a1", "f1", isStarred = 1, starredAt = 150))
            order.forEach { merge(db, it) }
            assertEquals("0|200|250|250", retention(db))
        }
    }

    @Test
    fun theStarAndDeletionGroupsAreIdempotentAndCommutativeForEveryPair() {
        val inputs = retentionFiles().map { it.first }
        for (file in inputs) {
            val once = localDb().also { merge(it, file) }
            val twice = localDb().also { merge(it, file); merge(it, file) }
            assertEquals(dump(once), dump(twice))
        }
        for ((x, y) in listOf(0 to 1, 0 to 2, 1 to 2)) {
            val xy = localDb().also { merge(it, inputs[x]); merge(it, inputs[y]) }
            val yx = localDb().also { merge(it, inputs[y]); merge(it, inputs[x]) }
            assertEquals(dump(xy), dump(yx), "files $x, $y")
        }
    }

    // ---- The body group's own stamp ----

    @Test
    fun aNewerBodyWinsWhateverReadsOrStarsTheOtherSideMade() {
        // Device A refreshed a newer body (body stamp 500); device B, holding the older body
        // (body stamp 100), later read and starred the article — bumping its updated_at well past
        // A's. B's reading must not make its older body win.
        val newer = transportFile(nameA, feed("f1"), article("a1", "f1", updatedAt = 500, bodyAt = 500, content = "new body"))
        val readElsewhere = transportFile(
            nameB, feed("f1"),
            article("a1", "f1", updatedAt = 900, bodyAt = 100, content = "old body", isRead = 1, readAt = 900, isStarred = 1, starredAt = 900),
        )
        fun body(file: File): String = TransportFixtures.connect(file).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT content, body_updated_at, is_read, is_starred, updated_at FROM articles WHERE id = 'a1'").use { rs ->
                    rs.next()
                    (1..5).joinToString("|") { "${rs.getObject(it)}" }
                }
            }
        }
        val expected = "new body|500|1|1|900"
        assertEquals(expected, body(localDb().also { merge(it, newer); merge(it, readElsewhere) }))
        assertEquals(expected, body(localDb().also { merge(it, readElsewhere); merge(it, newer) }))
        assertEquals(expected, body(fold(newer to nameA, readElsewhere to nameB)))
        assertEquals(expected, body(fold(readElsewhere to nameB, newer to nameA)))
        // A local article holding the old body, read locally after the fact, still takes the newer body.
        val local = localDb(feed("f1"), article("a1", "f1", updatedAt = 950, bodyAt = 100, content = "old body", isRead = 1, readAt = 950))
        merge(local, newer)
        assertEquals("new body|500|1|0|950", body(local))
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
                st.executeQuery("SELECT content, is_read, body_updated_at, updated_at FROM articles WHERE id = 'a1'").use { rs ->
                    rs.next()
                    assertEquals("<p>kept</p>", rs.getString(1))
                    assertEquals(1L, rs.getLong(2))
                    assertEquals(100L, rs.getLong(3)) // the body (and its stamp) stay
                    assertEquals(900L, rs.getLong(4)) // updated_at is no group's key: the latest
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
    fun twoDevicesCreatingTheSameNameConvergeOnOneRowWhateverTheOrder() {
        // A tag's and a folder's id derive from the name (IdGenerator.tagId / folderId), so two
        // devices that created the same name independently hold the same id: the fold keys by id and
        // merges their rows into one, and merging the fold — or the files one by one — lands on that
        // one row in every order. A feed's URL under two ids is one feed (FeedIdentitySql): the fold
        // records the collision as a feed edge, and the merge moves both onto the smallest id.
        val p = transportFile(
            nameA,
            tag(IdGenerator.tagId("dup"), "dup", updatedAt = 100),
            folder(IdGenerator.folderId("Dup"), "Dup", updatedAt = 100),
            feed("fp", url = "https://same/feed", updatedAt = 100),
        )
        val q = transportFile(
            nameB,
            tag(IdGenerator.tagId("dup"), "dup", updatedAt = 200),
            folder(IdGenerator.folderId("Dup"), "Dup", updatedAt = 300),
            feed("fq", url = "https://same/feed", updatedAt = 100),
        )
        val pq = fold(p to nameA, q to nameB)
        val qp = fold(q to nameB, p to nameA)
        assertEquals(dump(pq), dump(qp))
        assertEquals(1, dump(pq).getValue("tags").size)
        assertEquals(1, dump(pq).getValue("folders").size)

        val viaPq = localDb().also { merge(it, pq) }
        val viaQp = localDb().also { merge(it, qp) }
        assertEquals(dump(viaPq), dump(viaQp))
        val merged = dump(viaPq)
        // Each row's greater updated_at wins; the feeds sharing a URL become one feed at the smaller id.
        assertEquals(listOf(IdGenerator.tagId("dup")), merged.getValue("tags").map { it.substringBefore('|') })
        assertEquals(listOf(IdGenerator.folderId("Dup")), merged.getValue("folders").map { it.substringBefore('|') })
        assertEquals(listOf("fp"), merged.getValue("feeds").map { it.substringBefore('|') })
    }

    // ---- A rename onto a name another row holds (no UNIQUE(name) blocks it any more) ----

    private val renameLocal = arrayOf(
        tag("a", "Old", updatedAt = 100, nameAt = 100),
        tag("b", "Taken", updatedAt = 100, nameAt = 100),
        folder("d1", "Work", updatedAt = 100, nameAt = 100),
        folder("d2", "Home", updatedAt = 100, nameAt = 100),
    )

    private fun renameFileA() = transportFile(
        nameA,
        tag("a", "Taken", updatedAt = 100, nameAt = 200), // a takes the name b holds
        folder("d1", "Home", updatedAt = 100, nameAt = 200),
    )

    private fun renameFileB() = transportFile(
        nameB,
        tag("b", "Old", updatedAt = 100, nameAt = 200), // the swap: b takes a's old name
        folder("d2", "Work", updatedAt = 100, nameAt = 200),
    )

    /**
     * The finding this closes: under the old UNIQUE(name) guard, whichever rename merged first was
     * dropped for good (the other row still held the name), so the arrival order decided the names
     * and the fold — which has no such guard — disagreed with the merges. With names not unique,
     * every order applies both renames and ends with the same rows.
     */
    @Test
    fun renamesOntoANameAnotherRowHoldsApplyInEveryOrder() {
        val results = listOf(
            "P, Q" to localDb(*renameLocal).also { db -> merge(db, renameFileA()); merge(db, renameFileB()) },
            "Q, P" to localDb(*renameLocal).also { db -> merge(db, renameFileB()); merge(db, renameFileA()) },
            "fold(P, Q)" to localDb(*renameLocal).also { merge(it, fold(renameFileA() to nameA, renameFileB() to nameB)) },
            "fold(Q, P)" to localDb(*renameLocal).also { merge(it, fold(renameFileB() to nameB, renameFileA() to nameA)) },
        )
        fun names(db: File): List<String> = TransportFixtures.connect(db).use { conn ->
            conn.createStatement().use { st ->
                listOf(
                    "SELECT name FROM tags WHERE id = 'a'",
                    "SELECT name FROM tags WHERE id = 'b'",
                    "SELECT name FROM folders WHERE id = 'd1'",
                    "SELECT name FROM folders WHERE id = 'd2'",
                ).map { sql -> st.executeQuery(sql).use { it.next(); it.getString(1) } }
            }
        }
        for ((label, db) in results) assertEquals(listOf("Taken", "Old", "Home", "Work"), names(db), label)
        val dumps = results.map { (_, db) -> dump(db, local = true) }
        dumps.drop(1).forEach { assertEquals(dumps.first(), it) }
    }

    @Test
    fun separateRowsThatBecomeSameNamedLeaveTheSameRowsWhateverTheOrder() {
        // Device A renames its tag to "Z" — its id stays the one its original name derived — and
        // device B creates a tag named "Z", whose id is the one "Z" derives. Two different rows share
        // the name once the files meet, and every merge and fold order leaves the same two rows with
        // the same names (the UI numbers them).
        val local = arrayOf(tag("a", "X", updatedAt = 100, nameAt = 100))
        val p = transportFile(nameA, tag("a", "Z", updatedAt = 100, nameAt = 200))
        val q = transportFile(nameB, tag(IdGenerator.tagId("Z"), "Z", updatedAt = 300, createdAt = 300, nameAt = 300))
        val results = listOf(
            "P, Q" to localDb(*local).also { db -> merge(db, p); merge(db, q) },
            "Q, P" to localDb(*local).also { db -> merge(db, q); merge(db, p) },
            "fold(P, Q)" to localDb(*local).also { merge(it, fold(p to nameA, q to nameB)) },
            "fold(Q, P)" to localDb(*local).also { merge(it, fold(q to nameB, p to nameA)) },
        )
        fun rowIdsAndNames(db: File): Set<String> = TransportFixtures.connect(db).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT id, name FROM tags ORDER BY id").use { rs ->
                    buildSet { while (rs.next()) add("${rs.getString(1)}|${rs.getString(2)}") }
                }
            }
        }
        val expected = setOf("a|Z", "${IdGenerator.tagId("Z")}|Z")
        for ((label, db) in results) assertEquals(expected, rowIdsAndNames(db), label)
        val dumps = results.map { (_, db) -> dump(db, local = true) }
        dumps.drop(1).forEach { assertEquals(dumps.first(), it) }
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

    /**
     * Two files carry the same feed into different folders at the same `folder_updated_at`, and each
     * file's folder lands in main under its own id (same-named rows are allowed now), so the feed's
     * resolved folder is the raw id the winning file carried. The tie must be decided between the
     * raw ids the files carry (as the fold does: `dz` > `dm`), never by the merge order.
     */
    private fun assertFolderTieConverges(p: File, q: File, expected: String?) {
        val names = listOf(nameA, nameB)
        val local = arrayOf(
            folder("da", "A", updatedAt = 50),
            folder("db", "B", updatedAt = 50),
            feed("f1", updatedAt = 100),
        )
        fun folderOf(db: File): String? = TransportFixtures.connect(db).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT folder_id FROM feeds WHERE id = 'f1'").use { rs -> rs.next(); rs.getString(1) }
            }
        }
        val results = listOf(
            "P then Q" to localDb(*local).also { merge(it, p); merge(it, q) },
            "Q then P" to localDb(*local).also { merge(it, q); merge(it, p) },
            "fold(P, Q)" to localDb(*local).also { merge(it, fold(p to names[0], q to names[1])) },
            "fold(Q, P)" to localDb(*local).also { merge(it, fold(q to names[1], p to names[0])) },
        )
        for ((label, db) in results) assertEquals(expected, folderOf(db), label)
        val dumps = results.map { (_, db) -> dump(db, local = true) }
        dumps.drop(1).forEach { assertEquals(dumps.first(), it) }
    }

    @Test
    fun aFolderTieIsDecidedBetweenRawIdsWhateverTheMergeOrder() {
        assertFolderTieConverges(
            transportFile(nameA, folder("dz", "A", updatedAt = 50), feed("f1", folderId = "dz", folderAt = 500)),
            transportFile(nameB, folder("dm", "B", updatedAt = 50), feed("f1", folderId = "dm", folderAt = 500)),
            expected = "dz",
        )
    }

    @Test
    fun aFolderTieWhoseWinnerResolvesToNoFolderConvergesToo() {
        // dz is dead in its file, so the raw winner resolves to no folder.
        assertFolderTieConverges(
            transportFile(nameA, folder("dz", "A", updatedAt = 60, deletedAt = 60), feed("f1", folderId = "dz", folderAt = 500)),
            transportFile(nameB, folder("dm", "B", updatedAt = 50), feed("f1", folderId = "dm", folderAt = 500)),
            expected = null,
        )
    }

    @Test
    fun twoDevicesAssigningTheirFeedToTheSameNamedFolderSettleOnTheSameId() {
        // Two devices created the same folder name independently — the same name-derived id — and
        // each moved their feed into it. The assignment settles on that id on both devices, whatever
        // the merge order.
        val folderId = IdGenerator.folderId("X")
        val p = transportFile(nameA, folder(folderId, "X", updatedAt = 50), feed("f1", updatedAt = 100, folderId = folderId, folderAt = 500))
        val q = transportFile(nameB, folder(folderId, "X", updatedAt = 50), feed("f1", updatedAt = 100, folderId = folderId, folderAt = 500))
        val deviceA = localDb(
            folder(folderId, "X", updatedAt = 50),
            feed("f1", updatedAt = 100, folderId = folderId, folderAt = 500),
        ).also { merge(it, q) }
        val deviceB = localDb(
            folder(folderId, "X", updatedAt = 50),
            feed("f1", updatedAt = 100, folderId = folderId, folderAt = 500),
        ).also { merge(it, p) }
        fun folderOf(db: File): String = TransportFixtures.connect(db).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT folder_id FROM feeds WHERE id = 'f1'").use { rs -> rs.next(); rs.getString(1) }
            }
        }
        assertEquals(folderId, folderOf(deviceA))
        assertEquals(folderOf(deviceA), folderOf(deviceB))
    }

    // ---- A feed's folder across its folder's deletion and revival ----

    private fun feedFolder(db: File, feedId: String = "f1"): String = TransportFixtures.connect(db).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(
                "SELECT f.folder_id, f.folder_updated_at, r.folder_id, r.folder_updated_at " +
                    "FROM feeds f JOIN ks_feed_folder_raw r ON r.feed_id = f.id WHERE f.id = '$feedId'",
            ).use { rs -> rs.next(); "resolved=${rs.getString(1)}@${rs.getObject(2)} raw=${rs.getString(3)}@${rs.getObject(4)}" }
        }
    }

    /** Every merge and fold order of [p] and [q] into a fresh [local], each with its label. */
    private fun everyOrder(local: Array<String>, p: File, q: File): List<Pair<String, File>> = listOf(
        "P then Q" to localDb(*local).also { merge(it, p); merge(it, q) },
        "Q then P" to localDb(*local).also { merge(it, q); merge(it, p) },
        "fold(P, Q)" to localDb(*local).also { merge(it, fold(p to nameA, q to nameB)) },
        "fold(Q, P)" to localDb(*local).also { merge(it, fold(q to nameB, p to nameA)) },
    )

    /**
     * The former known issue "A feed can be left pointing at a deleted folder across the folder's
     * deletion and revival": with folder d live and f assigned to d at 500, one file keeps d live and
     * one deletes it, both assigning f to d at 500. The assignment's tie is no change, and the
     * folder's deletion re-resolves the feed whichever file arrives last, so every order stores — and
     * exports, as the raw value — the same thing: raw d@500, resolved "no folder".
     */
    @Test
    fun aFolderDeletionAndAnEqualAssignmentConvergeWhateverTheOrder() {
        val local = arrayOf(folder("d", "D", updatedAt = 100), feed("f1", folderId = "d", folderAt = 500))
        val p = transportFile(nameA, folder("d", "D", updatedAt = 100), feed("f1", folderId = "d", folderAt = 500))
        val q = transportFile(nameB, folder("d", "D", updatedAt = 600, deletedAt = 600), feed("f1", folderId = "d", folderAt = 500))
        val results = everyOrder(local, p, q)
        for ((label, db) in results) assertEquals("resolved=null@500 raw=d@500", feedFolder(db), label)
        val dumps = results.map { (_, db) -> dump(db, local = true) }
        dumps.drop(1).forEach { assertEquals(dumps.first(), it) }
    }

    /**
     * A folder deleted by one file and revived by a newer one: the feed still assigned to it is back
     * in it in every order — the revival re-resolves the feeds whose raw assignment names the folder,
     * even when no file carries the feed again.
     */
    @Test
    fun aFolderRevivedByAMergeRestoresItsFeedsWhateverTheOrder() {
        val local = arrayOf(folder("d", "D", updatedAt = 100), feed("f1", folderId = "d", folderAt = 500))
        val p = transportFile(nameA, folder("d", "D", updatedAt = 600, deletedAt = 600), feed("f1", folderId = "d", folderAt = 500))
        val q = transportFile(nameB, folder("d", "D", updatedAt = 700))
        val results = everyOrder(local, p, q)
        for ((label, db) in results) assertEquals("resolved=d@500 raw=d@500", feedFolder(db), label)
        val dumps = results.map { (_, db) -> dump(db, local = true) }
        dumps.drop(1).forEach { assertEquals(dumps.first(), it) }

        // Merged alone, the deletion leaves the feed in "no folder" — and the revival restores it.
        val db = localDb(*local)
        merge(db, p)
        assertEquals("resolved=null@500 raw=d@500", feedFolder(db))
        merge(db, q)
        assertEquals("resolved=d@500 raw=d@500", feedFolder(db))
    }

    /**
     * An assignment to a folder that is deleted here is recorded raw: the feed shows no folder, but a
     * later revival of the folder by a file that does not carry the feed puts it back, and a newer
     * move elsewhere still wins.
     */
    @Test
    fun anAssignmentToADeletedFolderIsKeptRawAndResolvedLater() {
        val db = localDb(folder("d", "D", updatedAt = 600, deletedAt = 600), feed("f1"))
        merge(db, transportFile(nameA, folder("d", "D", updatedAt = 100), feed("f1", folderId = "d", folderAt = 500)))
        assertEquals("resolved=null@500 raw=d@500", feedFolder(db), "the local deletion is newer")
        merge(db, transportFile(nameB, folder("d", "D", updatedAt = 700)))
        assertEquals("resolved=d@500 raw=d@500", feedFolder(db))
        merge(db, transportFile(nameC, folder("e", "E", updatedAt = 100), feed("f1", folderId = "e", folderAt = 800)))
        assertEquals("resolved=e@800 raw=e@800", feedFolder(db))
    }

    /**
     * A transport file's reference to a folder main does not hold (never in a valid file — check 9 —
     * but a merge must not depend on it) is stored as the file wrote it — a raw assignment is never
     * rewritten, so a tie is settled by the same values in every merge and fold — and resolves to "no
     * folder": the resolution never writes a dangling `feeds.folder_id`.
     */
    @Test
    fun aReferenceToAFolderMainLacksIsKeptRawAndResolvesToNoFolder() {
        val db = localDb(feed("f1"))
        merge(db, transportFile(nameA, feed("f1", folderId = "gone", folderAt = 500)))
        assertEquals("resolved=null@500 raw=gone@500", feedFolder(db))
        TransportFixtures.connect(db).use { conn ->
            conn.createStatement().use { st ->
                st.executeQuery("SELECT COUNT(*) FROM pragma_foreign_key_check").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) }
            }
        }
    }

    // ---- Feed identity: the same URL under different ids ----

    private val sharedUrl = "https://shared/feed"

    /**
     * Two versions of one feed — ids `fp` < `fq`, one URL — with every stamp equal, so each field group
     * is a tie decided by value, and with articles (one colliding key `g1`), tag links and raw folders.
     */
    private fun versionOfFeed(feedId: String, suffix: String, articleSuffix: String) = arrayOf(
        tag("t1", "news"), tag("t2", "tech"), folder("d1", "Work"), folder("d2", "Home"),
        feed(
            feedId, url = sharedUrl, title = "Title $suffix", updatedAt = 100, createdAt = if (suffix == "P") 80 else 90,
            folderId = if (suffix == "P") "d1" else "d2", folderAt = 200, sortOrder = if (suffix == "P") 1 else 2, sortAt = 200,
            customTitle = "Custom $suffix", customAt = 200,
        ),
        feedTag(feedId, "t1", updatedAt = 100, deletedAt = if (suffix == "P") null else 100),
        feedTag(feedId, "t2", updatedAt = 100),
        article(
            "a-$articleSuffix-1", feedId, guid = "g1", title = "Article $suffix", content = "body $suffix", updatedAt = 100, bodyAt = 100,
            isRead = if (suffix == "P") 1 else 0, readAt = 200, isStarred = if (suffix == "P") 0 else 1, starredAt = 200,
            cachedAt = if (suffix == "P") 100 else 150, createdAt = if (suffix == "P") 70 else 60,
        ),
        article("a-$articleSuffix-2", feedId, guid = "g-own-$suffix", updatedAt = 100),
    )

    private val feedP = deltaName(DEV_A, 1)
    private val feedQ = deltaName(DEV_B, 1)
    private val feedEdge = deltaName(DEV_C, 1)

    private fun filePForFeeds() = transportFile(feedP, *versionOfFeed("fp", "P", "p"))
    private fun fileQForFeeds() = transportFile(feedQ, *versionOfFeed("fq", "Q", "q"))
    private fun fileEdgeOnly() = transportFile(feedEdge, "INSERT INTO feed_edges (a, b) VALUES ('fp', 'fq')")

    @Test
    fun sameUrlFeedsUnderDifferentIdsReachTheSameStateWhicheverIsMergedFirstAndWhenFolded() {
        val pThenQ = localDb().also { merge(it, filePForFeeds()); merge(it, fileQForFeeds()) }
        val qThenP = localDb().also { merge(it, fileQForFeeds()); merge(it, filePForFeeds()) }
        val folded = localDb().also { merge(it, fold(filePForFeeds() to feedP, fileQForFeeds() to feedQ)) }
        val foldedBackwards = localDb().also { merge(it, fold(fileQForFeeds() to feedQ, filePForFeeds() to feedP)) }
        // A device that already holds one version, receiving the other as its file.
        val holdsP = localDb(*versionOfFeed("fp", "P", "p")).also { merge(it, fileQForFeeds()) }
        val holdsQ = localDb(*versionOfFeed("fq", "Q", "q")).also { merge(it, filePForFeeds()) }
        // A device holding the greater id and receiving both, and one holding the smaller.
        val holdsQReceivesBoth = localDb(*versionOfFeed("fq", "Q", "q")).also { merge(it, filePForFeeds()); merge(it, fileQForFeeds()) }

        val reference = identityState(pThenQ)
        for ((label, db) in listOf(
            "Q then P" to qThenP, "fold(P, Q)" to folded, "fold(Q, P)" to foldedBackwards,
            "holds P, receives Q" to holdsP, "holds Q, receives P" to holdsQ, "holds Q, receives both" to holdsQReceivesBoth,
        )) {
            assertEquals(reference, identityState(db), label)
        }
        // One feed on the smallest id; both versions' articles under it, the colliding key folded to the
        // smallest article id; the two feeds' ties decided by value (the greater title, sort order).
        assertEquals(listOf("fp"), TransportFixtures.rows(pThenQ, "SELECT id FROM feeds"))
        assertEquals(
            listOf("a-p-1|fp|g1", "a-p-2|fp|g-own-P", "a-q-2|fp|g-own-Q"),
            TransportFixtures.rows(pThenQ, "SELECT id, feed_id, guid FROM articles ORDER BY id"),
        )
        assertEquals(
            listOf("fp|Title Q|80|2|Custom Q"),
            TransportFixtures.rows(pThenQ, "SELECT id, title, created_at, sort_order, custom_title FROM feeds"),
        )
        assertEquals(listOf("fp|fq"), reference.getValue("ks_feed_edge"))
        assertEquals(listOf("article|a-q-1|a-p-1", "feed|fq|fp"), reference.getValue("ks_id_remap"))
        // The tag links of the two versions fold per (feed, tag): the detach (tie) outranks the attach.
        assertEquals(listOf("fp|t1|100|100", "fp|t2|100|null"), TransportFixtures.rows(pThenQ, "SELECT feed_id, tag_id, updated_at, deleted_at FROM feed_tags ORDER BY 1, 2"))
    }

    @Test
    fun mergeAndFoldAreCommutativeOverEveryOrderOfTwoFeedVersionsAndAnEdgeOnlyFile() {
        val inputs = listOf(::filePForFeeds to feedP, ::fileQForFeeds to feedQ, ::fileEdgeOnly to feedEdge)
        val merged = permutations(listOf(0, 1, 2)).map { order ->
            order to localDb().also { db -> order.forEach { merge(db, inputs[it].first()) } }
        }
        val reference = identityState(merged.first().second)
        for ((order, db) in merged) assertEquals(reference, identityState(db), "merge order $order")
        // With either version already held.
        for (held in listOf(versionOfFeed("fp", "P", "p"), versionOfFeed("fq", "Q", "q"))) {
            for (order in permutations(listOf(0, 1, 2))) {
                val db = localDb(*held).also { d -> order.forEach { merge(d, inputs[it].first()) } }
                assertEquals(reference, identityState(db), "held ${held.size}, merge order $order")
            }
        }
        for (order in permutations(listOf(0, 1, 2))) {
            val folded = fold(*order.map { inputs[it].first() to inputs[it].second }.toTypedArray())
            assertEquals(reference, identityState(localDb().also { merge(it, folded) }), "fold order $order")
        }
        // Folding folds is associative, and a merge of a fold of folds equals the one-by-one merge.
        val left = fold(fold(filePForFeeds() to feedP, fileQForFeeds() to feedQ) to TransportFixtures.baseName(DEV_A, 1), fileEdgeOnly() to feedEdge)
        val right = fold(filePForFeeds() to feedP, fold(fileQForFeeds() to feedQ, fileEdgeOnly() to feedEdge) to TransportFixtures.baseName(DEV_A, 1))
        assertEquals(identityState(localDb().also { merge(it, left) }), identityState(localDb().also { merge(it, right) }))
        assertEquals(reference, identityState(localDb().also { merge(it, left) }))
    }

    @Test
    fun feedIdentityMergeIsIdempotent() {
        for (file in listOf(filePForFeeds(), fileQForFeeds(), fileEdgeOnly())) {
            for (local in listOf(emptyArray(), versionOfFeed("fp", "P", "p"), versionOfFeed("fq", "Q", "q"))) {
                val once = localDb(*local).also { merge(it, file) }
                val twice = localDb(*local).also { merge(it, file); merge(it, file) }
                assertEquals(identityState(once), identityState(twice))
            }
        }
    }

    /** The same feed on every device, two article ids for one `(feed, guid)`: one article on the smallest id. */
    @Test
    fun articlesSharingAKeyUnderDifferentIdsConvergeOnTheSmallestIdInEveryOrder() {
        fun withId(id: String, read: Boolean, readAt: Long, body: String, bodyAt: Long) = arrayOf(
            feed("f1", url = sharedUrl),
            article(id, "f1", guid = "g", content = body, updatedAt = bodyAt, bodyAt = bodyAt, isRead = if (read) 1 else 0, readAt = readAt, cachedAt = bodyAt),
        )
        val x = deltaName(DEV_A, 1)
        val y = deltaName(DEV_B, 1)
        fun fileX() = transportFile(x, *withId("a2", read = true, readAt = 300, body = "body X", bodyAt = 100))
        fun fileY() = transportFile(y, *withId("a1", read = false, readAt = 100, body = "body Y", bodyAt = 200))
        val results = listOf(
            localDb().also { merge(it, fileX()); merge(it, fileY()) },
            localDb().also { merge(it, fileY()); merge(it, fileX()) },
            localDb().also { merge(it, fold(fileX() to x, fileY() to y)) },
            localDb().also { merge(it, fold(fileY() to y, fileX() to x)) },
            localDb(*withId("a2", read = true, readAt = 300, body = "body X", bodyAt = 100)).also { merge(it, fileY()) },
            localDb(*withId("a1", read = false, readAt = 100, body = "body Y", bodyAt = 200)).also { merge(it, fileX()) },
        )
        val reference = identityState(results.first())
        results.forEachIndexed { i, db -> assertEquals(reference, identityState(db), "variant $i") }
        // The smallest id, the newer body (Y's) and the newer read (X's).
        assertEquals(listOf("a1|body Y|1|300"), TransportFixtures.rows(results.first(), "SELECT id, content, is_read, read_at FROM articles"))
        assertEquals(listOf("article|a2|a1"), reference.getValue("ks_id_remap"))
        assertEquals(emptyList(), reference.getValue("ks_feed_edge"))
    }
}
