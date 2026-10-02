package works.merc.keryx.app.presentation.settings

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * [shouldPresentOpmlRequest] is the one rule both UIs use to open Settings ▸ Data for a waiting OPML
 * request (Compose's `App.kt`, SwiftUI's `OpmlRequestPresenter`), so its whole truth table is pinned here.
 */
class ShouldPresentOpmlRequestTest {

    @Test
    fun nothingIsPresentedWithoutAWaitingRequest() {
        assertFalse(shouldPresentOpmlRequest(pending = null, busy = false))
        assertFalse(shouldPresentOpmlRequest(pending = null, busy = true))
    }

    @Test
    fun aWaitingRequestIsPresentedWhileIdle() {
        assertTrue(shouldPresentOpmlRequest(pending = OpmlRequest.ImportFile, busy = false))
        assertTrue(shouldPresentOpmlRequest(pending = OpmlRequest.ExportFile, busy = false))
        assertTrue(shouldPresentOpmlRequest(pending = OpmlRequest.ImportDocument(null), busy = false))
    }

    @Test
    fun aWaitingRequestWaitsWhileAnOperationRuns() {
        assertFalse(shouldPresentOpmlRequest(pending = OpmlRequest.ImportFile, busy = true))
        assertFalse(shouldPresentOpmlRequest(pending = OpmlRequest.ImportDocument("<opml/>"), busy = true))
    }
}
