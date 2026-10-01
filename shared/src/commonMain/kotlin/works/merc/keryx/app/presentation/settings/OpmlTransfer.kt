package works.merc.keryx.app.presentation.settings

import works.merc.keryx.app.data.opml.OpmlCodec
import works.merc.keryx.app.domain.FeedRepository
import works.merc.keryx.app.domain.FolderRepository
import works.merc.keryx.app.domain.OpmlImportOutcome
import works.merc.keryx.app.domain.OpmlImporter
import works.merc.keryx.app.domain.TagRepository
import works.merc.keryx.app.domain.displayTitle
import works.merc.keryx.app.presentation.home.groupFeedsByFolder

/**
 * The byte-level halves of OPML import/export — building the document and parsing one back in —
 * shared with the SwiftUI app (via `KeryxSdk.opml`) so both UIs produce/accept the same OPML
 * shape. Split out of what was `SettingsViewModel`'s `buildOpmlDocument`/`importOpml`; picking the
 * file and reading/writing its bytes stays with each UI (native file dialogs are platform-specific
 * and outside this class's job).
 */
class OpmlTransfer(
    private val feedRepository: FeedRepository,
    private val folderRepository: FolderRepository,
    private val tagRepository: TagRepository,
    private val opmlImporter: OpmlImporter,
) {
    /**
     * Builds an OPML document containing the current subscriptions, organized by folder and annotated with tags.
     *
     * @return The serialized OPML document.
     */
    fun exportOpml(): String {
        val feeds = feedRepository.getAllFeeds()
        val folders = folderRepository.getAllFolders()
        val allTags = tagRepository.getAllTags() // already in display order
        val feedTagMap = tagRepository.getFeedTagMap()
        val groups = groupFeedsByFolder(feeds, folders)
            .map { (folder, groupFeeds) ->
                folder?.name to groupFeeds.map { feed ->
                    val tagIds = feedTagMap[feed.id].orEmpty()
                    OpmlCodec.ExportFeed(
                        title = feed.displayTitle(),
                        xmlUrl = feed.url,
                        htmlUrl = feed.site_url,
                        tags = allTags.filter { it.id in tagIds }.map { it.name },
                    )
                }
            }
            .filter { (_, groupFeeds) -> groupFeeds.isNotEmpty() }
        return OpmlCodec.export(groups)
    }

    /** Imports feeds, folders, and tags from an OPML/XML document. */
    suspend fun importOpml(xml: String): OpmlImportOutcome = opmlImporter.import(xml)
}
