package works.merc.keryx.app.domain

import works.merc.keryx.app.core.CloudStorageType
import works.merc.keryx.app.data.cloud.OAuthTokens

/**
 * The domain/settings side of connecting and disconnecting a cloud provider: which state has to
 * change, and in what order, once an OAuth authorization has succeeded or a connection is torn
 * down. Every UI (the Compose settings and setup screens, the SwiftUI app) goes through this so
 * the ordering guarantees below live in one place instead of being re-implemented per screen.
 *
 * Deliberately *not* here: awaiting the interactive OAuth flow, its cancellation, the per-phase
 * loading state, and triggering the initial sync afterwards. Those are presentation concerns each
 * UI owns (see `awaitCancellableConnect`); this class starts where the tokens are already in hand.
 *
 * Both calls may touch the OS secure credential store (on macOS the `security` CLI, which can
 * block on an authorization dialog), so a UI caller runs them off its main thread.
 */
class CloudConnectionService(
    private val cloudSession: CloudSession,
    private val settingsRepository: SettingsRepository,
    private val syncRepository: SyncRepository,
) {

    /**
     * Records a successful authorization for [type]: saves [tokens], selects [type] as the active
     * provider in the device-local settings, and flushes those settings to disk.
     *
     * The flush is load-bearing and must complete before the caller starts any sync. The tokens
     * are written durably to the secure store first, but the provider selection would otherwise
     * sit in [SettingsRepository]'s coalesced write; a crash in that window would leave tokens
     * present but `cloudStorageType` null, so [CloudSession.current] reports local-only and every
     * later sync silently does nothing.
     */
    suspend fun completeConnect(type: CloudStorageType, tokens: OAuthTokens) {
        cloudSession.saveTokens(type, tokens)
        settingsRepository.mutateLocalSettings { it.copy(cloudStorageType = type.id) }
        settingsRepository.flush()
    }

    /**
     * Disconnects [type] and clears everything a subsequent connect must not inherit: its stored
     * tokens (revoked first), the sync failure state and per-provider sync markers (see
     * [SyncRepository.clearSyncFailureState] — which waits out a sync in flight, since it takes the
     * sync's own lock), and the persisted provider selection.
     */
    suspend fun tearDown(type: CloudStorageType) {
        cloudSession.disconnect(type)
        syncRepository.clearSyncFailureState()
        settingsRepository.mutateLocalSettings { it.copy(cloudStorageType = null) }
    }
}
