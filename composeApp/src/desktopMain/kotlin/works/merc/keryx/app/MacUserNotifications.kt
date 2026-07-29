package works.merc.keryx.app

import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import com.sun.jna.Structure
import works.merc.keryx.app.core.Log
import java.util.UUID

private const val LOG_TAG = "MacUserNotifications"
private const val USER_NOTIFICATIONS_FRAMEWORK_PATH =
    "/System/Library/Frameworks/UserNotifications.framework/UserNotifications"

// UNAuthorizationOptions bitmask values (UNNotificationSettings.h).
private const val UN_AUTHORIZATION_OPTION_SOUND = 1L shl 1
private const val UN_AUTHORIZATION_OPTION_ALERT = 1L shl 2

// Clang Blocks ABI flag bits (clang.llvm.org/docs/Block-ABI-Apple.html).
private const val BLOCK_IS_GLOBAL = 1 shl 28

/**
 * Posts macOS notifications via the modern `UNUserNotificationCenter` API, driven directly through
 * raw `objc_msgSend` calls (JNA) — bypassing AWT's `TrayIcon.displayMessage`, which wraps the
 * long-deprecated `NSUserNotificationCenter` peer and was confirmed non-functional on this JDK/macOS
 * combination (tray icon shows, but no banner and no Notification Center entry) even from a plain
 * unsigned process. This attributes notifications to "Keryx" correctly, unlike shelling out to
 * `osascript` (which would attribute them to whatever process runs the script instead).
 *
 * There is no ARC here — every Objective-C call goes through raw `objc_msgSend`, so ownership
 * follows classic Cocoa manual-retain-release rules: only `alloc`/`init`'d objects are ours to
 * `release`; convenience-constructed and singleton objects are not.
 */
internal object MacUserNotifications {
    init {
        // UserNotifications.framework isn't linked by AWT (unlike Foundation/AppKit, already
        // resident), so objc_getClass("UN...") below would return NULL unless it's dlopen'd first.
        // JNA caches NativeLibrary instances per path, so this is a cheap no-op on repeat access.
        NativeLibrary.getInstance(USER_NOTIFICATIONS_FRAMEWORK_PATH)
    }

    private val objcGetClass = Function.getFunction("objc", "objc_getClass")
    private val selRegisterName = Function.getFunction("objc", "sel_registerName")

    // One Function instance per distinct call shape (return type + arg types), even where the
    // underlying symbol is the same objc_msgSend — matching MacActivationPolicy.kt's convention,
    // required on arm64 where a single generic vararg mapping picks the wrong calling convention.
    private val msgSendId_id = Function.getFunction("objc", "objc_msgSend")
    private val msgSendVoid_id = Function.getFunction("objc", "objc_msgSend")
    private val msgSendVoid_id_long_ptr = Function.getFunction("objc", "objc_msgSend")
    private val msgSendVoid_id_ptr_ptr = Function.getFunction("objc", "objc_msgSend")
    private val msgSendVoid_id_ptr = Function.getFunction("objc", "objc_msgSend")
    private val msgSendId_id_ptr = Function.getFunction("objc", "objc_msgSend")
    private val msgSendId_id_ptr_ptr_ptr = Function.getFunction("objc", "objc_msgSend")

    private val unUserNotificationCenterClass: Pointer by lazy { objcGetClass.invokePointer(arrayOf("UNUserNotificationCenter")) }
    private val unMutableNotificationContentClass: Pointer by lazy { objcGetClass.invokePointer(arrayOf("UNMutableNotificationContent")) }
    private val unNotificationRequestClass: Pointer by lazy { objcGetClass.invokePointer(arrayOf("UNNotificationRequest")) }
    private val nsStringClass: Pointer by lazy { objcGetClass.invokePointer(arrayOf("NSString")) }

    private val currentNotificationCenterSel: Pointer by lazy { selRegisterName.invokePointer(arrayOf("currentNotificationCenter")) }
    private val requestAuthorizationSel: Pointer by lazy {
        selRegisterName.invokePointer(arrayOf("requestAuthorizationWithOptions:completionHandler:"))
    }
    private val addNotificationRequestSel: Pointer by lazy {
        selRegisterName.invokePointer(arrayOf("addNotificationRequest:withCompletionHandler:"))
    }
    private val allocSel: Pointer by lazy { selRegisterName.invokePointer(arrayOf("alloc")) }
    private val initSel: Pointer by lazy { selRegisterName.invokePointer(arrayOf("init")) }
    private val setTitleSel: Pointer by lazy { selRegisterName.invokePointer(arrayOf("setTitle:")) }
    private val setBodySel: Pointer by lazy { selRegisterName.invokePointer(arrayOf("setBody:")) }
    private val stringWithUtf8StringSel: Pointer by lazy { selRegisterName.invokePointer(arrayOf("stringWithUTF8String:")) }
    private val requestWithIdentifierSel: Pointer by lazy {
        selRegisterName.invokePointer(arrayOf("requestWithIdentifier:content:trigger:"))
    }
    private val releaseSel: Pointer by lazy { selRegisterName.invokePointer(arrayOf("release")) }

    private val notificationCenter: Pointer by lazy {
        msgSendId_id.invokePointer(arrayOf(unUserNotificationCenterClass, currentNotificationCenterSel))
    }

    // Not private: JNA's Structure/Callback machinery reflects into these types' @JvmField members,
    // and a private *enclosing* class blocks that reflective access regardless of the field's own
    /**
         * Handles completion of a notification authorization request.
         *
         * @param blockLiteral The native block instance associated with the callback.
         * @param granted A nonzero value if authorization was granted.
         * @param error The native error object, or `null` when no error is available.
         */
    fun interface AuthorizationCompletionBlock : Callback {
        fun invoke(blockLiteral: Pointer?, granted: Byte, error: Pointer?)
    }

    /** Minimal `Block_descriptor_1` (no copy/dispose helpers, no signature — see MacUserNotifications.kt design notes). */
    class BlockDescriptor : Structure() {
        @JvmField var reserved: Long = 0L
        @JvmField var size: Long = 0L
        override fun getFieldOrder(): List<String> = listOf("reserved", "size")
    }

    /** `Block_literal_1` for the one block this file needs (the authorization completion handler). */
    class AuthorizationBlockLiteral : Structure() {
        @JvmField var isa: Pointer? = null
        @JvmField var flags: Int = 0
        @JvmField var reserved: Int = 0
        @JvmField var invoke: AuthorizationCompletionBlock? = null
        @JvmField var descriptor: Pointer? = null
        override fun getFieldOrder(): List<String> = listOf("isa", "flags", "reserved", "invoke", "descriptor")
    }

    // Kept alive for the process lifetime: this callback (and the block literal/descriptor memory
    // built from it) must never be GC'd, since the OS invokes the block at an unknown future time.
    private val liveCallbacks = mutableListOf<Callback>()

    /**
     * Requests authorization for alert and sound notifications.
     *
     * The authorization result is logged and not otherwise exposed.
     */
    fun requestAuthorization() {
        val callback = AuthorizationCompletionBlock { _, granted, _ ->
            Log.info(LOG_TAG, "Notification authorization granted=${granted.toInt() != 0}")
        }
        liveCallbacks += callback

        val descriptor = BlockDescriptor()
        val literal = AuthorizationBlockLiteral()
        descriptor.size = literal.size().toLong()
        descriptor.write()

        literal.isa = NativeLibrary.getProcess().getGlobalVariableAddress("_NSConcreteGlobalBlock")
        literal.flags = BLOCK_IS_GLOBAL
        literal.reserved = 0
        literal.invoke = callback
        literal.descriptor = descriptor.pointer
        literal.write()

        val options = UN_AUTHORIZATION_OPTION_ALERT or UN_AUTHORIZATION_OPTION_SOUND
        msgSendVoid_id_long_ptr.invoke(
            Void.TYPE,
            arrayOf(notificationCenter, requestAuthorizationSel, options, literal.pointer),
        )
    }

    /**
     * Posts a macOS user notification with the specified title and body.
     *
     * @param title The notification title.
     * @param body The notification body.
     */
    fun post(title: String, body: String) {
        val content = msgSendId_id.invokePointer(arrayOf(unMutableNotificationContentClass, allocSel))
            .let { msgSendId_id.invokePointer(arrayOf(it, initSel)) }

        msgSendVoid_id_ptr.invoke(Void.TYPE, arrayOf(content, setTitleSel, nsString(title)))
        msgSendVoid_id_ptr.invoke(Void.TYPE, arrayOf(content, setBodySel, nsString(body)))

        val identifier = nsString(UUID.randomUUID().toString())
        val request = msgSendId_id_ptr_ptr_ptr.invokePointer(
            arrayOf(unNotificationRequestClass, requestWithIdentifierSel, identifier, content, Pointer.NULL),
        )

        msgSendVoid_id_ptr_ptr.invoke(
            Void.TYPE,
            arrayOf(notificationCenter, addNotificationRequestSel, request, Pointer.NULL),
        )

        // We alloc'd `content` ourselves (unlike title/body/identifier/request, which are
        // convenience-constructed/autoreleased, and `notificationCenter`, a singleton) — so we,
        // not an autorelease pool, are responsible for releasing it.
        msgSendVoid_id.invoke(Void.TYPE, arrayOf(content, releaseSel))
    }

    /**
     * Creates an `NSString` from a UTF-8 encoded Kotlin string.
     *
     * @param value The string to convert.
     * @return A pointer to the resulting native `NSString`.
     */
    private fun nsString(value: String): Pointer {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val memory = Memory((bytes.size + 1).toLong())
        memory.write(0, bytes, 0, bytes.size)
        memory.setByte(bytes.size.toLong(), 0)
        return msgSendId_id_ptr.invokePointer(arrayOf(nsStringClass, stringWithUtf8StringSel, memory))
    }
}
