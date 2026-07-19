package works.merc.keryx.app.domain

/**
 * The ATTACH-DATABASE merge statements. Cloud DB is attached as `cloud`; these
 * merge it into `main` per the conflict-resolution policy:
 * - read/star state: timestamp-last-wins (read_at / starred_at)
 * - article body: OR-merge (keep whichever side has it)
 * - feeds / tags / feed_tags / global_settings: timestamp-last-wins
 *
 * Columns are listed explicitly (avoiding `SELECT *`, which breaks on any
 * schema drift). NOT-EXISTS guards skip rows that would violate a UNIQUE
 * or FK constraint, so a merge can never abort the whole sync.
 */
object MergeSql {

    /** When a cloud folder has the same name but a different ID and is newer,
     *  update the local folder in place so feeds can resolve to the local UUID. */
    private val updateFoldersByName = """
        UPDATE main.folders
        SET sort_order = c.sort_order,
            deleted_at = c.deleted_at,
            updated_at = c.updated_at
        FROM cloud.folders c
        WHERE main.folders.name = c.name
          AND main.folders.id <> c.id
          AND c.updated_at > main.folders.updated_at;
    """.trimIndent()

    private val insertFolders = """
        INSERT INTO main.folders (id, name, sort_order, deleted_at, updated_at, created_at)
        SELECT c.id, c.name, c.sort_order, c.deleted_at, c.updated_at, c.created_at
        FROM cloud.folders c
        WHERE NOT EXISTS (SELECT 1 FROM main.folders l WHERE l.id = c.id AND l.updated_at >= c.updated_at)
          AND NOT EXISTS (SELECT 1 FROM main.folders l2 WHERE l2.name = c.name AND l2.id <> c.id)
        ON CONFLICT(id) DO UPDATE SET
            name = excluded.name, sort_order = excluded.sort_order,
            deleted_at = excluded.deleted_at, updated_at = excluded.updated_at
        WHERE excluded.updated_at > updated_at;
    """.trimIndent()

    // The user-editable fields folder_id / sort_order / custom_title / deleted_at are intentionally
    // NOT merged in the ON CONFLICT here — each rides its own last-wins timestamp and is handled by
    // a dedicated mergeFeed* statement, so a content refresh that makes this row "newer" can never
    // clobber a folder move / reorder / rename / unsubscribe from another device. Their values are
    // still carried in the INSERT so a brand-new feed gets its initial values.
    private val feeds = """
        INSERT INTO main.feeds (
            id, url, site_url, title, description, favicon_url, etag, last_modified,
            error_count, last_error, custom_title, deleted_at, updated_at, created_at, sort_order,
            sort_order_updated_at, custom_title_updated_at, deleted_updated_at
        )
        SELECT c.id, c.url, c.site_url, c.title, c.description, c.favicon_url, c.etag,
               c.last_modified, c.error_count, c.last_error, c.custom_title,
               c.deleted_at, c.updated_at, c.created_at, c.sort_order,
               c.sort_order_updated_at, c.custom_title_updated_at, c.deleted_updated_at
        FROM cloud.feeds c
        WHERE NOT EXISTS (SELECT 1 FROM main.feeds l WHERE l.id = c.id AND l.updated_at >= c.updated_at)
          AND NOT EXISTS (SELECT 1 FROM main.feeds l2 WHERE l2.url = c.url AND l2.id <> c.id)
        ON CONFLICT(id) DO UPDATE SET
            url = excluded.url, site_url = excluded.site_url, title = excluded.title,
            description = excluded.description, favicon_url = excluded.favicon_url,
            etag = excluded.etag, last_modified = excluded.last_modified,
            error_count = excluded.error_count, last_error = excluded.last_error,
            updated_at = excluded.updated_at
        WHERE excluded.updated_at > updated_at;
    """.trimIndent()

    /**
     * Merges a feed's folder assignment independently of its content row, using the
     * field-specific `folder_updated_at` timestamp (last-wins). Runs after `feeds` (so both the
     * folder rows and the feed rows exist in `main`), and applies whether or not the `feeds`
     * statement rewrote the row — this is what lets a folder move propagate even when the local
     * feed is same-or-newer for content reasons.
     *
     * NULL-aware: only adopts the cloud assignment when the cloud has an assignment event
     * (`folder_updated_at IS NOT NULL`) that is strictly newer than the local one (NULL = "no
     * event", distinct from an assignment made at epoch 0). The resolved folder_id keeps the id
     * when the folder is present in `main`, else resolves by folder name, else NULL — the same
     * logic the `feeds` insert used to carry.
     */
    private val mergeFeedFolderId = """
        UPDATE main.feeds
        SET folder_id = (
                CASE
                    WHEN NOT EXISTS (SELECT 1 FROM cloud.folders cf WHERE cf.id = c.folder_id AND cf.deleted_at IS NULL)
                        THEN NULL
                    WHEN EXISTS (SELECT 1 FROM main.folders mf WHERE mf.id = c.folder_id AND mf.deleted_at IS NULL)
                        THEN c.folder_id
                    WHEN c.folder_id IS NOT NULL THEN (
                        SELECT mf.id FROM main.folders mf
                        INNER JOIN cloud.folders cf ON cf.name = mf.name
                        WHERE cf.id = c.folder_id
                          AND cf.deleted_at IS NULL
                          AND mf.deleted_at IS NULL
                        LIMIT 1
                    )
                    ELSE NULL
                END),
            folder_updated_at = c.folder_updated_at
        FROM cloud.feeds c
        WHERE main.feeds.id = c.id
          AND c.folder_updated_at IS NOT NULL
          AND (main.feeds.folder_updated_at IS NULL OR c.folder_updated_at > main.feeds.folder_updated_at);
    """.trimIndent()

    /**
     * Merges a feed's reorder position (`sort_order`) by its own `sort_order_updated_at` timestamp,
     * independently of the content row — so a reorder propagates even when the local feed is
     * same-or-newer for content reasons. NULL-aware last-wins (NULL = no reorder event yet).
     */
    private val mergeFeedSortOrder = """
        UPDATE main.feeds
        SET sort_order = c.sort_order,
            sort_order_updated_at = c.sort_order_updated_at
        FROM cloud.feeds c
        WHERE main.feeds.id = c.id
          AND c.sort_order_updated_at IS NOT NULL
          AND (main.feeds.sort_order_updated_at IS NULL OR c.sort_order_updated_at > main.feeds.sort_order_updated_at);
    """.trimIndent()

    /** Merges a feed's manual rename (`custom_title`) by its own `custom_title_updated_at`. */
    private val mergeFeedCustomTitle = """
        UPDATE main.feeds
        SET custom_title = c.custom_title,
            custom_title_updated_at = c.custom_title_updated_at
        FROM cloud.feeds c
        WHERE main.feeds.id = c.id
          AND c.custom_title_updated_at IS NOT NULL
          AND (main.feeds.custom_title_updated_at IS NULL OR c.custom_title_updated_at > main.feeds.custom_title_updated_at);
    """.trimIndent()

    /**
     * Merges a feed's subscription state (`deleted_at`) by its own `deleted_updated_at` — so an
     * unsubscribe propagates even when the other device kept refreshing the feed, and a
     * re-subscribe (newer timestamp) overrides it.
     */
    private val mergeFeedDeletedAt = """
        UPDATE main.feeds
        SET deleted_at = c.deleted_at,
            deleted_updated_at = c.deleted_updated_at
        FROM cloud.feeds c
        WHERE main.feeds.id = c.id
          AND c.deleted_updated_at IS NOT NULL
          AND (main.feeds.deleted_updated_at IS NULL OR c.deleted_updated_at > main.feeds.deleted_updated_at);
    """.trimIndent()

    private val articles = """
        INSERT INTO main.articles (
            id, feed_id, guid, url, title, summary, content, author, published_at,
            thumbnail_url, is_read, read_at, is_starred, starred_at, cached_at,
            search_text, updated_at, created_at
        )
        SELECT
            c.id, c.feed_id, c.guid, c.url, c.title,
            COALESCE(c.summary, l.summary),
            COALESCE(c.content, l.content),
            c.author, c.published_at, c.thumbnail_url,
            CASE WHEN COALESCE(c.read_at, 0) >= COALESCE(l.read_at, 0) THEN c.is_read ELSE l.is_read END,
            CASE WHEN COALESCE(c.read_at, 0) >= COALESCE(l.read_at, 0) THEN c.read_at ELSE l.read_at END,
            CASE WHEN COALESCE(c.starred_at, 0) >= COALESCE(l.starred_at, 0) THEN c.is_starred ELSE l.is_starred END,
            CASE WHEN COALESCE(c.starred_at, 0) >= COALESCE(l.starred_at, 0) THEN c.starred_at ELSE l.starred_at END,
            CASE
                WHEN c.cached_at IS NOT NULL AND l.cached_at IS NOT NULL THEN MAX(c.cached_at, l.cached_at)
                ELSE COALESCE(c.cached_at, l.cached_at)
            END,
            COALESCE(c.content, l.content, c.summary, l.summary, ''),
            c.updated_at, c.created_at
        FROM cloud.articles c
        LEFT JOIN main.articles l ON l.id = c.id
        WHERE NOT EXISTS (
            SELECT 1 FROM main.articles l2
            WHERE l2.feed_id = c.feed_id AND l2.guid = c.guid AND l2.id <> c.id
        )
          AND EXISTS (SELECT 1 FROM main.feeds mf WHERE mf.id = c.feed_id)
        ON CONFLICT(id) DO UPDATE SET
            summary = COALESCE(excluded.summary, summary),
            content = COALESCE(excluded.content, content),
            cached_at = CASE
                WHEN excluded.cached_at IS NOT NULL AND cached_at IS NOT NULL THEN MAX(excluded.cached_at, cached_at)
                ELSE COALESCE(excluded.cached_at, cached_at)
            END,
            search_text = COALESCE(excluded.content, content, excluded.summary, summary, ''),
            is_read = excluded.is_read, read_at = excluded.read_at,
            is_starred = excluded.is_starred, starred_at = excluded.starred_at,
            updated_at = excluded.updated_at
        WHERE COALESCE(excluded.read_at, 0) >= COALESCE(read_at, 0)
           OR COALESCE(excluded.starred_at, 0) >= COALESCE(starred_at, 0)
           OR excluded.summary IS NOT NULL
           OR excluded.content IS NOT NULL;
    """.trimIndent()

    /** When a cloud tag has the same name but a different ID and is newer,
     *  update the local tag in place so feed_tags can resolve to the local UUID. */
    private val updateTagsByName = """
        UPDATE main.tags
        SET color = c.color,
            sort_order = c.sort_order,
            deleted_at = c.deleted_at,
            updated_at = c.updated_at
        FROM cloud.tags c
        WHERE main.tags.name = c.name
          AND main.tags.id <> c.id
          AND c.updated_at > main.tags.updated_at;
    """.trimIndent()

    private val insertTags = """
        INSERT INTO main.tags (id, name, color, sort_order, deleted_at, updated_at, created_at)
        SELECT c.id, c.name, c.color, c.sort_order, c.deleted_at, c.updated_at, c.created_at
        FROM cloud.tags c
        WHERE NOT EXISTS (SELECT 1 FROM main.tags l WHERE l.id = c.id AND l.updated_at >= c.updated_at)
          AND NOT EXISTS (SELECT 1 FROM main.tags l2 WHERE l2.name = c.name AND l2.id <> c.id)
        ON CONFLICT(id) DO UPDATE SET
            name = excluded.name, color = excluded.color, sort_order = excluded.sort_order,
            deleted_at = excluded.deleted_at, updated_at = excluded.updated_at
        WHERE excluded.updated_at > updated_at;
    """.trimIndent()

    private val feedTags = """
        INSERT INTO main.feed_tags (feed_id, tag_id, deleted_at, updated_at)
        SELECT resolved.feed_id, resolved.resolved_tag_id, resolved.deleted_at, resolved.updated_at
        FROM (
            SELECT c.feed_id,
                   CASE
                       WHEN EXISTS (SELECT 1 FROM main.tags mt WHERE mt.id = c.tag_id AND mt.deleted_at IS NULL)
                           THEN c.tag_id
                       WHEN EXISTS (SELECT 1 FROM cloud.tags ct WHERE ct.id = c.tag_id) THEN (
                           SELECT mt.id FROM main.tags mt
                           INNER JOIN cloud.tags ct ON ct.name = mt.name
                           WHERE ct.id = c.tag_id
                           LIMIT 1
                       )
                       ELSE NULL
                   END AS resolved_tag_id,
                   c.deleted_at, c.updated_at
            FROM cloud.feed_tags c
            WHERE EXISTS (SELECT 1 FROM main.feeds mf WHERE mf.id = c.feed_id)
        ) resolved
        WHERE resolved.resolved_tag_id IS NOT NULL
          AND NOT EXISTS (
              SELECT 1 FROM main.feed_tags l
              WHERE l.feed_id = resolved.feed_id AND l.tag_id = resolved.resolved_tag_id AND l.updated_at >= resolved.updated_at
          )
        ON CONFLICT(feed_id, tag_id) DO UPDATE SET
            deleted_at = excluded.deleted_at, updated_at = excluded.updated_at
        WHERE excluded.updated_at > updated_at;
    """.trimIndent()

    private val globalSettings = """
        INSERT INTO main.global_settings (key, value, updated_at)
        SELECT c.key, c.value, c.updated_at
        FROM cloud.global_settings c
        WHERE NOT EXISTS (SELECT 1 FROM main.global_settings l WHERE l.key = c.key AND l.updated_at >= c.updated_at)
        ON CONFLICT(key) DO UPDATE SET
            value = excluded.value, updated_at = excluded.updated_at
        WHERE excluded.updated_at > updated_at;
    """.trimIndent()

    /** Applied in FK-safe order (parents before children). */
    val all: List<String> = listOf(
        updateFoldersByName, insertFolders, feeds,
        mergeFeedFolderId, mergeFeedSortOrder, mergeFeedCustomTitle, mergeFeedDeletedAt,
        updateTagsByName, insertTags, articles, feedTags, globalSettings,
    )
}
