package works.merc.keryx.app.presentation.home

import works.merc.keryx.app.data.local.db.Folders
import works.merc.keryx.app.data.local.db.Tags
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class NameValidationTest {

    private fun folder(id: String, name: String) = Folders(id, name, 0L, null, 0L, 0L)
    private fun tag(id: String, name: String) = Tags(id, name, null, 0L, null, 0L, 0L)

    @Test
    fun folderNameIsADuplicateWhenAnotherFolderAlreadyHasIt() {
        val folders = listOf(folder("d1", "Tech"), folder("d2", "News"))

        assertTrue(isDuplicateFolderName("Tech", folders))
        assertFalse(isDuplicateFolderName("Sports", folders))
    }

    @Test
    fun folderNameCheckExcludesTheGivenId() {
        // A rename dialog checks the folder's own new name against every *other* folder — it must
        // not flag itself for keeping (or reverting to) its own current name.
        val folders = listOf(folder("d1", "Tech"), folder("d2", "News"))

        assertFalse(isDuplicateFolderName("Tech", folders, excludeId = "d1"))
        assertTrue(isDuplicateFolderName("Tech", folders, excludeId = "d2"))
    }

    @Test
    fun tagNameIsADuplicateWhenAnotherTagAlreadyHasIt() {
        val tags = listOf(tag("t1", "kotlin"), tag("t2", "daily"))

        assertTrue(isDuplicateTagName("kotlin", tags))
        assertFalse(isDuplicateTagName("swift", tags))
    }

    @Test
    fun tagNameCheckExcludesTheGivenId() {
        val tags = listOf(tag("t1", "kotlin"), tag("t2", "daily"))

        assertFalse(isDuplicateTagName("kotlin", tags, excludeId = "t1"))
        assertTrue(isDuplicateTagName("kotlin", tags, excludeId = "t2"))
    }
}
