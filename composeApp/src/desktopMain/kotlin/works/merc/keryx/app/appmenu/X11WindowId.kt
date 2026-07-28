package works.merc.keryx.app.appmenu

import com.sun.jna.Native
import com.sun.jna.NativeLong
import com.sun.jna.Pointer
import com.sun.jna.platform.unix.X11
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.NativeLongByReference
import com.sun.jna.ptr.PointerByReference
import works.merc.keryx.app.core.Log

private const val LOG_TAG = "X11WindowId"

/**
 * Resolves the X11 window id (XID) of this process's own top-level window, needed to register with
 * the KDE Global Menu (`com.canonical.AppMenu.Registrar.RegisterWindow`).
 *
 * A thin JNA X11 wrapper (style of `MacActivationPolicy`): it walks the root window's
 * `_NET_CLIENT_LIST` and matches each window's `_NET_WM_PID` against our own PID, rather than
 * reflecting into `sun.awt.X11` internals or the deprecated-for-removal `Component.getPeer()`.
 * Every top-level window this process owns (the main frame, but also any Compose `DialogWindow`)
 * shares that same PID, so a PID match alone doesn't identify the main frame; windows carrying a
 * `WM_TRANSIENT_FOR` property (i.e. owned by another window, like a dialog) are excluded.
 *
 * Blocking (issues synchronous X requests) — callers must dispatch it off the UI thread. Every
 * native call degrades to `null` on failure (no X server, missing property, a non-X11 session): a
 * failure here must only mean "keep the in-window menu bar", never a crash.
 */
internal object X11WindowId {

    /** The window id of our own top-level window, or `null` if it could not be resolved. */
    fun findOwnWindowId(): Long? = runCatching { resolve() }
        .onFailure { Log.warn(LOG_TAG, "Could not resolve the X11 window id", it) }
        .getOrNull()

    private fun resolve(): Long? {
        val x11 = X11.INSTANCE ?: return null
        val display = x11.XOpenDisplay(null) ?: return null
        return try {
            val root = x11.XDefaultRootWindow(display)
            val clientListAtom = x11.XInternAtom(display, "_NET_CLIENT_LIST", false)
            val pidAtom = x11.XInternAtom(display, "_NET_WM_PID", false)
            val ownPid = ProcessHandle.current().pid()
            windowList(x11, display, root, clientListAtom)
                .filter { window -> cardinal(x11, display, window, pidAtom) == ownPid }
                .firstOrNull { window -> !isTransient(x11, display, window) }
                ?.toLong()
        } finally {
            x11.XCloseDisplay(display)
        }
    }

    /** The `_NET_CLIENT_LIST` (an array of window XIDs) of the [root] window. */
    private fun windowList(
        x11: X11,
        display: X11.Display,
        root: X11.Window,
        atom: X11.Atom,
    ): List<X11.Window> {
        val data = property(x11, display, root, atom, X11.XA_WINDOW, MAX_WINDOWS) ?: return emptyList()
        return try {
            (0 until data.count).map { X11.Window(readLong(data.pointer, it)) }
        } finally {
            x11.XFree(data.pointer)
        }
    }

    /** The first value of a `CARDINAL` property (e.g. `_NET_WM_PID`) on [window], or `null`. */
    private fun cardinal(
        x11: X11,
        display: X11.Display,
        window: X11.Window,
        atom: X11.Atom,
    ): Long? {
        val data = property(x11, display, window, atom, X11.XA_CARDINAL, 1) ?: return null
        return try {
            if (data.count > 0) readLong(data.pointer, 0) else null
        } finally {
            x11.XFree(data.pointer)
        }
    }

    /**
     * Whether [window] carries a `WM_TRANSIENT_FOR` property (i.e. is owned by another window,
     * like a dialog) — a real top-level application frame has no owner and therefore no such
     * property. Used to exclude our own dialogs from XID resolution: every top-level window this
     * process owns shares the same `_NET_WM_PID`, so a PID match alone can't tell the main frame
     * from a `DialogWindow` that happens to be mapped at the same time.
     */
    private fun isTransient(x11: X11, display: X11.Display, window: X11.Window): Boolean {
        val data = property(x11, display, window, X11.XA_WM_TRANSIENT_FOR, X11.XA_WINDOW, 1) ?: return false
        x11.XFree(data.pointer)
        return true
    }

    /** Reads a window property of [reqType], returning the data pointer + item count, or `null`. */
    private fun property(
        x11: X11,
        display: X11.Display,
        window: X11.Window,
        atom: X11.Atom,
        reqType: X11.Atom,
        length: Long,
    ): PropertyData? {
        val actualType = X11.AtomByReference()
        val actualFormat = IntByReference()
        val itemCount = NativeLongByReference()
        val bytesAfter = NativeLongByReference()
        val prop = PointerByReference()
        val status = x11.XGetWindowProperty(
            display, window, atom, NativeLong(0), NativeLong(length), false, reqType,
            actualType, actualFormat, itemCount, bytesAfter, prop,
        )
        if (status != X11.Success) return null
        val pointer = prop.value ?: return null
        val count = itemCount.value.toInt()
        if (count <= 0) {
            x11.XFree(pointer)
            return null
        }
        return PropertyData(pointer, count)
    }

    /**
     * Reads item [index] of an X11 format-32 property. libX11 always returns format-32 data as an
     * array of C `long`, so each element is [Native.LONG_SIZE] bytes (8 on 64-bit).
     */
    private fun readLong(pointer: Pointer, index: Int): Long {
        val offset = index.toLong() * Native.LONG_SIZE
        return if (Native.LONG_SIZE == 8) {
            pointer.getLong(offset)
        } else {
            pointer.getInt(offset).toLong() and 0xFFFF_FFFFL
        }
    }

    private class PropertyData(val pointer: Pointer, val count: Int)

    /** Upper bound on the number of top-level windows we read from `_NET_CLIENT_LIST`. */
    private const val MAX_WINDOWS = 4096L
}
