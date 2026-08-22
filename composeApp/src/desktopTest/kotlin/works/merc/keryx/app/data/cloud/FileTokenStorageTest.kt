package works.merc.keryx.app.data.cloud

import works.merc.keryx.app.core.Log
import works.merc.keryx.app.platform.AppDirs
import works.merc.keryx.app.platform.FileIO
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.util.logging.Handler
import java.util.logging.LogRecord
import java.util.logging.Logger
import kotlin.random.Random
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileTokenStorageTest {
    private val dir = FileIO.join(AppDirs.tempDir(), "token-storage-test-${Random.nextInt()}")
    private val storage = FileTokenStorage(dirOverride = dir)

    @AfterTest
    fun cleanup() {
        // A permission-manipulating test may still hold the directory read-only if it failed
        // before its own finally block ran; restore write access so cleanup can actually delete.
        runCatching {
            Files.setPosixFilePermissions(Paths.get(dir), PosixFilePermission.values().toSet())
        }
        FileIO.delete(FileIO.join(dir, ".dropbox_tokens.json"))
    }

    private fun withCapturedLogRecords(block: () -> Unit): List<LogRecord> {
        val logger = Logger.getLogger(Log.LOGGER_NAME)
        val captured = mutableListOf<LogRecord>()
        val handler = object : Handler() {
            override fun publish(record: LogRecord) { captured.add(record) }
            override fun flush() {}
            override fun close() {}
        }
        logger.addHandler(handler)
        try {
            block()
        } finally {
            logger.removeHandler(handler)
        }
        return captured
    }

    /** POSIX-only: makes the token directory read-only, runs [block], then restores permissions. */
    private fun withReadOnlyDir(block: () -> Unit) {
        val path = Paths.get(dir)
        val original = try {
            Files.getPosixFilePermissions(path)
        } catch (_: UnsupportedOperationException) {
            return // non-POSIX filesystem: nothing to test
        }
        Files.setPosixFilePermissions(path, setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_EXECUTE))
        try {
            block()
        } finally {
            Files.setPosixFilePermissions(path, original)
        }
    }

    @Test
    fun saveThenLoadRoundTrips() {
        val tokens = OAuthTokens(
            accessToken = "access-123",
            refreshToken = "refresh-456",
            expiresAtMillis = 1_700_000_000_000L,
        )
        storage.save(tokens)
        val loaded = storage.load()
        assertEquals(tokens, loaded)
    }

    @Test
    fun loadReturnsNullWhenNoFileExists() {
        assertNull(storage.load())
    }

    @Test
    fun loadReturnsNullOnCorruptContent() {
        FileIO.writeText(FileIO.join(dir, ".dropbox_tokens.json"), "{ not valid json")
        assertNull(storage.load())
    }

    @Test
    fun saveDoesNotThrowWhenFileCannotBeWritten() {
        // dirOverride points at an existing regular FILE, so the token path's parent is not a
        // directory and writeText fails. save() must swallow the failure rather than propagate it
        // (which would otherwise abort the connect flow after the token is already held in memory).
        val blockingFile = FileIO.join(AppDirs.tempDir(), "token-block-${Random.nextInt()}")
        FileIO.writeText(blockingFile, "not a directory")
        try {
            val storage = FileTokenStorage(dirOverride = blockingFile)
            storage.save(OAuthTokens(accessToken = "access-123")) // must not throw
            assertNull(storage.load())
        } finally {
            FileIO.delete(blockingFile)
        }
    }

    @Test
    fun savedTokenFileIsNotReadableByGroupOrOthers() {
        // The refresh token must never be group/world-readable, even transiently. On POSIX
        // filesystems verify the persisted file is owner-only; skip elsewhere (e.g. Windows).
        val path = java.nio.file.Paths.get(FileIO.join(dir, ".dropbox_tokens.json"))
        storage.save(OAuthTokens(accessToken = "access-123", refreshToken = "refresh-456"))
        val perms = try {
            java.nio.file.Files.getPosixFilePermissions(path)
        } catch (_: UnsupportedOperationException) {
            return // non-POSIX filesystem: nothing to assert
        }
        val forbidden = setOf(
            java.nio.file.attribute.PosixFilePermission.GROUP_READ,
            java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
        )
        assertEquals(emptySet(), perms.intersect(forbidden), "Token file must not be group/other readable")
    }

    @Test
    fun clearDeletesFileAndSubsequentLoadReturnsNull() {
        storage.save(OAuthTokens(accessToken = "access-123"))
        assertEquals("access-123", storage.load()?.accessToken)

        storage.clear()

        assertNull(storage.load())
    }

    @Test
    fun saveDoesNotLeaveTempFileBehind() {
        storage.save(OAuthTokens(accessToken = "access-123"))
        assertFalse(File(FileIO.join(dir, ".dropbox_tokens.json.tmp")).exists())
    }

    @Test
    fun saveFailureLeavesPreviousTokenFileIntact() {
        // save() writes to a temp file and atomically replaces the target, so a failure partway
        // through (here: the directory becomes unwritable) must never truncate the existing file.
        storage.save(OAuthTokens(accessToken = "original"))

        withReadOnlyDir {
            storage.save(OAuthTokens(accessToken = "replacement")) // must not throw
        }

        assertEquals("original", storage.load()?.accessToken)
    }

    @Test
    fun clearLogsWarningWhenDeleteReturnsFalse() {
        storage.save(OAuthTokens(accessToken = "access-123"))

        val records = mutableListOf<LogRecord>()
        withReadOnlyDir {
            records += withCapturedLogRecords { storage.clear() } // must not throw
        }

        assertTrue(
            records.any { it.message.contains("delete returned false") },
            "expected a warning about the failed delete, got: ${records.map { it.message }}",
        )
    }
}
