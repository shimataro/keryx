package works.merc.keryx.app.domain

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import works.merc.keryx.app.core.CloudDataIncompatibleException
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.core.SYNC_POINTER_FORMAT
import works.merc.keryx.app.core.SchemaVersionException

/**
 * The pointer file `keryx-sync.json`, `{"format":3,"generation":"<genId>"}` — the one file that names
 * the current generation (see "Cloud File Structure" and step 1 of "Sync Flow" in
 * `docs/sync-architecture.md`).
 */
object SyncPointer {

    /** The pointer's content for [generation] (a lowercase UUID). */
    fun encode(generation: String): String {
        require(TransportFileName.isSyncId(generation)) { "Not a generation id" }
        return buildJsonObject {
            put("format", SYNC_POINTER_FORMAT)
            put("generation", generation)
        }.toString()
    }

    /**
     * The generation [text] names. A `format` newer than [SYNC_POINTER_FORMAT] is a
     * [SchemaVersionException] ("update the app") whatever else the file holds; anything that cannot
     * be parsed, lacks a field, or carries an older format or a malformed generation is a
     * [CloudDataIncompatibleException] (a reset rewrites it). Unknown extra fields are ignored.
     */
    fun parse(text: String): Result<String> {
        val root = runCatching { Json.parseToJsonElement(text) }.getOrNull() as? JsonObject
            ?: return incompatible("the pointer is not a JSON object")
        val format = (root["format"] as? JsonPrimitive)?.takeUnless { it.isString }?.longOrNull
            ?: return incompatible("the pointer has no numeric format")
        if (format > SYNC_POINTER_FORMAT) {
            return Result.Err(SchemaVersionException(localVersion = SYNC_POINTER_FORMAT, cloudVersion = format))
        }
        if (format != SYNC_POINTER_FORMAT) return incompatible("the pointer's format $format is not supported")
        val generation = (root["generation"] as? JsonPrimitive)?.takeIf { it.isString }?.contentOrNull
        if (generation == null || !TransportFileName.isSyncId(generation)) {
            return incompatible("the pointer's generation is malformed")
        }
        return Result.Ok(generation)
    }

    private fun incompatible(reason: String): Result.Err =
        Result.Err(CloudDataIncompatibleException("Cloud sync pointer is unusable: $reason"))
}
