package works.merc.keryx.app.domain

/**
 * Removes [draggedId] from [currentOrder] (a display-order list of IDs) and inserts it directly
 * before [targetId] (or at the end if [targetId] is null), returning the new order. [draggedId]
 * doesn't need to already be present in [currentOrder] (e.g. when moving in from another group) —
 * it's simply inserted at the resolved position.
 */
fun reorderIds(currentOrder: List<String>, draggedId: String, targetId: String?): List<String> {
    if (draggedId == targetId) return currentOrder
    val without = currentOrder.filterNot { it == draggedId }
    val insertAt = targetId?.let { without.indexOf(it) }?.takeIf { it >= 0 } ?: without.size
    return without.toMutableList().apply { add(insertAt, draggedId) }
}
