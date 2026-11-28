package works.merc.keryx.app.domain

import works.merc.keryx.app.inMemoryDb
import works.merc.keryx.app.insertFolder
import works.merc.keryx.app.insertTag
import works.merc.keryx.app.presentation.home.folderOrdinals
import works.merc.keryx.app.presentation.home.tagOrdinals
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tag and folder lists' order is decided by synced values alone — sort_order, then the name
 * (NOCASE, then byte-wise), then created_at, then id. Same-named rows coexist (a rename crossing a
 * same-named creation) and two offline creations take the same sort_order, so without the trailing
 * tiebreakers the display order would follow each device's own rowid order — and stop agreeing with
 * the ordinals, which number same-named rows by created_at and then id (NameOrdinals).
 */
class TagFolderListOrderTest {

    @Test
    fun sameNamedTagsListAndNumberIdenticallyWhateverOrderTheirRowsWereInsertedIn() {
        val (driverA, dbA) = inMemoryDb()
        val (driverB, dbB) = inMemoryDb()
        try {
            // The same two tags — same name, same sort_order (two offline creations) — inserted in
            // opposite orders, so their rowids differ.
            dbA.insertTag("t-1", "Work", now = 100L)
            dbA.insertTag("t-2", "Work", now = 200L)
            dbB.insertTag("t-2", "Work", now = 200L)
            dbB.insertTag("t-1", "Work", now = 100L)

            val orderA = dbA.tagsQueries.watchAll().executeAsList()
            val orderB = dbB.tagsQueries.watchAll().executeAsList()
            assertEquals(listOf("t-1", "t-2"), orderA.map { it.id })
            assertEquals(orderA.map { it.id }, orderB.map { it.id })

            // The position order of the duplicates is the order the ordinals number them by: the
            // earlier-created row leads, the second one wears "(2)".
            assertEquals(mapOf("t-2" to 2), tagOrdinals(orderA))
        } finally {
            driverA.close()
            driverB.close()
        }
    }

    @Test
    fun tagNamesThatOnlyDifferByCaseStillOrderIdenticallyOnEveryDevice() {
        val (driverA, dbA) = inMemoryDb()
        val (driverB, dbB) = inMemoryDb()
        try {
            // NOCASE ties "Work" and "work"; the byte-wise name then orders them the same everywhere.
            dbA.insertTag("t-upper", "Work", now = 100L)
            dbA.insertTag("t-lower", "work", now = 100L)
            dbB.insertTag("t-lower", "work", now = 100L)
            dbB.insertTag("t-upper", "Work", now = 100L)

            val orderA = dbA.tagsQueries.watchAll().executeAsList().map { it.id }
            val orderB = dbB.tagsQueries.watchAll().executeAsList().map { it.id }
            assertEquals(listOf("t-upper", "t-lower"), orderA)
            assertEquals(orderA, orderB)
        } finally {
            driverA.close()
            driverB.close()
        }
    }

    @Test
    fun sameNamedFoldersListAndNumberIdenticallyWhateverOrderTheirRowsWereInsertedIn() {
        val (driverA, dbA) = inMemoryDb()
        val (driverB, dbB) = inMemoryDb()
        try {
            dbA.insertFolder("f-1", "Work", now = 100L)
            dbA.insertFolder("f-2", "Work", now = 200L)
            dbB.insertFolder("f-2", "Work", now = 200L)
            dbB.insertFolder("f-1", "Work", now = 100L)

            val orderA = dbA.foldersQueries.watchAll().executeAsList()
            val orderB = dbB.foldersQueries.watchAll().executeAsList()
            assertEquals(listOf("f-1", "f-2"), orderA.map { it.id })
            assertEquals(orderA.map { it.id }, orderB.map { it.id })
            assertEquals(mapOf("f-2" to 2), folderOrdinals(orderA))

            // reorderFolders reads this same order to compute the new sort orders.
            val reorderA = dbA.foldersQueries.orderForReorder(dragged = "f-1", target = null).executeAsList().map { it.id }
            val reorderB = dbB.foldersQueries.orderForReorder(dragged = "f-1", target = null).executeAsList().map { it.id }
            assertEquals(listOf("f-1", "f-2"), reorderA)
            assertEquals(reorderA, reorderB)
        } finally {
            driverA.close()
            driverB.close()
        }
    }
}
