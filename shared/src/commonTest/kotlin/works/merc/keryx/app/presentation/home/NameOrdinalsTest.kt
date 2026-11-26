package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Covers [tagOrdinals]/[folderOrdinals]: which rows of a name get an ordinal, and that the order
 * they are numbered in (created_at, then id) is the same on every device, so the same row always
 * shows the same "X (2)".
 */
class NameOrdinalsTest {

    @Test
    fun uniquelyNamedRowsAndSingleRowsGetNoOrdinals() {
        assertEquals(emptyMap(), tagOrdinals(listOf(tag("t1", "Work", 5))))
        assertEquals(emptyMap(), tagOrdinals(listOf(tag("t1", "Work", 5), tag("t2", "Job", 9))))
        assertEquals(emptyMap(), tagOrdinals(emptyList<Tags>()))
    }

    @Test
    fun secondAndLaterRowsOfANameAreNumberedByCreationOrder() {
        val rows = listOf(tag("t1", "Work", 9), tag("t2", "Work", 5), tag("t3", "Work", 7))
        // The input order must not matter: 5 is the first of the name (no entry), then 7, then 9.
        assertEquals(mapOf("t3" to 2, "t1" to 3), tagOrdinals(rows))
    }

    @Test
    fun equalCreationTimesFallBackToIdOrder() {
        val rows = listOf(tag("t2", "Work", 5), tag("t1", "Work", 5), tag("t3", "Work", 5))
        assertEquals(mapOf("t2" to 2, "t3" to 3), tagOrdinals(rows))
    }

    @Test
    fun deletedRowsAreIgnored() {
        // The deleted row neither carries an ordinal nor consumes one: the two live rows number 1st
        // and 2nd of their name, not 2nd and 3rd.
        val rows = listOf(
            tag("t0", "Work", 3, deletedAt = 100L),
            tag("t1", "Work", 5),
            tag("t2", "Work", 9),
        )
        assertEquals(mapOf("t2" to 2), tagOrdinals(rows))
    }

    @Test
    fun assignmentIsDeterministicForTheSameRows() {
        val rows = listOf(
            tag("t1", "Work", 5), tag("t2", "Work", 9), tag("t3", "Work", 7),
            tag("t4", "Job", 1), tag("t5", "Job", 2),
        )
        val expected = tagOrdinals(rows)
        // Same rows in a different order — as a re-sorted query emission would hand them — must
        // produce the same ordinals.
        assertEquals(expected, tagOrdinals(rows.asReversed()))
        assertEquals(expected, tagOrdinals(rows))
        assertTrue(expected.keys.containsAll(setOf("t2", "t3", "t5")))
    }

    @Test
    fun foldersAreNumberedLikeTags() {
        val rows = listOf(folder("d1", "Work", 9), folder("d2", "Work", 5))
        assertEquals(mapOf("d1" to 2), folderOrdinals(rows))
    }

    @Test
    fun labelsCarryEachRowsNameTogetherWithItsOrdinal() {
        val labels = tagFolderLabels(
            tags = listOf(tag("t1", "Work", 9), tag("t2", "Work", 5), tag("t3", "Home", 1)),
            folders = listOf(folder("d1", "Work", 1)),
        )
        assertEquals(OrdinalName("Work", 2), labels.tag("t1", "stale"))
        assertEquals(OrdinalName("Work", null), labels.tag("t2", "stale"))
        assertEquals(OrdinalName("Home", null), labels.tag("t3", "stale"))
        assertEquals(OrdinalName("Work", null), labels.folder("d1", "stale"))
    }

    @Test
    fun aRowTheSnapshotHasNoEntryForShowsItsOwnNameBare() {
        assertEquals(OrdinalName("Fresh", null), TagFolderLabels.EMPTY.tag("t9", "Fresh"))
        assertEquals(OrdinalName("Fresh", null), TagFolderLabels.EMPTY.folder("d9", "Fresh"))
    }
}

private fun tag(id: String, name: String, createdAt: Long, deletedAt: Long? = null): Tags = Tags(
    id = id,
    name = name,
    color = null,
    sort_order = 0L,
    deleted_at = deletedAt,
    updated_at = 0L,
    created_at = createdAt,
    name_updated_at = null,
    color_updated_at = null,
    sort_order_updated_at = null,
    v2_id = null,
)

private fun folder(id: String, name: String, createdAt: Long): Folders = Folders(
    id = id,
    name = name,
    sort_order = 0L,
    deleted_at = null,
    updated_at = 0L,
    created_at = createdAt,
    name_updated_at = null,
    sort_order_updated_at = null,
    v2_id = null,
)
