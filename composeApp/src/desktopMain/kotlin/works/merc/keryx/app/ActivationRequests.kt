package works.merc.keryx.app

import kotlinx.coroutines.flow.MutableSharedFlow

// Asks the main window to show itself (un-minimize, raise, focus); collected inside main()'s window
// content.
// replay = 1 (not extraBufferCapacity): on a cold .opml launch, incomingArg is dispatched — and can
// finish importing and tryEmit here — before Compose mounts the window and the LaunchedEffect in
// main() starts collecting. With replay = 0, a value emitted with no active subscriber is simply
// dropped (see kotlinx.coroutines SharedFlow docs), which would leave the window hidden if
// startMinimized is set. replay = 1 guarantees the first subscriber still receives it regardless of
// ordering.
internal val activationRequests = MutableSharedFlow<Unit>(replay = 1)
