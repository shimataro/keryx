package works.merc.keryx.app.core

import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Guards Android's backup/device-transfer exclusions against drifting behind [CloudStorageType].
 *
 * `data_extraction_rules.xml` (API 31+) and `backup_rules.xml` (below API 31) must exclude every
 * provider's `.{CloudStorageType.id}_tokens.enc` and `.{CloudStorageType.id}_tokens.json` — both
 * files say so in their own comments, but nothing enforced it, and adding Google Drive to Android
 * did in fact leave its pair behind. A missed entry lets an OAuth token ride along in a cloud
 * backup or a device-to-device transfer, which is exactly what those rules exist to prevent.
 *
 * Reads the XML as a plain file, the same way `ui/i18n/StringsXmlParityTest` reads the locale
 * resources: the paths are relative to the `composeApp` module directory, and the files being
 * checked live in the sibling `androidApp` module (they belong to the application manifest, which
 * is where `android:dataExtractionRules`/`android:fullBackupContent` are declared).
 */
class TokenBackupExclusionTest {

    private val resDir = File("../androidApp/src/main/res/xml")

    /** Every token file that must never be backed up, for every provider the app supports. */
    private val requiredPaths: Set<String> =
        CloudStorageType.entries.flatMap { listOf(".${it.id}_tokens.enc", ".${it.id}_tokens.json") }.toSet()

    /**
     * The `path` attribute of every `<exclude domain="file">` under the [section] element of
     * [fileName] — e.g. `cloud-backup` in `data_extraction_rules.xml`, or the document element
     * itself (`full-backup-content`) when [section] names it.
     */
    private fun excludedFilePaths(fileName: String, section: String): Set<String> {
        val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(resDir, fileName))
        val root = doc.documentElement
        val scope = if (root.tagName == section) {
            root
        } else {
            root.getElementsByTagName(section).item(0) as? Element
                ?: error("<$section> not found in $fileName")
        }
        val excludes = scope.getElementsByTagName("exclude")
        return (0 until excludes.length)
            .map { excludes.item(it) as Element }
            .filter { it.getAttribute("domain") == "file" }
            .map { it.getAttribute("path") }
            .toSet()
    }

    @Test
    fun cloudBackupExcludesEveryProvidersTokenFiles() {
        assertEquals(
            requiredPaths,
            excludedFilePaths("data_extraction_rules.xml", "cloud-backup"),
            "<cloud-backup> in data_extraction_rules.xml must exclude exactly every provider's token files",
        )
    }

    @Test
    fun deviceTransferExcludesEveryProvidersTokenFiles() {
        assertEquals(
            requiredPaths,
            excludedFilePaths("data_extraction_rules.xml", "device-transfer"),
            "<device-transfer> in data_extraction_rules.xml must exclude exactly every provider's token files",
        )
    }

    @Test
    fun legacyFullBackupExcludesEveryProvidersTokenFiles() {
        assertEquals(
            requiredPaths,
            excludedFilePaths("backup_rules.xml", "full-backup-content"),
            "backup_rules.xml (below API 31) must exclude exactly every provider's token files",
        )
    }
}
