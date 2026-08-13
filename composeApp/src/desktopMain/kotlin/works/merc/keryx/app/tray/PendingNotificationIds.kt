package works.merc.keryx.app.tray

import org.freedesktop.dbus.types.UInt32
import java.util.Collections

/**
 * Tracks the ids of notifications this app has itself sent via `org.freedesktop.Notifications`,
 * so a received `ActionInvoked` signal - which is unscoped by sender and therefore also fires for
 * every other application's notifications - can be filtered down to only our own.
 *
 * Deliberately holds no D-Bus connection, so it can be constructed and exercised in tests without
 * a bus (same rationale as [SniStatusNotifierItem]). [add] runs on the caller's coroutine after a
 * successful `Notify` call; [consume] runs on a dbus-java signal-handler thread - both mutate a
 * synchronized set, so no external synchronization is required.
 */
internal class PendingNotificationIds {
    private val ids: MutableSet<UInt32> = Collections.synchronizedSet(mutableSetOf())

    /** Records [id] as belonging to a notification this app just sent. */
    fun add(id: UInt32) {
        ids.add(id)
    }

    /**
     * Returns `true` and forgets [id] if it was previously recorded via [add]; `false` otherwise
     * (an id we never sent - some other application's notification).
     */
    fun consume(id: UInt32): Boolean = ids.remove(id)
}
