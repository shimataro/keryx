package works.merc.keryx.app.presentation.home

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ArticleWriteGenerationsTest {

    /** A feed merge moving an article carries its latest write over; of two meeting on one id the newer stands. */
    @Test
    fun remapMovesTheLatestWriteOntoTheNewIdKeepingTheNewer() {
        val generations = ArticleWriteGenerations()
        val older = generations.stamp(listOf("new"))
        val moved = generations.stamp(listOf("old"))

        generations.remap(mapOf("old" to "new"))

        assertTrue(generations.isLatest("new", moved))
        assertFalse(generations.isLatest("new", older))
        assertFalse(generations.isLatest("old", moved))

        generations.release(listOf("new"), moved)
        assertFalse(generations.isLatest("new", moved))
    }

    @Test
    fun remapKeepsANewerWriteAlreadyUnderTheNewId() {
        val generations = ArticleWriteGenerations()
        val moved = generations.stamp(listOf("old"))
        val newer = generations.stamp(listOf("new"))

        generations.remap(mapOf("old" to "new"))

        assertTrue(generations.isLatest("new", newer))
        assertFalse(generations.isLatest("new", moved))
    }
}
