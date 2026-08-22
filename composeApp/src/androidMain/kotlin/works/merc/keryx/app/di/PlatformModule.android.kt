package works.merc.keryx.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import org.koin.core.module.Module
import org.koin.dsl.module
import works.merc.keryx.app.core.CONNECTION_TIMEOUT_MS
import works.merc.keryx.app.core.REQUEST_TIMEOUT_MS
import works.merc.keryx.app.domain.CloudSession

/**
 * Android `platformModule`. Cloud sync itself is Phase 4 work (Custom Tabs OAuth,
 * `EncryptedSharedPreferences` `TokenStorage`, the real `DatabaseMerger`/`DatabaseSnapshot`); for
 * now [CloudSession] is registered with no providers so `CloudStorageAvailability.available` is
 * empty (hiding the sync UI, since its `BuildConfig` secrets are unset by default anyway — see
 * `jvmCommonMain`'s `CloudStorageAvailability.kt`) and `SyncRepository.sync()`'s
 * `cloudProvider() ?: return` guard short-circuits before ever reaching the still-stubbed
 * `DatabaseMerger`/`DatabaseSnapshot` actuals.
 */
actual val platformModule: Module = module {
    single {
        HttpClient(OkHttp) {
            // Statuses are handled explicitly everywhere (feed redirects, cloud errors).
            followRedirects = false
            expectSuccess = false
            install(HttpTimeout) {
                connectTimeoutMillis = CONNECTION_TIMEOUT_MS
                requestTimeoutMillis = REQUEST_TIMEOUT_MS
            }
        }
    }

    single {
        CloudSession(
            providers = emptyMap(),
            selectedType = { null },
            clock = get(),
        )
    }
}
