package works.merc.keryx.app.domain

import works.merc.keryx.app.data.local.db.Feeds

/**
 * The title to show for a feed: the user's manual override ([Feeds.custom_title]) when set to a
 * non-blank value, otherwise the title parsed from the feed itself. A blank override is treated as
 * "no override" so the parsed title is used instead.
 *
 * @return The custom title when available, otherwise the parsed feed title.
 */
fun Feeds.displayTitle(): String = custom_title?.takeIf { it.isNotBlank() } ?: title
