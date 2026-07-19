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
}
