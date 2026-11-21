package works.merc.keryx.app.domain

import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.NetworkCondition
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SchemaVersionException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** [BaseCoverage], [SyncBaseCache], [SyncPointer] and [SendAttempt]: the pure rules of the delta sync. */
class TransportCoverageTest {
    private val gen = "0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e"
    private val a = "a1b2c3d4-e5f6-4789-8abc-def012345678"
    private val b = "b1b2c3d4-e5f6-4789-8abc-def012345678"

    private fun delta(device: String, seq: Long, hash: String = "0123456789ab") =
        TransportFileName(gen, TransportFileKind.DELTA, device, seq, hash)

    private fun base(n: Long, vector: Map<String, Long>, covered: Map<String, Long?> = emptyMap(), createdAt: Long = 0, device: String = a) =
        BaseCoverage(TransportFileName(gen, TransportFileKind.BASE, device, n, "0123456789ab").fileName, vector, covered, createdAt)

    @Test
    fun aBaseCoversByItsVectorOrByName() {
        val coverage = base(1, mapOf(a to 3), mapOf(delta(b, 2).fileName to 10))
        assertTrue(coverage.covers(delta(a, 1)))
        assertTrue(coverage.covers(delta(a, 3)))
        assertFalse(coverage.covers(delta(a, 4)))
        assertTrue(coverage.covers(delta(b, 2)))
        assertFalse(coverage.covers(delta(b, 1)))
        assertTrue(coverage.coversSeq(b, 2))
        assertFalse(coverage.coversSeq(b, 1))
    }

    @Test
    fun aVectorDoesNotImplyADuplicateItListsNoneOf() {
        val listed = delta(a, 2)
        val coverage = base(1, mapOf(a to 3), mapOf(listed.fileName to 10))
        assertTrue(coverage.covers(listed))
        assertFalse(coverage.covers(delta(a, 2, "ba9876543210")), "a clone's duplicate the base never folded")
        assertTrue(coverage.covers(delta(a, 1)))
    }

    @Test
    fun aDeltaIsCollectableOnceOldEnough() {
        val explicit = delta(a, 5)
        val coverage = base(1, mapOf(a to 3), mapOf(explicit.fileName to 1_000, delta(a, 6).fileName to null), createdAt = 3_000)
        assertTrue(coverage.coveredBefore(delta(a, 2), cutoff = 0), "an implied delta is old by construction")
        assertTrue(coverage.coveredBefore(explicit, cutoff = 1_000))
        assertFalse(coverage.coveredBefore(explicit, cutoff = 999))
        assertFalse(coverage.coveredBefore(delta(a, 6), cutoff = 2_999), "unknown time: the base's own")
        assertTrue(coverage.coveredBefore(delta(a, 6), cutoff = 3_000))
        assertFalse(coverage.coveredBefore(delta(a, 9), cutoff = Long.MAX_VALUE), "not covered at all")
    }

    @Test
    fun dominationIsAStrictOrder() {
        val small = base(1, mapOf(a to 2), mapOf(delta(b, 4).fileName to 0))
        val large = base(2, mapOf(a to 3, b to 4))
        assertTrue(large.dominates(small))
        assertFalse(small.dominates(large))

        val equalLow = base(3, mapOf(a to 3, b to 4), device = a)
        val equalHigh = base(3, mapOf(a to 3, b to 4), device = b)
        assertTrue(equalHigh.dominates(equalLow) != equalLow.dominates(equalHigh), "equal coverage: exactly one dominates, by name")
        assertFalse(equalLow.dominates(equalLow))

        val incomparable = base(4, mapOf(a to 5))
        assertFalse(incomparable.dominates(large))
        assertFalse(large.dominates(incomparable))
    }

    @Test
    fun aBaseDoesNotDominateOneListingADuplicateItLacks() {
        val withClone = base(1, mapOf(a to 1), mapOf(delta(a, 1).fileName to 0, delta(a, 1, "ba9876543210").fileName to 0))
        val implied = base(2, mapOf(a to 2))
        assertFalse(implied.dominates(withClone), "a vector alone cannot vouch for both copies")
    }

    @Test
    fun theBaseCacheRoundTripsAndDropsWhatIsNotABase() {
        val coverage = base(1, mapOf(a to 3), mapOf(delta(b, 2).fileName to 10, delta(b, 3).fileName to null), createdAt = 7)
        assertEquals(mapOf(coverage.name to coverage), SyncBaseCache.decode(SyncBaseCache.encode(listOf(coverage))))
        assertEquals(emptyMap(), SyncBaseCache.decode("{not json"))
        assertEquals(emptyMap(), SyncBaseCache.decode(null))
        val notABase = coverage.copy(name = delta(a, 1).fileName)
        assertEquals(emptyMap(), SyncBaseCache.decode(SyncBaseCache.encode(listOf(notABase))))
    }

    @Test
    fun thePointerRoundTripsAndRejectsWhatItCannotUse() {
        assertEquals(Result.Ok(gen), SyncPointer.parse(SyncPointer.encode(gen)))
        assertEquals(Result.Ok(gen), SyncPointer.parse("""{"format":3,"generation":"$gen","extra":1}"""))
        for (bad in listOf("", "[]", """{"generation":"$gen"}""", """{"format":"3","generation":"$gen"}""", """{"format":2,"generation":"$gen"}""",
            """{"format":3}""", """{"format":3,"generation":"NOT-A-UUID"}""", """{"format":3,"generation":3}""")) {
            val result = SyncPointer.parse(bad)
            assertIs<Result.Err>(result, bad)
            assertIs<CloudDataIncompatibleException>(result.exception, bad)
        }
        val newer = SyncPointer.parse("""{"format":4}""")
        assertIs<Result.Err>(newer)
        assertIs<SchemaVersionException>(newer.exception)
    }

    @Test
    fun aSendAttemptRoundTripsAndRejectsMalformedRecords() {
        val name = delta(a, 7)
        for (attempt in listOf(SendAttempt(7, name, 42, false), SendAttempt(7, name, null, true))) {
            assertEquals(attempt, SendAttempt.decode(attempt.encode()))
        }
        assertNull(SendAttempt.decode(null))
        assertNull(SendAttempt.decode("7|${name.fileName}|42"))
        assertNull(SendAttempt.decode("8|${name.fileName}|42|0"), "the number must match the name")
        assertNull(SendAttempt.decode("7|not-a-name|42|0"))
        assertNull(SendAttempt.decode("7|${name.fileName}|x|0"))
        assertNull(SendAttempt.decode("7|${name.fileName}|42|2"))
    }

    @Test
    fun foldingIsAllowedOnlyOnAnUnrestrictedNetwork() {
        assertTrue(NetworkUsagePolicy.compactionAllowed(NetworkCondition(metered = false, dataSaver = false)))
        assertFalse(NetworkUsagePolicy.compactionAllowed(NetworkCondition(metered = true, dataSaver = false)))
        assertFalse(NetworkUsagePolicy.compactionAllowed(NetworkCondition(metered = false, dataSaver = true)))
    }
}
