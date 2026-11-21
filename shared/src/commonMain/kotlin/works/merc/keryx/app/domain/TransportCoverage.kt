package works.merc.keryx.app.domain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a base covers — its `ks_vector`, its explicit `ks_covered` names and its creation time — as
 * read from a validated base file. See "Transport schema" and "Bases and Garbage Collection" in
 * `docs/sync-architecture.md`.
 *
 * A base covers a delta `(device, seq)` either **explicitly** (its name is in [covered]) or **by its
 * vector** (`seq <= vector[device]`). The vector implies a delta only while [covered] names no delta
 * of the same `(device, seq)`: when two files share a `(device, seq)` — a cloned database writing as
 * one device — the base lists every one it folded, so a duplicate it never saw is not implied.
 */
@Serializable
data class BaseCoverage(
    val name: String,
    val vector: Map<String, Long>,
    /** Explicitly covered delta names, each with its creation time (`null` when unknown). */
    val covered: Map<String, Long?>,
    val createdAt: Long,
) {
    /** The parsed [name] (bases are only ever recorded under a name that parsed). */
    val fileName: TransportFileName get() = checkNotNull(TransportFileName.parse(name)) { "Malformed base name" }

    /** The `(device, seq)` pairs named explicitly by [covered]. */
    private val explicitSeqs: Set<Pair<String, Long>> by lazy {
        covered.keys.mapNotNullTo(mutableSetOf()) { TransportFileName.parse(it)?.let { n -> n.device to n.number } }
    }

    /** Whether this base folded [delta] (see the class KDoc). */
    fun covers(delta: TransportFileName): Boolean {
        if (delta.kind != TransportFileKind.DELTA) return false
        if (delta.fileName in covered) return true
        return delta.number <= (vector[delta.device] ?: 0L) && (delta.device to delta.number) !in explicitSeqs
    }

    /**
     * Whether [delta], which this base covers, is old enough to be garbage-collected: created at or
     * before [cutoff]. A delta its vector implies always is — a vector only extends over deltas that
     * were at least `KS_DELTA_GRACE_MS` old when the base was built ([works.merc.keryx.app.platform.TransportFiles.finalizeBase])
     * — and an explicit one by its recorded creation time, or this base's own when unknown.
     */
    fun coveredBefore(delta: TransportFileName, cutoff: Long): Boolean {
        if (!covers(delta)) return false
        if (delta.fileName !in covered) return true
        return (covered[delta.fileName] ?: createdAt) <= cutoff
    }

    /** Whether this base covers `(device, seq)` by any delta — for the receiving cursor and gaps. */
    fun coversSeq(device: String, seq: Long): Boolean =
        seq <= (vector[device] ?: 0L) || (device to seq) in explicitSeqs

    /**
     * Whether this base strictly dominates [other]: every component of its vector at least as high,
     * every delta [other] names explicitly covered here too, and either more coverage or — for equal
     * coverage — a greater name. The last rule makes domination a strict order, so two devices can
     * never delete each other's bases.
     */
    fun dominates(other: BaseCoverage): Boolean {
        if (other.name == name) return false
        if (other.vector.any { (device, seq) -> (vector[device] ?: 0L) < seq }) return false
        val otherNames = other.covered.keys.mapNotNull(TransportFileName::parse)
        // Clone duplicates [other] lists must be listed here too: a vector cannot vouch for which copy
        // of a (device, seq) it folded.
        val duplicated = otherNames.groupBy { it.device to it.number }.filterValues { it.size > 1 }.values.flatten().toSet()
        if (otherNames.any { if (it in duplicated) it.fileName !in covered else !covers(it) }) return false
        val strictlyMore = vector.any { (device, seq) -> seq > (other.vector[device] ?: 0L) } ||
            covered.keys.mapNotNull(TransportFileName::parse).any { !other.covers(it) }
        return strictlyMore || name > other.name
    }
}

/** The bases this device knows of in its generation, persisted in `sync_state.ks_bases`. */
object SyncBaseCache {
    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class Stored(val bases: List<BaseCoverage>)

    /** Decodes [text]; anything unreadable is an empty cache (it is only ever a shortcut). */
    fun decode(text: String?): Map<String, BaseCoverage> {
        if (text.isNullOrEmpty()) return emptyMap()
        return runCatching { json.decodeFromString(Stored.serializer(), text).bases }
            .getOrDefault(emptyList())
            .filter { TransportFileName.parse(it.name)?.kind == TransportFileKind.BASE }
            .associateBy { it.name }
    }

    fun encode(bases: Collection<BaseCoverage>): String =
        json.encodeToString(Stored.serializer(), Stored(bases.sortedBy { it.name }))
}

/**
 * The send this device recorded before uploading (`sync_state.ks_attempt`) — see step 2 of "Sending".
 *
 * @property highWaterSeq `S`, or `null` when the send covered no outbox entry (a full send from an
 * empty outbox).
 * @property full whether it is the full send that clears `sync_capture.full_pending`.
 */
data class SendAttempt(val number: Long, val name: TransportFileName, val highWaterSeq: Long?, val full: Boolean) {
    fun encode(): String = "$number|${name.fileName}|${highWaterSeq ?: ""}|${if (full) 1 else 0}"

    companion object {
        /** Parses [text], or `null` for anything malformed (treated as no attempt). */
        fun decode(text: String?): SendAttempt? {
            val parts = text?.split('|') ?: return null
            if (parts.size != 4) return null
            val number = parts[0].toLongOrNull() ?: return null
            val name = TransportFileName.parse(parts[1]) ?: return null
            if (name.number != number) return null
            val s = if (parts[2].isEmpty()) null else parts[2].toLongOrNull() ?: return null
            val full = when (parts[3]) {
                "1" -> true
                "0" -> false
                else -> return null
            }
            return SendAttempt(number, name, s, full)
        }
    }
}
