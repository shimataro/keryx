package works.merc.keryx.app.domain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import works.merc.keryx.app.core.Result
import works.merc.keryx.app.data.opml.OpmlCodec

/** Result of [OpmlImporter.import]: how many listed feeds subscribed successfully vs. failed. */
data class OpmlImportOutcome(val added: Int, val failed: Int)

/**
 * Parses an OPML document, subscribes to every feed it lists, and synchronizes each feed's folder
 * and tags to match the document. Used both by the settings dialog's manual import and by opening
 * an `.opml` file via the OS file association.
 */
class OpmlImporter(
    private val feedRepository: FeedRepository,
    private val folderRepository: FolderRepository,
    private val tagRepository: TagRepository,
) {
    // OpmlImporter is a Koin singleton shared by the settings dialog's import button and every
    // .opml file-association open (main.kt's dispatchOpmlFile) — and macOS's setOpenFileHandler
    // dispatches one call per file selected in Finder's "Open With", so concurrent import() calls
    // are a real possibility. Without this lock, two concurrent runs that reference the same new
    // folder/tag name race FolderRepository.createFolder / TagRepository.createTag's check-then-act
    // getByName-then-upsert, which can throw a SQLite UNIQUE constraint violation on
    // folders.name / tags.name (upsert's ON CONFLICT is on id only, not name).
    private val mutex = Mutex()

    /**
     * @param xml The OPML document contents.
     * @return The number of feeds successfully subscribed vs. failed.
     */
    suspend fun import(xml: String): OpmlImportOutcome = mutex.withLock {
        // Each distinct folder / tag name is resolved once per import run, not once per feed:
        // FolderRepository.createFolder re-appends an already-active folder to the end of the folder
        // sort order and bumps its updated_at on every call, so calling it per feed would reshuffle
        // folder order and emit needless sync writes.
        val folderIdByName = mutableMapOf<String, String>()
        val tagIdByName = mutableMapOf<String, String>()
        // Snapshot of the pre-import attachments, so each feed's tag diff below is computed against
        // the state before this run started changing things.
        val previousFeedTagMap = tagRepository.getFeedTagMap()
        var added = 0
        var failed = 0
        // Indexes new articles for search once after the whole loop, rather than once per feed (see
        // FeedRepository.subscribeFeedWrite) — indexMissing()'s NOT IN scan is O(articles table
        // size), so a large OPML import must not repeat it once per feed.
        var anyHadArticles = false
        for (entry in OpmlCodec.import(xml)) {
            val outcome = feedRepository.subscribeFeedWrite(entry.xmlUrl)
            when (val subscribed = outcome.result) {
                is Result.Ok -> {
                    added++
                    if (outcome.hadArticles) anyHadArticles = true
                    val feed = subscribed.value
                    val folderId = entry.folderName?.let { name ->
                        folderIdByName.getOrPut(name) { folderRepository.createFolder(name) }
                    }
                    // Guarded so a re-import that changes nothing writes nothing.
                    if (feed.folder_id != folderId) feedRepository.moveFeed(feed.id, folderId)

                    val newTagIds = entry.tags
                        .map { name -> tagIdByName.getOrPut(name) { tagRepository.createTag(name) } }
                        .toSet()
                    val currentTagIds = previousFeedTagMap[feed.id].orEmpty()
                    (currentTagIds - newTagIds).forEach { tagRepository.setFeedTag(feed.id, it, false) }
                    (newTagIds - currentTagIds).forEach { tagRepository.setFeedTag(feed.id, it, true) }
                }
                is Result.Err -> failed++
            }
        }
        if (anyHadArticles) feedRepository.indexImportedArticles()
        OpmlImportOutcome(added, failed)
    }
}
