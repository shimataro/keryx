package works.merc.keryx.app.domain

import works.merc.keryx.app.platform.Sha1
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/** Generates ids for entities. */
@OptIn(ExperimentalUuidApi::class)
object IdGenerator {
    /**
     * Fixed namespace for article [articleId] generation. Must NEVER change: article ids derive
     * from it, so altering it would make previously-generated ids diverge across app versions.
     */
    private val ARTICLE_NAMESPACE: ByteArray =
        Uuid.parse("6b3f9e2a-7c4d-4a1b-8e5f-1d2c3b4a5e6f").toByteArray()

    /**
     * Fixed namespace for feed [feedId] generation. Distinct from [ARTICLE_NAMESPACE] so feed and
     * article id-spaces are independent. Must NEVER change (feed ids derive from it).
     */
    private val FEED_NAMESPACE: ByteArray =
        Uuid.parse("a1c9e5d3-7b42-4f18-9e60-2d4b6a8c0f31").toByteArray()

    /** A random UUIDv4 for a new entity id (tags / folders / notifications). */
    fun newId(): String = Uuid.random().toString()

    /**
     * Deterministic UUIDv5 for an article, derived from its natural key (feed_id, guid) so the same
     * article gets the SAME id on every device — required for the sync merge (which matches articles
     * by id) to propagate read/star state cross-device. Same UUID format as [newId]; the version-5
     * nibble guarantees it can never collide with a legacy random v4 id. feed_id is a UUID (no '\n'),
     * so `feedId + '\n' + guid` is injective in (feedId, guid).
     */
    fun articleId(feedId: String, guid: String): String =
        uuidV5(ARTICLE_NAMESPACE, "$feedId\n$guid")

    /**
     * Deterministic UUIDv5 for a feed, derived from its (redirect-resolved) url so the same feed gets
     * the SAME id on every device — required for the sync merge (which matches feeds by id) to
     * converge feeds (and, since article ids derive from feed_id, their articles) that were subscribed
     * independently on each device before the first sync. Same UUID format as [newId]; the version-5
     * nibble guarantees it can never collide with a legacy random v4 id.
     */
    fun feedId(url: String): String = uuidV5(FEED_NAMESPACE, url)

    /** Name-based (SHA-1) UUIDv5 of [name] under [namespace], per RFC 4122 §4.3. */
    private fun uuidV5(namespace: ByteArray, name: String): String {
        val bytes = Sha1.digest(namespace + name.encodeToByteArray()).copyOf(16)
        bytes[6] = ((bytes[6].toInt() and 0x0F) or 0x50).toByte() // version 5
        bytes[8] = ((bytes[8].toInt() and 0x3F) or 0x80).toByte() // RFC 4122 variant (10xx)
        return Uuid.fromByteArray(bytes).toString()
    }
}
