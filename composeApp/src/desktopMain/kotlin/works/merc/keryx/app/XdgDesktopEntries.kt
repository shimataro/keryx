package works.merc.keryx.app

import works.merc.keryx.app.core.APP_NAME
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

private const val DEFAULT_APPLICATIONS = "Default Applications"
private const val ADDED_ASSOCIATIONS = "Added Associations"

/**
 * Reads the contents of a regular file when it exists.
 *
 * @param file The file to read.
 * @return The file contents, or `null` when the path is not a regular file.
 */
internal fun readOrNull(file: File): String? = file.takeIf { it.isFile }?.readText()

/**
 * Atomically replaces the target file with the specified content.
 *
 * @param target The file to replace.
 * @param content The content to write.
 */
internal fun writeAtomically(target: File, content: String) {
    val parent = target.parentFile
    parent?.mkdirs()
    val tmp = File(parent, "${target.name}.tmp")
    tmp.writeText(content)
    Files.move(
        tmp.toPath(),
        target.toPath(),
        StandardCopyOption.ATOMIC_MOVE,
        StandardCopyOption.REPLACE_EXISTING,
    )
}

/**
 * Writes [content] to [file] only when it differs from the file's current contents.
 *
 * @param file The file to update.
 * @param content The desired file contents.
 * @return `true` if [file] was written, `false` if it already held [content].
 */
internal fun writeIfChanged(file: File, content: String): Boolean {
    if (readOrNull(file) == content) return false
    writeAtomically(file, content)
    return true
}

/**
 * Generates a desktop entry that hands a launched file or URI to [launcherPath] via [execFieldCode]
 * (`%u` for a URI, `%f` for a bare local path), declaring itself the handler for [mimeType].
 *
 * @param launcherPath The executable path used to launch the application.
 * @param mimeType The MIME type this entry declares itself the handler for.
 * @param execFieldCode The desktop-entry `Exec=` field code appended after the launcher path.
 * @return The formatted desktop entry content.
 */
internal fun desktopEntryContent(launcherPath: String, mimeType: String, execFieldCode: String): String =
    """
    [Desktop Entry]
    Type=Application
    Name=$APP_NAME
    Exec=${escapeDesktopExecPath(launcherPath)} $execFieldCode
    Terminal=false
    NoDisplay=true
    StartupNotify=false
    MimeType=$mimeType;
    """.trimIndent() + "\n"

/**
 * Escapes and quotes a launcher path for use as an `Exec=` value in a desktop entry.
 *
 * @param path The launcher path to escape.
 * @return The quoted, desktop-entry-escaped launcher path.
 */
internal fun escapeDesktopExecPath(path: String): String {
    val quoted = buildString {
        append('"')
        for (char in path) {
            when (char) {
                '"', '`', '$', '\\' -> append('\\').append(char)
                '%' -> append("%%")
                else -> append(char)
            }
        }
        append('"')
    }
    return quoted.replace("\\", "\\\\")
}

/**
 * Merges the custom URI scheme association into a `mimeapps.list` document.
 *
 * Updates the default application association and adds the desktop file to the added
 * associations, while preserving unrelated content and the original trailing-newline state.
 *
 * @param existing The current `mimeapps.list` content, or `null` if the file does not exist.
 * @param desktopFileName The desktop file to associate with the MIME type.
 * @param mimeType The MIME type representing the custom URI scheme.
 * @return The merged `mimeapps.list` content.
 */
internal fun mergeMimeAppsList(existing: String?, desktopFileName: String, mimeType: String): String {
    val endsWithNewline = existing.isNullOrEmpty() || existing.endsWith("\n")
    val lines = if (existing.isNullOrEmpty()) {
        mutableListOf()
    } else {
        existing.removeSuffix("\n").split("\n").toMutableList()
    }

    upsertEntry(lines, DEFAULT_APPLICATIONS, mimeType) { desktopFileName }
    upsertEntry(lines, ADDED_ASSOCIATIONS, mimeType) { current ->
        val values = current.orEmpty().split(";").filter { it.isNotBlank() }
        if (desktopFileName in values) null else (values + desktopFileName).joinToString(";", postfix = ";")
    }

    val merged = lines.joinToString("\n")
    return if (endsWithNewline) "$merged\n" else merged
}

/**
 * Sets [key] within [section] to whatever [newValue] returns, creating the section or the key if
 * needed. [newValue] receives the current value (null when the key is absent) and may return null
 * to leave [lines] untouched.
 */
private fun upsertEntry(
    lines: MutableList<String>,
    section: String,
    key: String,
    newValue: (current: String?) -> String?,
) {
    val header = "[$section]"
    val sectionStart = lines.indexOfFirst { it.trim() == header }
    if (sectionStart < 0) {
        val value = newValue(null) ?: return
        if (lines.isNotEmpty() && lines.last().isNotBlank()) lines.add("")
        lines.add(header)
        lines.add("$key=$value")
        return
    }

    val sectionEnd = (sectionStart + 1 until lines.size)
        .firstOrNull { lines[it].trimStart().startsWith("[") }
        ?: lines.size
    val keyIndex = (sectionStart + 1 until sectionEnd)
        .firstOrNull { lines[it].contains('=') && lines[it].substringBefore('=').trim() == key }

    if (keyIndex != null) {
        val value = newValue(lines[keyIndex].substringAfter('=')) ?: return
        lines[keyIndex] = "$key=$value"
    } else {
        val value = newValue(null) ?: return
        // Insert after the section's last non-blank line so any blank separator stays in place.
        var insertAt = sectionEnd
        while (insertAt > sectionStart + 1 && lines[insertAt - 1].isBlank()) insertAt--
        lines.add(insertAt, "$key=$value")
    }
}
