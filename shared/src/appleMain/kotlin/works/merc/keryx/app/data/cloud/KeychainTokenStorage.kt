package works.merc.keryx.app.data.cloud

import kotlinx.cinterop.CPointerVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.convert
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.value
import kotlinx.serialization.json.Json
import platform.CoreFoundation.CFDataCreate
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDataRef
import platform.CoreFoundation.CFDictionaryAddValue
import platform.CoreFoundation.CFDictionaryCreateMutable
import platform.CoreFoundation.CFMutableDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.CFTypeRef
import platform.CoreFoundation.CFTypeRefVar
import platform.CoreFoundation.kCFBooleanTrue
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.Security.SecItemAdd
import platform.Security.SecItemCopyMatching
import platform.Security.SecItemDelete
import platform.Security.SecItemUpdate
import platform.Security.errSecItemNotFound
import platform.Security.errSecSuccess
import platform.Security.kSecAttrAccessible
import platform.Security.kSecAttrAccessibleAfterFirstUnlock
import platform.Security.kSecAttrAccount
import platform.Security.kSecAttrService
import platform.Security.kSecClass
import platform.Security.kSecClassGenericPassword
import platform.Security.kSecMatchLimit
import platform.Security.kSecMatchLimitOne
import platform.Security.kSecReturnData
import platform.Security.kSecUseDataProtectionKeychain
import platform.Security.kSecValueData
import works.merc.keryx.app.core.Log

/**
 * [TokenStorage] in the system Keychain, as one generic-password item per provider: service
 * [service] (`works.merc.keryx`), account [account] (`CloudStorageType.id`). Readable after the
 * first unlock since boot, so a background refresh can still sync. There is no plaintext fallback
 * on Apple — the Keychain is always present — so a failed write is [TokenSaveOutcome.NOT_PERSISTED].
 *
 * @param useDataProtectionKeychain When true, every query also sets `kSecUseDataProtectionKeychain`
 *   — a store the Compose desktop build's `security`-CLI-based storage cannot reach at all, unlike
 *   plain login-Keychain items which share one flat namespace by service+account alone. The
 *   shipping app always passes true (see `KeryxSdk.start`'s own parameter); it needs the
 *   `keychain-access-groups` entitlement and a real (not ad-hoc) code signature, so tests and
 *   previews pass false and use the ordinary login Keychain instead — see
 *   "Distribution and coexistence" in `docs/app-architecture.md`.
 */
@OptIn(ExperimentalForeignApi::class)
class KeychainTokenStorage(
    private val account: String,
    private val service: String = KEYCHAIN_SERVICE,
    private val useDataProtectionKeychain: Boolean = false,
    private val json: Json = Json { ignoreUnknownKeys = true },
) : TokenStorage {

    /**
     * Upserts this provider's item: updates the existing one in place, and adds it only when there
     * is none. Never delete-then-add — that would leave a window where a concurrent [load] sees no
     * token, and a failed add would already have destroyed the previously valid one.
     */
    override fun save(tokens: OAuthTokens): TokenSaveOutcome {
        val payload = json.encodeToString(tokens).encodeToByteArray()
        val data = payload.usePinned { CFDataCreate(null, it.addressOf(0).reinterpret(), payload.size.convert()) }
        val status = try {
            val updateStatus = withItemQuery { query ->
                val attributes = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
                try {
                    CFDictionaryAddValue(attributes, kSecValueData, data)
                    CFDictionaryAddValue(attributes, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
                    SecItemUpdate(query, attributes)
                } finally {
                    CFRelease(attributes)
                }
            }
            if (updateStatus != errSecItemNotFound) {
                updateStatus
            } else {
                withItemQuery { query ->
                    CFDictionaryAddValue(query, kSecValueData, data)
                    CFDictionaryAddValue(query, kSecAttrAccessible, kSecAttrAccessibleAfterFirstUnlock)
                    SecItemAdd(query, null)
                }
            }
        } finally {
            CFRelease(data)
        }
        if (status != errSecSuccess) Log.warn(TOKEN_STORAGE_LOG_TAG, "Keychain write failed (OSStatus $status)")
        return if (status == errSecSuccess) TokenSaveOutcome.SECURE else TokenSaveOutcome.NOT_PERSISTED
    }

    override fun load(): OAuthTokens? {
        val bytes = withItemQuery { query ->
            CFDictionaryAddValue(query, kSecReturnData, kCFBooleanTrue)
            CFDictionaryAddValue(query, kSecMatchLimit, kSecMatchLimitOne)
            memScoped {
                val result = alloc<CFTypeRefVar>()
                if (SecItemCopyMatching(query, result.ptr) != errSecSuccess) return@memScoped null
                val data: CFDataRef = result.value?.reinterpret() ?: return@memScoped null
                try {
                    CFDataGetBytePtr(data)?.readBytes(CFDataGetLength(data).toInt())
                } finally {
                    CFRelease(data)
                }
            }
        } ?: return null
        // Only the exception's type is logged: the decoder's message would echo the token payload.
        return runCatching { json.decodeFromString<OAuthTokens>(bytes.decodeToString()) }
            .onFailure { e -> Log.warn(TOKEN_STORAGE_LOG_TAG, "Stored token payload could not be decoded (${e::class.simpleName})") }
            .getOrNull()
    }

    override fun clear(): TokenClearOutcome = if (delete()) TokenClearOutcome.CLEARED else TokenClearOutcome.DATA_MAY_REMAIN

    /** Deletes this provider's item; `true` when none remains (including when there was none). */
    private fun delete(): Boolean {
        val status = withItemQuery { SecItemDelete(it) }
        return status == errSecSuccess || status == errSecItemNotFound
    }

    /** Runs [block] with a fresh mutable query naming this provider's generic-password item. */
    private inline fun <T> withItemQuery(block: (CFMutableDictionaryRef?) -> T): T {
        val query = CFDictionaryCreateMutable(null, 0, kCFTypeDictionaryKeyCallBacks.ptr, kCFTypeDictionaryValueCallBacks.ptr)
        val serviceRef = CFStringCreateWithCString(null, service, kCFStringEncodingUTF8)
        val accountRef = CFStringCreateWithCString(null, account, kCFStringEncodingUTF8)
        try {
            CFDictionaryAddValue(query, kSecClass, kSecClassGenericPassword)
            CFDictionaryAddValue(query, kSecAttrService, serviceRef)
            CFDictionaryAddValue(query, kSecAttrAccount, accountRef)
            if (useDataProtectionKeychain) CFDictionaryAddValue(query, kSecUseDataProtectionKeychain, kCFBooleanTrue)
            return block(query)
        } finally {
            CFRelease(serviceRef)
            CFRelease(accountRef)
            CFRelease(query)
        }
    }

    companion object {
        /** The Keychain service every Keryx build stores its tokens under. */
        const val KEYCHAIN_SERVICE: String = "works.merc.keryx"
    }
}
