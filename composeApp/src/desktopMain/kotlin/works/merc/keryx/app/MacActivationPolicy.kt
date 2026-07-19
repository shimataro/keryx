package works.merc.keryx.app

import com.sun.jna.Function
import com.sun.jna.Pointer

/**
 * Toggles the macOS Dock icon / Cmd+Tab entry at runtime via the public
 * NSApplication.setActivationPolicy: Cocoa API. apple.awt.UIElement is read
 * only once at AWT startup (confirmed in OpenJDK's NSApplicationAWT.m), so
 * there is no JDK/AWT property that can flip this after launch.
 */
internal object MacActivationPolicy {
    private const val POLICY_REGULAR = 0L
    private const val POLICY_ACCESSORY = 1L

    private val objcGetClass = Function.getFunction("objc", "objc_getClass")
    private val selRegisterName = Function.getFunction("objc", "sel_registerName")
    // Fixed-signature Function per call shape - required on arm64, where a
    // generic vararg objc_msgSend mapping uses the wrong calling convention.
    private val msgSendId = Function.getFunction("objc", "objc_msgSend")
    private val msgSendVoidLong = Function.getFunction("objc", "objc_msgSend")

    private val nsApplicationClass: Pointer by lazy { objcGetClass.invokePointer(arrayOf("NSApplication")) }
    private val sharedApplicationSel: Pointer by lazy { selRegisterName.invokePointer(arrayOf("sharedApplication")) }
    private val setActivationPolicySel: Pointer by lazy { selRegisterName.invokePointer(arrayOf("setActivationPolicy:")) }
    private val activateIgnoringOtherAppsSel: Pointer by lazy { selRegisterName.invokePointer(arrayOf("activateIgnoringOtherApps:")) }

    fun setDockIconVisible(visible: Boolean) {
        val app = msgSendId.invokePointer(arrayOf(nsApplicationClass, sharedApplicationSel))
        val policy = if (visible) POLICY_REGULAR else POLICY_ACCESSORY
        msgSendVoidLong.invoke(Void.TYPE, arrayOf(app, setActivationPolicySel, policy))
        if (visible) {
            msgSendVoidLong.invoke(Void.TYPE, arrayOf(app, activateIgnoringOtherAppsSel, 1L))
        }
    }
}
