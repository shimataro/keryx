package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class ReorderUtilTest {

    @Test
    fun reorderIdsMovesItemBeforeTarget() {
        val result = reorderIds(listOf("a", "b", "c"), draggedId = "c", targetId = "b")
        assertEquals(listOf("a", "c", "b"), result)
    }

    @Test
    fun reorderIdsMovesItemToFrontWhenTargetIsFirst() {
        val result = reorderIds(listOf("a", "b", "c"), draggedId = "c", targetId = "a")
        assertEquals(listOf("c", "a", "b"), result)
    }

    @Test
    fun reorderIdsMovesItemToEndWhenTargetIsNull() {
        val result = reorderIds(listOf("a", "b", "c"), draggedId = "a", targetId = null)
        assertEquals(listOf("b", "c", "a"), result)
    }

    @Test
    fun reorderIdsDraggingItemOntoItselfIsNoOp() {
        val result = reorderIds(listOf("a", "b", "c"), draggedId = "b", targetId = "b")
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun reorderIdsInsertsItemNotInCurrentOrder() {
        // Simulates moving in a feed from another folder group: "x" isn't part of this group yet.
        val result = reorderIds(listOf("a", "b"), draggedId = "x", targetId = "b")
        assertEquals(listOf("a", "x", "b"), result)
    }

    @Test
    fun reorderIdsInsertsItemNotInCurrentOrderAtEndWhenTargetIsNull() {
        val result = reorderIds(listOf("a", "b"), draggedId = "x", targetId = null)
        assertEquals(listOf("a", "b", "x"), result)
    }

    @Test
    fun reorderIdsWithUnknownTargetFallsBackToEnd() {
        // Defensive case: targetId doesn't exist in currentOrder (e.g. stale reference).
        val result = reorderIds(listOf("a", "b"), draggedId = "a", targetId = "gone")
        assertEquals(listOf("b", "a"), result)
    }

    @Test
    fun reorderIdsOnSingleElementListIsNoOp() {
        val result = reorderIds(listOf("a"), draggedId = "a", targetId = null)
        assertEquals(listOf("a"), result)
    }

    @Test
    fun reorderIdsOnEmptyListInsertsTheDraggedItem() {
        val result = reorderIds(emptyList(), draggedId = "a", targetId = null)
        assertEquals(listOf("a"), result)
    }
}
