package works.merc.keryx.app.presentation.settings

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.getAndUpdate
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import works.merc.keryx.app.core.Log

/** The outcome of one OPML operation, shown inline next to the Data tab's import/export buttons. Data only — each UI localizes it. */
sealed interface OpmlResult {
    data class Imported(val added: Int, val failed: Int) : OpmlResult
    data object Exported : OpmlResult
    data object ExportFailed : OpmlResult
    data object ImportFailed : OpmlResult
}

/** Which OPML operation is running, so a UI can put its spinner on the matching button. */
enum class OpmlOperation { Importing, Exporting }

/**
 * An OPML operation asked for from outside the settings Data tab — the File menu's Import/Export
 * items, or an `.opml` file the app was opened with — which the Data tab carries out, so every route shows the same spinner and
 * result in the same place. (Entry names avoid Swift keywords, since the SwiftUI app switches over
 * them.)
 */
sealed interface OpmlRequest {
    /** Choose a file and import it. */
    data object ImportFile : OpmlRequest

    /** Choose where to save, and export there. */
    data object ExportFile : OpmlRequest

    /** Import a document already read from an opened file (`null`: reading it failed). */
    data class ImportDocument(val xml: String?) : OpmlRequest
}

/**
 * The one owner of OPML import/export state — busy flag, last result, and a request waiting for the
 * settings Data tab — shared by every route (the Data tab's own buttons, the File menu, an opened
 * `.opml` file) and every UI (Compose's `SettingsViewModel`/`DataTab`, the SwiftUI app via
 * `KeryxSdk.opmlController`). Wraps [OpmlTransfer], which does the document-level work.
 *
 * - [busy]/[running] are set the moment an operation begins ([tryBegin]) — before any file dialog
 *   opens — and cleared by [finish], so a second operation can never overlap the first, whichever
 *   route starts it, and the menu items can be disabled for the whole run.
 * - [result] is the last finished operation's outcome. Starting a new operation clears it. It is
 *   kept until a UI shows it and calls [clearResult], so one that finishes after the settings dialog
 *   was closed is shown once on the next visit to the Data tab.
 * - [pendingRequest] holds a request made outside the Data tab until the tab takes it with
 *   [consumeRequest], which hands it out only while nothing is running (a request made mid-run is
 *   carried out afterwards). The last request wins.
 *
 * @param scope The app scope (`KeryxSdk.close()` cancels and joins it), where [importResult] runs
 *   the import, so closing the SDK waits for — and cancels — an import started from any UI, rather
 *   than closing the database under one still running in a UI's own scope (a Swift `Task`).
 * @param dispatcher Where [importResult] runs the import (network fetches and DB writes), so a
 *   caller on the main thread (the SwiftUI app) never blocks it.
 */
class OpmlTransferController(
    private val transfer: OpmlTransfer,
    private val scope: CoroutineScope,
    private val dispatcher: CoroutineDispatcher = Dispatchers.Default,
) {
    private val _running = MutableStateFlow<OpmlOperation?>(null)
    private val _result = MutableStateFlow<OpmlResult?>(null)
    private val _pendingRequest = MutableStateFlow<OpmlRequest?>(null)

    /** The operation currently running, or `null`. */
    val running: StateFlow<OpmlOperation?> = _running.asStateFlow()

    /**
     * Whether any OPML operation is running ([running] is non-null). Derived from [running] on every
     * read (not a copy kept in step by hand, nor a `stateIn` one that would lag it), so the two can
     * never disagree.
     */
    val busy: StateFlow<Boolean> = DerivedStateFlow(
        compute = { _running.value != null },
        changes = _running.map { it != null },
    )

    /** The last finished operation's outcome, until [clearResult] (or the next [tryBegin]). */
    val result: StateFlow<OpmlResult?> = _result.asStateFlow()

    /** A request waiting for the Data tab to carry it out (see [consumeRequest]). */
    val pendingRequest: StateFlow<OpmlRequest?> = _pendingRequest.asStateFlow()

    /** Asks the Data tab to carry out [request]; replaces a request still waiting. */
    fun request(request: OpmlRequest) {
        _pendingRequest.value = request
    }

    /**
     * Takes (and clears) the waiting request — but only while nothing is running; returns `null`
     * otherwise, leaving the request to be taken once the running operation finishes.
     */
    fun consumeRequest(): OpmlRequest? {
        if (_running.value != null) return null
        return _pendingRequest.getAndUpdate { null }
    }

    /**
     * Marks [operation] as running and clears the previous [result]. Returns `false` (changing
     * nothing) when an operation is already running. Every successful call must be paired with one
     * [finish].
     */
    fun tryBegin(operation: OpmlOperation): Boolean {
        if (!_running.compareAndSet(null, operation)) return false
        _result.value = null
        return true
    }

    /**
     * Ends the running operation with [result] — `null` when it produced nothing to show (the user
     * dismissed the file dialog, or the operation was cancelled).
     */
    fun finish(result: OpmlResult?) {
        _result.value = result
        _running.value = null
    }

    /** Clears [result] once a UI has shown it. */
    fun clearResult() {
        _result.value = null
    }

    /** Builds the OPML document of the current subscriptions (see [OpmlTransfer.exportOpml]). */
    fun exportDocument(): String = transfer.exportOpml()

    /**
     * Imports [xml] and maps the outcome to an [OpmlResult], without touching [busy] — for a caller
     * that already holds the operation via [tryBegin]. `null` [xml] (the file could not be read) and
     * any failure are [OpmlResult.ImportFailed]; cancellation propagates.
     *
     * The import runs on the app [scope] (on [dispatcher]), not the caller's, so `KeryxSdk.close()` —
     * which cancels and joins that scope — waits for and cancels an import started from any UI.
     * A failure is mapped inside that coroutine, so it never reaches the scope's exception handler.
     * When the caller is cancelled, this waits for the import to actually stop before rethrowing, so
     * the caller's [finish] never releases [busy] while the import is still writing.
     */
    suspend fun importResult(xml: String?): OpmlResult {
        if (xml == null) return OpmlResult.ImportFailed
        val job = scope.async {
            try {
                val outcome = withContext(dispatcher) { transfer.importOpml(xml) }
                OpmlResult.Imported(outcome.added, outcome.failed)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.warn(TAG, "Failed to import OPML", e)
                OpmlResult.ImportFailed
            }
        }
        return try {
            job.await()
        } catch (e: CancellationException) {
            withContext(NonCancellable) { job.cancelAndJoin() }
            throw e
        }
    }

    /**
     * Runs a whole import of an already-read document — [tryBegin], [importResult], [finish] — and
     * returns `false` without doing anything when an operation is already running.
     */
    suspend fun importDocument(xml: String?): Boolean {
        if (!tryBegin(OpmlOperation.Importing)) return false
        var outcome: OpmlResult? = null
        try {
            outcome = importResult(xml)
        } finally {
            finish(outcome)
        }
        return true
    }

    private companion object {
        const val TAG = "OpmlTransfer"
    }
}

/**
 * The single definition of when a request waiting for Settings ▸ Data ([OpmlTransferController.pendingRequest])
 * should open it: whenever one is waiting and nothing is running — a request made mid-run opens the
 * tab once that run finishes. Both UIs call this (Compose's `App.kt`, SwiftUI's
 * `OpmlRequestPresenter` via `OpmlTransferObservable.shouldPresentRequest`) rather than each
 * re-deriving it.
 */
fun shouldPresentOpmlRequest(pending: OpmlRequest?, busy: Boolean): Boolean = pending != null && !busy
