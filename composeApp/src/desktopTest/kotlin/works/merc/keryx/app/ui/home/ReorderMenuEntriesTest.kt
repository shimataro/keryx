package works.merc.keryx.app.ui.home

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import works.merc.keryx.app.platform.NativeMenuItem

class ReorderMenuEntriesTest {

    @Test
    fun noMovesYieldNoMenuEntries() {
        assertTrue(reorderMenuEntries(emptyList()).isEmpty())
    }

    @Test
    fun eachMoveBecomesAMenuItemWithTheSameLabelAndEffect() {
        val ran = mutableListOf<String>()
        val moves = listOf(
            ReorderMove("Move up") { ran += "up" },
            ReorderMove("Move down") { ran += "down" },
        )

        val entries = reorderMenuEntries(moves).map { it as NativeMenuItem }

        assertEquals(listOf("Move up", "Move down"), entries.map { it.label })
        assertTrue(entries.all { it.enabled })
        entries.forEach { it.onClick() }
        assertEquals(listOf("up", "down"), ran)
    }
}
