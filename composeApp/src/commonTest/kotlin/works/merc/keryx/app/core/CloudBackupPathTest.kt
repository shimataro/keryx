package works.merc.keryx.app.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class CloudBackupPathTest {

    @Test
    fun backupPathIsDeterministicForAFixedInstant() {
        assertEquals("/keryx-19700101-000000.db.gz.bak", cloudBackupPath(0L))
        assertEquals("/keryx-19700101-000055.db.gz.bak", cloudBackupPath(55_000L))
        // 2026-08-11T10:30:00Z
        assertEquals("/keryx-20260811-103000.db.gz.bak", cloudBackupPath(1_786_444_200_000L))
    }

    @Test
    fun backupPathZeroPadsEveryComponent() {
        // 2026-01-02T03:04:05Z — every component is single-digit where possible.
        assertEquals("/keryx-20260102-030405.db.gz.bak", cloudBackupPath(1_767_323_045_000L))
    }

    @Test
    fun backupPathIsNotTheSyncPath() {
        val path = cloudBackupPath(0L)
        // Neither Google Drive's name-based lookup nor OneDrive's basename addressing must ever
        // resolve the archive to the same name as the live (compressed) sync file, or the legacy
        // fallback it is never allowed to touch.
        assertFalse(path == CLOUD_DB_GZ_PATH)
        assertFalse(path.substringAfterLast('/') == CLOUD_DB_GZ_PATH.substringAfterLast('/'))
        assertFalse(path == CLOUD_DB_PATH)
        assertFalse(path.substringAfterLast('/') == CLOUD_DB_PATH.substringAfterLast('/'))
    }
}
