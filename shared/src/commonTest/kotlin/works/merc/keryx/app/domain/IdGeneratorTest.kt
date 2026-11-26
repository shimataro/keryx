package works.merc.keryx.app.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class IdGeneratorTest {
    private val uuidRegex = Regex(
        "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$",
        RegexOption.IGNORE_CASE,
    )

    @Test
    fun newIdProducesDifferentValuesOnEachCall() {
        val a = IdGenerator.newId()
        val b = IdGenerator.newId()

        assertNotEquals(a, b)
    }

    @Test
    fun newIdIsNotBlank() {
        assertFalse(IdGenerator.newId().isBlank())
    }

    @Test
    fun newIdIsAValidUuid() {
        val id = IdGenerator.newId()
        assertTrue(uuidRegex.matches(id), "Expected UUID format, got: $id")
    }

    @Test
    fun articleIdIsDeterministicForTheSameKey() {
        // The whole point: two devices computing the id for the same (feed_id, guid) must agree,
        // so the sync merge (matched by id) can propagate read/star state.
        assertEquals(
            IdGenerator.articleId("feed-1", "guid-1"),
            IdGenerator.articleId("feed-1", "guid-1"),
        )
    }

    @Test
    fun articleIdDiffersForDifferentKeys() {
        val base = IdGenerator.articleId("feed-1", "guid-1")
        assertNotEquals(base, IdGenerator.articleId("feed-1", "guid-2")) // different guid
        assertNotEquals(base, IdGenerator.articleId("feed-2", "guid-1")) // different feed
    }

    @Test
    fun articleIdIsAValidUuidVersion5() {
        val id = IdGenerator.articleId("feed-1", "guid-1")
        assertTrue(uuidRegex.matches(id), "Expected UUID format, got: $id")
        // The version nibble (14th char) must be '5' — this is what guarantees a deterministic
        // article id can never collide with a legacy random (v4) id.
        assertEquals('5', id[14], "Expected a version-5 UUID, got: $id")
    }

    @Test
    fun feedIdIsDeterministicForTheSameUrl() {
        // Two devices subscribing the same feed url must derive the same id, so the sync merge
        // (matched by id) can converge independently-subscribed feeds.
        assertEquals(
            IdGenerator.feedId("https://example.com/feed"),
            IdGenerator.feedId("https://example.com/feed"),
        )
    }

    @Test
    fun feedIdDiffersForDifferentUrls() {
        assertNotEquals(
            IdGenerator.feedId("https://example.com/feed"),
            IdGenerator.feedId("https://example.com/other"),
        )
    }

    @Test
    fun feedIdIsAValidUuidVersion5() {
        val id = IdGenerator.feedId("https://example.com/feed")
        assertTrue(uuidRegex.matches(id), "Expected UUID format, got: $id")
        assertEquals('5', id[14], "Expected a version-5 UUID, got: $id")
    }

    @Test
    fun tagIdIsDeterministicForTheSameName() {
        // Two devices creating the same tag name must derive the same id, so the sync merge (matched
        // by id) converges them into one row.
        assertEquals(IdGenerator.tagId("Work"), IdGenerator.tagId("Work"))
    }

    @Test
    fun tagIdDiffersForDifferentNamesAndIsCaseSensitive() {
        val base = IdGenerator.tagId("Work")
        assertNotEquals(base, IdGenerator.tagId("Jobs"))
        assertNotEquals(base, IdGenerator.tagId("work"))
        assertNotEquals(base, IdGenerator.tagId("Work "))
    }

    /** Fixed vectors: the namespaces must never change, so their outputs are pinned. */
    @Test
    fun tagIdMatchesItsPinnedVectors() {
        assertEquals("b3718ad7-22d4-5806-80f0-d789f5ff8443", IdGenerator.tagId("Work"))
        assertEquals("e120c1b7-be34-5289-a5a0-ec0e461c7333", IdGenerator.tagId("仕事"))
    }

    @Test
    fun tagIdIsAValidUuidVersion5() {
        val id = IdGenerator.tagId("Work")
        assertTrue(uuidRegex.matches(id), "Expected UUID format, got: $id")
        assertEquals('5', id[14], "Expected a version-5 UUID, got: $id")
    }

    @Test
    fun folderIdIsDeterministicForTheSameName() {
        assertEquals(IdGenerator.folderId("Work"), IdGenerator.folderId("Work"))
    }

    @Test
    fun folderIdDiffersForDifferentNamesAndFromTheTagIdOfTheSameName() {
        val base = IdGenerator.folderId("Work")
        assertNotEquals(base, IdGenerator.folderId("Home"))
        // The tag and folder id-spaces are independent: the same name never maps to the same id.
        assertNotEquals(base, IdGenerator.tagId("Work"))
    }

    /** Fixed vectors: the namespaces must never change, so their outputs are pinned. */
    @Test
    fun folderIdMatchesItsPinnedVectors() {
        assertEquals("4892adac-f48d-5867-9d1f-4fb54659bb44", IdGenerator.folderId("Work"))
        assertEquals("7891bdc6-4027-500d-897b-a932e93411b5", IdGenerator.folderId("仕事"))
    }

    @Test
    fun folderIdIsAValidUuidVersion5() {
        val id = IdGenerator.folderId("Work")
        assertTrue(uuidRegex.matches(id), "Expected UUID format, got: $id")
        assertEquals('5', id[14], "Expected a version-5 UUID, got: $id")
    }
}
