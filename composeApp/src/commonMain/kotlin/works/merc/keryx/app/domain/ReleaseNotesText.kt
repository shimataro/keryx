package works.merc.keryx.app.domain

/** A line-start `#` through `######` heading marker, with any following whitespace. */
private val HEADING_PREFIX = Regex("(?m)^#{1,6}[ \\t]*")

/** Bold markers. Never confusable with a bullet (`-`/`* `, a single character followed by
 * whitespace) since these are always two characters together. */
private val BOLD_MARKERS = Regex("\\*\\*|__")

/** A single `*` not immediately adjacent to another one — i.e. no longer part of a `**` pair once
 * [BOLD_MARKERS] has already run. */
private val ITALIC_STAR = Regex("\\*")

/** A line-start bullet (`- ` or `* `), captured so it survives [ITALIC_STAR] stripping. */
private val BULLET_PREFIX = Regex("^(\\s*[*-][ \\t]+)")

/**
 * Reduces a GitHub release's Markdown body to plain text for the Updates settings tab's read-only
 * summary — strips heading (`#`) and emphasis (`**`/`__`/`*`) markers, while list-item bullets and
 * the blank lines between paragraphs are left untouched (both already read fine as plain text).
 *
 * Not a general Markdown-to-text converter — release notes are the only place this is used, and a
 * real Markdown renderer isn't worth the new shipped dependency (and the
 * `THIRD-PARTY-LICENSES.md` entry that would come with it) for one read-only summary.
 */
fun plainTextReleaseNotes(markdown: String): String {
    val withoutHeadings = HEADING_PREFIX.replace(markdown, "")
    val withoutBold = BOLD_MARKERS.replace(withoutHeadings, "")
    return withoutBold.lineSequence().joinToString("\n") { line ->
        val bullet = BULLET_PREFIX.find(line)?.value
        if (bullet != null) {
            bullet + ITALIC_STAR.replace(line.substring(bullet.length), "")
        } else {
            ITALIC_STAR.replace(line, "")
        }
    }
}
