package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Pure parts of [TransportSchema]: file names, migrations, vectors and the generated DDL. */
class TransportSchemaTest {

    private val gen = "0f1e2d3c-4b5a-4968-8778-695a4b3c2d1e"
    private val dev = "a1b2c3d4-e5f6-4789-8abc-def012345678"

    @Test
    fun deltaAndBaseNamesRoundTrip() {
        for (kind in TransportFileKind.entries) {
            val name = TransportFileName(gen, kind, dev, 42, 1_700_000_000_123, "0123456789ab")
            assertEquals("ks-$gen-${kind.token}-$dev-42-1700000000123-0123456789ab.db.gz", name.fileName)
            assertEquals(name, TransportFileName.parse(name.fileName))
        }
    }

    @Test
    fun theLargestNumberRoundTrips() {
        val name = TransportFileName(gen, TransportFileKind.DELTA, dev, 999_999_999_999_999_999L, 999_999_999_999_999_999L, "ffffffffffff")
        assertEquals(name, TransportFileName.parse(name.fileName))
        val epoch = TransportFileName(gen, TransportFileKind.DELTA, dev, 1, 0, "ffffffffffff")
        assertEquals(epoch, TransportFileName.parse(epoch.fileName))
    }

    @Test
    fun anythingElseIsRejected() {
        val good = "ks-$gen-d-$dev-7-1000-0123456789ab.db.gz"
        assertNotNull(TransportFileName.parse(good))
        val bad = listOf(
            "",
            "keryx.db.gz",
            "keryx-sync.json",
            "ks-$gen-x-$dev-7-1000-0123456789ab.db.gz", // unknown kind
            "ks-$gen-d-$dev-0-1000-0123456789ab.db.gz", // zero
            "ks-$gen-d-$dev-07-1000-0123456789ab.db.gz", // leading zero
            "ks-$gen-d-$dev--7-1000-0123456789ab.db.gz", // negative
            "ks-$gen-d-$dev-1234567890123456789-1000-0123456789ab.db.gz", // 19 digits
            "ks-$gen-d-$dev-7-0123456789ab.db.gz", // no creation time
            "ks-$gen-d-$dev-7-01000-0123456789ab.db.gz", // creation time with a leading zero
            "ks-$gen-d-$dev-7--1000-0123456789ab.db.gz", // negative creation time
            "ks-$gen-d-$dev-7-1234567890123456789-0123456789ab.db.gz", // 19-digit creation time
            "ks-$gen-d-$dev-7-1000-0123456789AB.db.gz", // uppercase hash
            "ks-$gen-d-$dev-7-1000-0123456789a.db.gz", // 11-digit hash
            "ks-$gen-d-$dev-7-1000-0123456789abc.db.gz", // 13-digit hash
            "ks-${gen.uppercase()}-d-$dev-7-1000-0123456789ab.db.gz", // uppercase generation
            "ks-${gen.replace("-", "")}-d-$dev-7-1000-0123456789ab.db.gz", // no dashes
            "ks-$gen-d-$dev-7-1000-0123456789ab.db", // no .gz
            "ks-$gen-d-$dev-7-1000-0123456789ab.db.gz.bak",
            "x$good",
            "$good ",
            "ks-$gen-d-$dev-7-1000-0123456789ab.db.gz\n",
            "ks-$gen-d-../../$dev-7-1000-0123456789ab.db.gz",
            "ks-$gen-d-$dev-7-1000-0123456789ab'.db.gz",
        )
        for (name in bad) assertNull(TransportFileName.parse(name), name)
    }

    @Test
    fun constructionEnforcesTheSameRulesAsParsing() {
        assertFailsWith<IllegalArgumentException> { TransportFileName("not-a-uuid", TransportFileKind.DELTA, dev, 1, 1_000, "0123456789ab") }
        assertFailsWith<IllegalArgumentException> { TransportFileName(gen, TransportFileKind.DELTA, dev.uppercase(), 1, 1_000, "0123456789ab") }
        assertFailsWith<IllegalArgumentException> { TransportFileName(gen, TransportFileKind.DELTA, dev, 0, 1_000, "0123456789ab") }
        assertFailsWith<IllegalArgumentException> { TransportFileName(gen, TransportFileKind.DELTA, dev, 1, 1_000, "0123456789") }
        assertFailsWith<IllegalArgumentException> { TransportFileName(gen, TransportFileKind.DELTA, dev, 1, -1, "0123456789ab") }
        assertFailsWith<IllegalArgumentException> { TransportFileName.prefixOf("x") }
        assertEquals("ks-$gen-", TransportFileName.prefixOf(gen))
    }

    @Test
    fun metaRowsCarryTheFileIdentity() {
        val rows = TransportMeta(gen, TransportFileKind.BASE, dev, 3, 1_700_000_000_000).rows
        assertEquals(TransportSchema.MetaKeys.ALL.toSet(), rows.keys)
        assertEquals("base", rows[TransportSchema.MetaKeys.KIND])
        assertEquals("3", rows[TransportSchema.MetaKeys.NUMBER])
        assertEquals(TransportSchema.VERSION.toString(), rows[TransportSchema.MetaKeys.FORMAT])
    }

    @Test
    fun migrationPathFollowsContiguousSteps() {
        val steps = listOf(
            TransportSchema.Migration(1, 2, listOf("a")),
            TransportSchema.Migration(2, 3, listOf("b")),
        )
        assertEquals(emptyList(), TransportSchema.migrationPath(3, 3, steps))
        assertEquals(steps, TransportSchema.migrationPath(1, 3, steps))
        assertEquals(steps.drop(1), TransportSchema.migrationPath(2, 3, steps))
        assertNull(TransportSchema.migrationPath(0, 3, steps))
        assertNull(TransportSchema.migrationPath(4, 3, steps))
        assertNull(TransportSchema.migrationPath(1, 3, steps.take(1)))
        // The current registry: version 2 needs nothing, version 1 migrates up to 2.
        assertEquals(emptyList(), TransportSchema.migrationPath(TransportSchema.VERSION))
        assertEquals(TransportSchema.MIGRATIONS, TransportSchema.migrationPath(1))
        assertNull(TransportSchema.migrationPath(0))
    }

    @Test
    fun vectorsCombineByMaximumAndExtendOnlyOverContiguousDeltas() {
        assertEquals(mapOf("a" to 5L, "b" to 3L, "c" to 1L), TransportVector.combine(mapOf("a" to 5L, "b" to 1L), mapOf("b" to 3L, "c" to 1L)))

        val other = "b1b2c3d4-e5f6-4789-8abc-def012345678"
        fun delta(device: String, seq: Long) = TransportFileName(gen, TransportFileKind.DELTA, device, seq, 1_000, "0123456789ab")
        val covered = listOf(delta(dev, 3), delta(dev, 4), delta(dev, 6), delta(other, 1), delta(other, 3))
        assertEquals(
            mapOf(dev to 4L, other to 1L),
            TransportVector.extend(mapOf(dev to 2L), covered),
        )
        // A gap right above the vector leaves it as it is.
        assertEquals(mapOf(dev to 1L), TransportVector.extend(mapOf(dev to 1L), listOf(delta(dev, 3))))
        // Bases in the covered list do not extend anything.
        assertEquals(emptyMap(), TransportVector.extend(emptyMap(), listOf(TransportFileName(gen, TransportFileKind.BASE, dev, 1, 1_000, "0123456789ab"))))
    }

    @Test
    fun theDdlHasNoUniqueOrForeignKeyConstraint() {
        assertEquals(TransportSchema.EXPECTED_TABLES.getValue(TransportSchema.VERSION).size, TransportSchema.ddl.size)
        for (statement in TransportSchema.ddl) {
            val upper = statement.uppercase()
            assertTrue("UNIQUE" !in upper && "REFERENCES" !in upper && "FOREIGN" !in upper, statement)
            assertTrue("PRIMARY KEY" in upper, statement)
        }
    }

    @Test
    fun transportArticlesHaveNoSearchTextButTheLocalSchemaDoes() {
        val transport = TransportSchema.SYNCED_TABLES.first { it.name == "articles" }.columnNames
        val local = MergeSchema.EXPECTED_TABLES.getValue(3L).first { it.name == "articles" }.columnNames
        assertTrue("search_text" !in transport)
        assertEquals(local - "search_text", transport)
    }

    @Test
    fun mergeSchemaColumnNamesKeepTheirLegacyShape() {
        val v2Feeds = MergeSchema.EXPECTED_SCHEMAS.getValue(2L).getValue("feeds")
        assertTrue(setOf("etag", "last_modified", "error_count", "last_error").all { it in v2Feeds })
        val v3Feeds = MergeSchema.EXPECTED_SCHEMAS.getValue(3L).getValue("feeds")
        assertTrue(setOf("etag", "last_modified", "error_count", "last_error").none { it in v3Feeds })
        assertTrue("name_updated_at" !in MergeSchema.EXPECTED_SCHEMAS.getValue(2L).getValue("tags"))
        assertTrue("color_updated_at" in MergeSchema.EXPECTED_SCHEMAS.getValue(3L).getValue("tags"))
    }
}
