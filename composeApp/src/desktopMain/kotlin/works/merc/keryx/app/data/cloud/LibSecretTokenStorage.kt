package works.merc.keryx.app.data.cloud

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.Structure
import com.sun.jna.ptr.PointerByReference
import kotlinx.serialization.json.Json
import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.core.Log

/** `secret-1` soname; no `.so` dev symlink is guaranteed to exist, so this must be the exact soname. */
private const val LIBSECRET_SONAME = "libsecret-1.so.0"

/** Only used to free a `GError*` after logging it; libsecret's own soname above is what matters for feature detection. */
private const val LIBGLIB_SONAME = "libglib-2.0.so.0"

/** `SecretSchemaFlags` / `SecretSchemaAttributeType` — both `0` (`SECRET_SCHEMA_NONE`, `SECRET_SCHEMA_ATTRIBUTE_STRING`). */
private const val SECRET_SCHEMA_NONE = 0
private const val SECRET_SCHEMA_ATTRIBUTE_STRING = 0

/**
 * Deliberately not routed through Compose Resources (constraint #3's usual rule for user-facing
 * strings): inside the snap libsecret always resolves to the file-backed Secret-portal backend
 * (see this file's own KDoc), which never surfaces a label in any credential-manager UI a user
 * could see — same non-localized-identifier treatment as [KEYCHAIN_SERVICE].
 */
private const val SECRET_LABEL = "Keryx cloud token"

/** The one schema attribute every call below keys on; a single constant so the schema definition and every call site can never drift apart. */
private const val SECRET_ATTRIBUTE_ACCOUNT = "account"

/**
 * Seam over the slice of libsecret's synchronous "simple API" this class uses (store / lookup /
 * clear a single secret by one string attribute). Real libsecret is reached only through JNA
 * [Native.load], which a test cannot swap out, so this interface is the only injectable point —
 * same role (and reason) as [KeyringAccess] in `KeyringTokenStorage`.
 */
internal interface LibSecretAccess {
    fun lookup(account: String): String?
    fun store(account: String, payload: String): Boolean
    fun clear(account: String): Boolean
}

/** The subset of libsecret's C API this class calls, bound via JNA. */
private interface LibSecretNative : Library {
    /** `SecretSchema *secret_schema_new (name, flags, attr1_name, attr1_type, ..., NULL)`, called here with one attribute. */
    fun secret_schema_new(name: String, flags: Int, attr1Name: String, attr1Type: Int, terminator: Pointer?): Pointer

    /** `gboolean secret_password_store_sync (schema, collection, label, password, cancellable, error, ..., NULL)`. */
    fun secret_password_store_sync(
        schema: Pointer,
        collection: Pointer?,
        label: String,
        password: String,
        cancellable: Pointer?,
        error: PointerByReference,
        attr1Name: String,
        attr1Value: String,
        terminator: Pointer?,
    ): Int

    /** `gchar *secret_password_lookup_sync (schema, cancellable, error, ..., NULL)` — caller frees via [secret_password_free]. */
    fun secret_password_lookup_sync(
        schema: Pointer,
        cancellable: Pointer?,
        error: PointerByReference,
        attr1Name: String,
        attr1Value: String,
        terminator: Pointer?,
    ): Pointer?

    /** `gboolean secret_password_clear_sync (schema, cancellable, error, ..., NULL)`. */
    fun secret_password_clear_sync(
        schema: Pointer,
        cancellable: Pointer?,
        error: PointerByReference,
        attr1Name: String,
        attr1Value: String,
        terminator: Pointer?,
    ): Int

    /** Frees a `gchar*` returned by [secret_password_lookup_sync]; must not be freed with plain `g_free`. */
    fun secret_password_free(password: Pointer?)
}

/** The one glib call needed to release a `GError*` after logging it (its `message` field is read directly via [GErrorStruct], no glib call needed for that). */
private interface GLibNative : Library {
    fun g_error_free(error: Pointer)
}

/** Mirrors glib's `struct GError { GQuark domain; gint code; gchar *message; }`, read-only. */
private class GErrorStruct(pointer: Pointer) : Structure(pointer) {
    @JvmField var domain: Int = 0
    @JvmField var code: Int = 0
    @JvmField var message: Pointer? = null

    override fun getFieldOrder(): List<String> = listOf("domain", "code", "message")
}

/** [LibSecretAccess] backed by the real libsecret library. */
private class RealLibSecretAccess(
    private val native: LibSecretNative,
    private val glib: GLibNative?,
    private val schema: Pointer,
) : LibSecretAccess {

    override fun lookup(account: String): String? {
        val error = PointerByReference()
        val result = native.secret_password_lookup_sync(schema, null, error, SECRET_ATTRIBUTE_ACCOUNT, account, null)
        reportError(error, "lookup")
        return result?.let { ptr ->
            try {
                ptr.getString(0)
            } finally {
                native.secret_password_free(ptr)
            }
        }
    }

    override fun store(account: String, payload: String): Boolean {
        val error = PointerByReference()
        val stored = native.secret_password_store_sync(
            schema, null, SECRET_LABEL, payload, null, error, SECRET_ATTRIBUTE_ACCOUNT, account, null,
        ) != 0
        reportError(error, "store")
        return stored
    }

    override fun clear(account: String): Boolean {
        val error = PointerByReference()
        val cleared = native.secret_password_clear_sync(schema, null, error, SECRET_ATTRIBUTE_ACCOUNT, account, null) != 0
        reportError(error, "clear")
        return cleared
    }

    /** Logs and frees a `GError*` left by the call just made, if any. */
    private fun reportError(error: PointerByReference, operation: String) {
        val pointer = error.value ?: return
        val message = runCatching { GErrorStruct(pointer).apply { read() }.message?.getString(0) }.getOrNull()
        Log.warn(TOKEN_STORAGE_LOG_TAG, "libsecret $operation failed: ${message ?: "unknown error"}")
        glib?.let { runCatching { it.g_error_free(pointer) } }
    }
}

/**
 * Loads libsecret once for the whole process (not once per [LibSecretTokenStorage] instance —
 * three are constructed, one per cloud provider) and builds its single, process-lifetime schema.
 * The schema is never released (`secret_schema_unref`): there is exactly one per process, kept
 * for as long as the process runs, so leaking it is equivalent to a static allocation, not a
 * per-call leak. Returns null (and logs) when libsecret could not be loaded at all — the day-to-
 * day "portal unreachable for this one call" failure is a per-operation `GError`, handled in
 * [RealLibSecretAccess.reportError] instead, and does not affect this value.
 */
private val sharedLibSecretAccess: LibSecretAccess? by lazy {
    runCatching {
        val native = Native.load(LIBSECRET_SONAME, LibSecretNative::class.java)
        val glib = runCatching { Native.load(LIBGLIB_SONAME, GLibNative::class.java) }.getOrNull()
        val schema = native.secret_schema_new(
            KEYCHAIN_SERVICE, SECRET_SCHEMA_NONE, SECRET_ATTRIBUTE_ACCOUNT, SECRET_SCHEMA_ATTRIBUTE_STRING, null,
        )
        RealLibSecretAccess(native, glib, schema)
    }.onFailure { Log.warn(TOKEN_STORAGE_LOG_TAG, "libsecret not available; falling back to file storage", it) }
        .getOrNull()
}

/**
 * Stores a cloud provider's tokens via libsecret's Secret portal integration — the path Snap
 * (and Flatpak) officially recommend over raw Secret Service access (see
 * `docs/build.md`'s "Linux Snap package"). Unlike [KeyringTokenStorage], which talks to
 * `org.freedesktop.secrets` directly over D-Bus, libsecret itself detects the sandbox (via the
 * `SNAP_NAME` environment variable — the same one [works.merc.keryx.app.platform.isSnap] checks)
 * and transparently switches to an encrypted local file keyed by a per-app master secret it
 * obtains from `org.freedesktop.portal.Secret`, which needs no `password-manager-service`
 * auto-connect. Used **only** inside the snap (see `PlatformModule.desktop.kt`'s
 * `providerTokenStorage`) — outside it, [KeyringTokenStorage] keeps talking to the same Secret
 * Service items existing deb/rpm users already have.
 *
 * The [account] (per-provider, derived from [CloudStorageType.id]) distinguishes providers under
 * the shared [KEYCHAIN_SERVICE] schema name. Falls back to [FileTokenStorage] if libsecret is
 * unavailable or an operation fails. Outcome composition lives in [SecretStoreTokenStorage].
 *
 * Every libsecret call here is synchronous (blocks on the D-Bus round-trip to the portal, with
 * no cancellation — `cancellable` is always passed as null) — exactly like [KeyringTokenStorage]'s
 * calls into java-keyring. Neither is actually confined to a background thread today:
 * `CloudSession.connectedType()`/`isConnected()` call straight into `TokenStorage.load()`, and are
 * themselves reached synchronously from `SettingsViewModel`'s and `HomeViewModel`'s property
 * initializers — i.e. from whatever thread resolves those ViewModels via Koin, which is the UI
 * thread during Compose composition. This is a pre-existing property of the whole `TokenStorage`
 * design, not something introduced here; it simply used to fail fast inside the snap (AppArmor
 * denying the raw Secret Service call outright) and now blocks on a real, slower D-Bus round trip
 * instead. Fixing it belongs to `CloudSession`'s own initialization path, not to this class.
 */
class LibSecretTokenStorage internal constructor(
    fallback: TokenStorage,
    private val account: String,
    json: Json,
    /**
     * The libsecret binding, or null when it could not be loaded. Injectable so tests can
     * exercise the "no backend → plaintext fallback" path — and the working-backend paths —
     * without touching (or depending on) a real libsecret/portal.
     */
    private val libSecret: LibSecretAccess?,
) : SecretStoreTokenStorage(fallback, json) {

    constructor(
        fallback: TokenStorage,
        account: String = CloudStorageType.DROPBOX.id,
        json: Json = Json { ignoreUnknownKeys = true },
    ) : this(fallback, account, json, sharedLibSecretAccess)

    override fun storeSecret(payload: String): Boolean = libSecret?.let {
        runCatching { it.store(account, payload) }
            .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "libsecret save failed; falling back to file storage", e) }
            .getOrDefault(false)
    } ?: false

    override fun loadSecret(): String? = libSecret?.let {
        runCatching { it.lookup(account) }
            .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "libsecret load failed; falling back to file storage", e) }
            .getOrNull()
    }

    // Same conservative composition as KeyringTokenStorage.clearSecret: only a confirmed clear is
    // reported as true, so a secret possibly still readable in the portal's encrypted store is
    // never mistakenly reported as gone.
    override fun clearSecret(): Boolean = libSecret?.let {
        runCatching { it.clear(account) }
            .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "libsecret clear failed", e) }
            .getOrDefault(false)
    } ?: false
}
