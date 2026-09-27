package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals

class ErrorKindTest {

    @Test
    fun everyExceptionTypeMapsToItsOwnKind() {
        assertEquals(ErrorKind.FEED_TIMEOUT, FeedTimeoutException().errorKind)
        assertEquals(ErrorKind.FEED_FETCH, FeedFetchException("500", statusCode = 500).errorKind)
        assertEquals(ErrorKind.FEED_PARSE, FeedParseException("bad xml").errorKind)
        assertEquals(ErrorKind.FEED_GONE, FeedNotFoundException("410", isGone = true).errorKind)
        assertEquals(ErrorKind.FEED_NOT_FOUND, FeedNotFoundException("404").errorKind)
        assertEquals(ErrorKind.CLOUD_AUTH, CloudAuthException("401").errorKind)
        assertEquals(ErrorKind.CLOUD_DATA_INCOMPATIBLE, CloudDataIncompatibleException("corrupt").errorKind)
        assertEquals(ErrorKind.CLOUD_STORAGE, CloudStorageException("503").errorKind)
        assertEquals(ErrorKind.SYNC_CONFLICT, SyncConflictException().errorKind)
        assertEquals(ErrorKind.SCHEMA_VERSION, SchemaVersionException(localVersion = 2, cloudVersion = 3).errorKind)
        assertEquals(ErrorKind.UPDATE, UpdateException(UpdateStage.DOWNLOAD, "io").errorKind)
        assertEquals(ErrorKind.GENERIC, FeedDiscoveryException(emptyList()).errorKind)
    }

    @Test
    fun aSyncKeepsOnlyTheSyncRelatedKindsAndReportsAnythingElseAsGeneric() {
        assertEquals(ErrorKind.CLOUD_AUTH, CloudAuthException("401").syncErrorKind)
        assertEquals(ErrorKind.SCHEMA_VERSION, SchemaVersionException(localVersion = 2, cloudVersion = 3).syncErrorKind)
        assertEquals(ErrorKind.CLOUD_DATA_INCOMPATIBLE, CloudDataIncompatibleException("corrupt").syncErrorKind)
        assertEquals(ErrorKind.SYNC_CONFLICT, SyncConflictException().syncErrorKind)
        assertEquals(ErrorKind.CLOUD_STORAGE, CloudStorageException("503").syncErrorKind)
        assertEquals(ErrorKind.GENERIC, FeedTimeoutException().syncErrorKind)
        assertEquals(ErrorKind.GENERIC, FeedParseException("bad xml").syncErrorKind)
    }
}
