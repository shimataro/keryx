package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags

/**
 * A display name resolved together with the ordinal that tells it apart from same-named rows —
 * [tagOrdinals]/[folderOrdinals]' decision as the UIs render it. Data rather than a formatted
 * string, because the "name (n)" pattern is each UI's own localized resource (`name_with_ordinal`);
 * every UI renders the pair through its one ordinal formatter (Compose's `nameWithOrdinal`,
 * SwiftUI's `nameWithOrdinal`), per the one-implementation-per-action constraint. [ordinal] is
 * `null` for a uniquely named row — the name alone.
 */
data class OrdinalName(val name: String, val ordinal: Int?)

/**
 * Every live tag's and folder's display name together with its ordinal, from one snapshot of the
 * tag and folder lists — what both UIs render the rows' labels from. A name and its ordinal are
 * published as one value so a rename that makes two rows share a name can never be shown with the
 * new name and the old ordinal (or the reverse), as two separately collected flows could.
 */
data class TagFolderLabels(
    val tags: Map<String, OrdinalName>,
    val folders: Map<String, OrdinalName>,
) {
    /** The tag's label, or its own [fallbackName] bare when this snapshot has no entry for it yet. */
    fun tag(id: String, fallbackName: String): OrdinalName = tags[id] ?: OrdinalName(fallbackName, null)

    /** The folder's label, or its own [fallbackName] bare when this snapshot has no entry for it yet. */
    fun folder(id: String, fallbackName: String): OrdinalName = folders[id] ?: OrdinalName(fallbackName, null)

    companion object {
        val EMPTY = TagFolderLabels(emptyMap(), emptyMap())
    }
}

/** The labels of every row in [tags] and [folders] — see [TagFolderLabels]. */
fun tagFolderLabels(tags: List<Tags>, folders: List<Folders>): TagFolderLabels {
    val tagOrdinals = tagOrdinals(tags)
    val folderOrdinals = folderOrdinals(folders)
    return TagFolderLabels(
        tags = tags.associate { it.id to OrdinalName(it.name, tagOrdinals[it.id]) },
        folders = folders.associate { it.id to OrdinalName(it.name, folderOrdinals[it.id]) },
    )
}

/**
 * The ordinals that tell same-named live tags apart on screen, keyed by tag id: only the second
 * and later of each name (2, 3, ... — see [nameOrdinals]); a tag absent from the map displays its
 * bare name.
 */
fun tagOrdinals(rows: List<Tags>): Map<String, Int> =
    nameOrdinals(rows.filter { it.deleted_at == null }, Tags::name, Tags::id, Tags::created_at)

/**
 * The ordinals that tell same-named live folders apart on screen, keyed by folder id — the folder
 * counterpart of [tagOrdinals].
 */
fun folderOrdinals(rows: List<Folders>): Map<String, Int> =
    nameOrdinals(rows.filter { it.deleted_at == null }, Folders::name, Folders::id, Folders::created_at)

/**
 * Numbers the rows sharing a name 2, 3, ... in a deterministic order — [createdAt], then [id] —
 * so every device labels the same row with the same ordinal. (After a sync merge `created_at` is
 * the minimum across devices, so the order converges; within one device the order is deterministic
 * regardless.) Only groups of two or more rows produce entries. A user-named "X (2)" is visually
 * indistinguishable from the ordinal on the second "X" — accepted; escaping user names would make
 * the common case unreadable.
 */
private fun <T> nameOrdinals(
    rows: List<T>,
    name: (T) -> String,
    id: (T) -> String,
    createdAt: (T) -> Long,
): Map<String, Int> {
    if (rows.size < 2) return emptyMap()
    val byName = LinkedHashMap<String, MutableList<T>>()
    for (row in rows) byName.getOrPut(name(row)) { mutableListOf() }.add(row)
    val ordinals = HashMap<String, Int>()
    for (group in byName.values) {
        if (group.size < 2) continue
        val ordered = group.sortedWith(compareBy(createdAt, id))
        for (index in 1 until ordered.size) ordinals[id(ordered[index])] = index + 1
    }
    return ordinals
}
