package works.merc.keryx.app.domain

import works.merc.keryx.app.domain.TransportFixtures.DEV_A
import works.merc.keryx.app.domain.TransportFixtures.connect
import works.merc.keryx.app.domain.TransportFixtures.deltaName
import works.merc.keryx.app.domain.TransportFixtures.exec
import works.merc.keryx.app.domain.TransportFixtures.localDb
import works.merc.keryx.app.domain.TransportFixtures.transportFile
import works.merc.keryx.app.platform.DatabaseMerger
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Merging a transport file's `feed_edges` into the local `ks_feed_edge` ([MergeSql.transport]):
 * normalized, self-edges ignored, and recorded in the outbox exactly once even inside a merge.
 */
class FeedEdgeMergeTest {

    private fun rows(file: File, sql: String): List<String> = connect(file).use { conn ->
        conn.createStatement().use { st ->
            st.executeQuery(sql).use { rs ->
                buildList { while (rs.next()) add((1..rs.metaData.columnCount).joinToString("|") { "${rs.getObject(it)}" }) }
            }
        }
    }

    private fun localEdges(file: File) = rows(file, "SELECT a, b FROM ks_feed_edge ORDER BY a, b")

    private fun feedEdgeOutbox(file: File) = rows(file, "SELECT tbl, pk, pk2 FROM sync_outbox WHERE tbl = 'feed_edges' ORDER BY seq")

    /** Merges as `DeltaSync.mergeFolded` does: inside the `sync_merging` marker. */
    private fun mergeWithMarker(local: File, cloud: File) {
        val statements = listOf("INSERT INTO main.sync_merging (flag) VALUES (1)") + MergeSql.transport + "DELETE FROM main.sync_merging"
        DatabaseMerger.mergeTransport(local.absolutePath, cloud.absolutePath, statements)
    }

    private fun cloudWithEdges() = transportFile(
        deltaName(DEV_A, 1),
        "INSERT INTO feed_edges (a, b) VALUES ('fa', 'fb')",
        "INSERT INTO feed_edges (a, b) VALUES ('fz', 'fy')", // wrong way round
        "INSERT INTO feed_edges (a, b) VALUES ('fq', 'fq')", // an id with itself
    )

    @Test
    fun aMergeRecordsTheFileFeedEdgesNormalizedAndWithoutSelfEdges() {
        val local = localDb()
        TransportFixtures.merge(local, cloudWithEdges())
        assertEquals(listOf("fa|fb", "fy|fz"), localEdges(local))
    }

    @Test
    fun aMergeKeepsEdgesThatAreAlreadyThere() {
        val local = localDb("INSERT INTO ks_feed_edge (a, b) VALUES ('fa', 'fb')", "INSERT INTO ks_feed_edge (a, b) VALUES ('fm', 'fn')")
        TransportFixtures.merge(local, cloudWithEdges())
        TransportFixtures.merge(local, cloudWithEdges())
        assertEquals(listOf("fa|fb", "fm|fn", "fy|fz"), localEdges(local))
    }

    @Test
    fun withCaptureOnEveryNewEdgeLandsInTheOutboxExactlyOnceEvenInsideAMerge() {
        val local = localDb("UPDATE sync_capture SET enabled = 1")
        val cloud = cloudWithEdges()
        mergeWithMarker(local, cloud)
        assertEquals(listOf("feed_edges|fa|fb", "feed_edges|fy|fz"), feedEdgeOutbox(local).sorted())

        // The same file again: every edge is known, so nothing more is recorded.
        mergeWithMarker(local, cloud)
        assertEquals(2, feedEdgeOutbox(local).size)
        assertEquals(listOf("fa|fb", "fy|fz"), localEdges(local))
        // The merge itself recorded no row of a synced table.
        assertEquals(emptyList(), rows(local, "SELECT tbl FROM sync_outbox WHERE tbl <> 'feed_edges'"))
    }

    @Test
    fun withCaptureOffTheMergeRecordsNothingInTheOutbox() {
        val local = localDb()
        mergeWithMarker(local, cloudWithEdges())
        assertEquals(listOf("fa|fb", "fy|fz"), localEdges(local))
        assertEquals(emptyList(), feedEdgeOutbox(local))
    }

    @Test
    fun anEdgeAlreadyHeldIsNotRecordedAgainWhenAFileCarriesItAnotherWayRound() {
        val local = localDb("UPDATE sync_capture SET enabled = 1", "INSERT INTO ks_feed_edge (a, b) VALUES ('fy', 'fz')")
        exec(local, "DELETE FROM sync_outbox")
        mergeWithMarker(local, cloudWithEdges())
        assertEquals(listOf("feed_edges|fa|fb"), feedEdgeOutbox(local))
    }
}
