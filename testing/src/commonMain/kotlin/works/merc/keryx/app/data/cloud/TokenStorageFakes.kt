package works.merc.keryx.app.data.cloud

/**
 * Minimal in-memory [TokenStorage] standing in for the plaintext fallback, shared by the
 * secure-store backend tests (desktop's `KeyringTokenStorageTest`/`LibSecretTokenStorageTest`, and
 * this module's own [SecretStoreTokenStorageTest]). [outcome] is what its own [save] reports, so a
 * test can model the fallback write itself failing. [clearOutcome] is what its own [clear]
 * reports; when it is [TokenClearOutcome.DATA_MAY_REMAIN], [stored] is left in place to model a
 * delete that did not actually remove the file.
 */
class RecordingTokenStorage(
    private val outcome: TokenSaveOutcome = TokenSaveOutcome.PLAINTEXT_FILE,
    private val clearOutcome: TokenClearOutcome = TokenClearOutcome.CLEARED,
) : TokenStorage {
    var stored: CloudCredentials? = null

    override fun save(credentials: CloudCredentials): TokenSaveOutcome {
        stored = credentials
        return outcome
    }

    override fun load(): CloudCredentials? = stored

    override fun clear(): TokenClearOutcome {
        if (clearOutcome == TokenClearOutcome.CLEARED) stored = null
        return clearOutcome
    }
}
