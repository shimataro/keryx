package works.merc.keryx.app.ui.home

import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import java.awt.datatransfer.DataFlavor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertNull

/**
 * [ArticleUrlCopier] is the one handler behind every "copy article URL" route, feedback included:
 * these pin what each copy writes, when it pulses the reader's ✓, and when it shows the snackbar.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ArticleUrlCopierTest {

    private val message = "URL copied"

    private fun copier(
        scope: CoroutineScope,
        clipboard: Clipboard,
        host: SnackbarHostState?,
        // Defaults model Android below API 33: no OS confirmation, and a touch platform's ✓ is
        // not relied on as the confirmation.
        platformShowsOwnConfirmation: Boolean = false,
        inlineCheckConfirms: Boolean = false,
        displayedArticleId: () -> String? = { "a1" },
    ) = ArticleUrlCopier(
        scope = scope,
        clipboard = clipboard,
        snackbarHostState = host,
        copiedMessage = message,
        platformShowsOwnConfirmation = platformShowsOwnConfirmation,
        inlineCheckConfirms = inlineCheckConfirms,
        displayedArticleId = displayedArticleId,
    )

    // showSnackbar suspends until dismissed (nothing here times it out), so the copier runs in
    // backgroundScope and each test reads the state after runCurrent().
    private fun TestScope.settle() = runCurrent()

    @Test
    fun copyingAnArticleTheReaderDoesNotShowStillShowsTheSnackbar() = runTest {
        val clipboard = CopyRecordingClipboard()
        val host = SnackbarHostState()
        val copier = copier(backgroundScope, clipboard, host, displayedArticleId = { null })

        copier.copy("https://example.com/a2", "a2")
        settle()

        assertEquals(listOf("https://example.com/a2"), clipboard.copied)
        assertEquals(message, host.currentSnackbarData?.visuals?.message)
        assertEquals(0, copier.pulse)
    }

    @Test
    fun copyingTheDisplayedArticlePulsesAndShowsTheSnackbar() = runTest {
        val clipboard = CopyRecordingClipboard()
        val host = SnackbarHostState()
        val copier = copier(backgroundScope, clipboard, host)

        copier.copy("https://example.com/a1", "a1")
        settle()

        assertEquals(1, copier.pulse)
        assertEquals(message, host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun copyingAnotherArticleDoesNotPulse() = runTest {
        val clipboard = CopyRecordingClipboard()
        val copier = copier(backgroundScope, clipboard, host = null)

        copier.copy("https://example.com/a2", "a2")
        settle()

        assertEquals(listOf("https://example.com/a2"), clipboard.copied)
        assertEquals(0, copier.pulse)
    }

    @Test
    fun aSecondCopyReplacesTheFirstSnackbarRatherThanQueueing() = runTest {
        val clipboard = CopyRecordingClipboard()
        val host = SnackbarHostState()
        val copier = copier(backgroundScope, clipboard, host)

        copier.copy("https://example.com/a1", "a1")
        settle()
        val first = host.currentSnackbarData
        copier.copy("https://example.com/a2", "a2")
        settle()
        val second = host.currentSnackbarData

        assertEquals(message, second?.visuals?.message)
        // A queued second snackbar would still be waiting behind the first one's data.
        assertNotSame(first, second)
        assertEquals(listOf("https://example.com/a1", "https://example.com/a2"), clipboard.copied)
    }

    @Test
    fun noSnackbarWhereThePlatformShowsItsOwnConfirmation() = runTest {
        val clipboard = CopyRecordingClipboard()
        val host = SnackbarHostState()
        val copier = copier(backgroundScope, clipboard, host, platformShowsOwnConfirmation = true)

        copier.copy("https://example.com/a1", "a1")
        settle()

        assertEquals(listOf("https://example.com/a1"), clipboard.copied)
        assertEquals(1, copier.pulse)
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun whereTheCheckConfirmsOnlyACopyOfAnotherArticleShowsTheSnackbar() = runTest {
        val clipboard = CopyRecordingClipboard()
        val host = SnackbarHostState()
        val copier = copier(backgroundScope, clipboard, host, inlineCheckConfirms = true)

        copier.copy("https://example.com/a1", "a1")
        settle()
        assertEquals(1, copier.pulse)
        assertNull(host.currentSnackbarData, "the displayed article's ✓ is the confirmation")

        copier.copy("https://example.com/a2", "a2")
        settle()
        assertEquals(1, copier.pulse)
        assertEquals(message, host.currentSnackbarData?.visuals?.message)
    }

    @Test
    fun aBlankOrMissingUrlCopiesAndShowsNothing() = runTest {
        val clipboard = CopyRecordingClipboard()
        val host = SnackbarHostState()
        val copier = copier(backgroundScope, clipboard, host)

        copier.copy("   ", "a1")
        copier.copy("", "a1")
        copier.copy(null, "a1")
        settle()

        assertEquals(emptyList(), clipboard.copied)
        assertEquals(0, copier.pulse)
        assertNull(host.currentSnackbarData)
    }

    @Test
    fun noHostDoesNotCrash() = runTest {
        val clipboard = CopyRecordingClipboard()
        val copier = copier(backgroundScope, clipboard, host = null)

        copier.copy("https://example.com/a1", "a1")
        settle()

        assertEquals(listOf("https://example.com/a1"), clipboard.copied)
        assertEquals(1, copier.pulse)
    }
}

/** Records every text written, without touching the real OS clipboard. */
@OptIn(ExperimentalComposeUiApi::class)
private class CopyRecordingClipboard : Clipboard {
    val copied = mutableListOf<String>()

    override suspend fun getClipEntry(): ClipEntry? = null

    override suspend fun setClipEntry(clipEntry: ClipEntry?) {
        (clipEntry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String)?.let(copied::add)
    }

    override val nativeClipboard: NativeClipboard get() = Unit
}
