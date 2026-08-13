package works.merc.keryx.app.domain

/**
 * Resolves the id for a create-or-reactivate-by-name write, shared by [TagRepository.createTag]
 * and [FolderRepository.createFolder]: the id of an existing row with the same name (reactivating
 * it in place, including a previously soft-deleted row) when [existingId] is non-null, or a
 * freshly generated [IdGenerator.newId] otherwise. [upsert] performs the table-specific write for
 * the resolved id — each table's column list, and how it treats `sort_order` on reactivation,
 * differ and stay entirely up to the caller.
 */
internal fun createOrReactivateId(existingId: String?, upsert: (id: String) -> Unit): String {
    val id = existingId ?: IdGenerator.newId()
    upsert(id)
    return id
}
