package works.merc.keryx.app

import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext

/**
 * Runs each dispatched block at once on the dispatching thread, except while [hold]ing — then
 * queues it until [release]. Lets a test keep a coroutine (e.g. an import that has already been
 * cancelled) from making progress, to observe who waits for it.
 *
 * [hold], [release] and [dispatch] are serialized by one lock, so a [dispatch] that saw the
 * dispatcher holding always enqueues before a concurrent [release] drains the queue — a block can
 * never be stranded in the queue after it was released. Blocks themselves run outside the lock.
 */
class HoldingDispatcher : CoroutineDispatcher() {
    private val lock = Any()
    private val queue = ArrayDeque<Runnable>()
    private var holding = false

    /** How many blocks are waiting for [release]. */
    val queuedCount: Int get() = synchronized(lock) { queue.size }

    fun hold() {
        synchronized(lock) { holding = true }
    }

    /** Stops holding and runs every queued block, in order, on the calling thread. */
    fun release() {
        val drained = synchronized(lock) {
            holding = false
            queue.toList().also { queue.clear() }
        }
        drained.forEach { it.run() }
    }

    override fun dispatch(context: CoroutineContext, block: Runnable) {
        val queued = synchronized(lock) { holding.also { if (it) queue.addLast(block) } }
        if (!queued) block.run()
    }
}
