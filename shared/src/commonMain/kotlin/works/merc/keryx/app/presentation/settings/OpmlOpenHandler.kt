package works.merc.keryx.app.presentation.settings

import org.koin.core.Koin

/**
 * Hands an `.opml` document the app was opened with (desktop's file association, Android's
 * `ACTION_VIEW` intent, the Apple app's `onOpenURL`) to the settings Data tab, which imports it with
 * the same spinner and inline result as its own Import button — the platform-independent half of
 * the flow, shared by every platform.
 *
 * Only requests the import ([OpmlTransferController.request] with [OpmlRequest.ImportDocument]):
 * each UI opens Settings ▸ Data for a waiting request, and only over Home, so a file opened during
 * first-run Setup waits until Setup is done (and never writes during Setup's initial sync), and a
 * file opened while another import runs is imported after it. Nothing is posted to the
 * notification center; the result is shown on the Data tab.
 *
 * Reading the file itself stays platform-specific — desktop's `handleOpenedOpmlFile` reads a
 * filesystem path, Android's `handleOpmlOpenIfPresent` reads a `content://` `Uri`, Swift's
 * `AppModel.importOpenedOpml` reads a security-scoped URL.
 *
 * @param xml The document's contents, or `null` when it could not be read — then shown on the Data
 *   tab as an import failure rather than silently dropped.
 */
fun requestOpenedOpmlImport(koin: Koin, xml: String?) {
    koin.get<OpmlTransferController>().request(OpmlRequest.ImportDocument(xml))
}
