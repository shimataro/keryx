package works.merc.keryx.app.di

import io.ktor.client.HttpClient
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.HttpClientEngineFactory
import io.ktor.client.plugins.HttpTimeout
import works.merc.keryx.app.core.CONNECTION_TIMEOUT_MS
import works.merc.keryx.app.core.REQUEST_TIMEOUT_MS

/**
 * The one HTTP client configuration every platform's `platformModule` shares — only the engine
 * differs (desktop's CIO, Android's OkHttp, each a target-only Gradle dependency). Kept in
 * commonMain rather than jvmCommonMain: nothing here touches a JVM API, only ktor-client-core's
 * common surface, so the config cannot drift per platform and a later non-JVM target reuses it too.
 */
internal fun <T : HttpClientEngineConfig> keryxHttpClient(engine: HttpClientEngineFactory<T>): HttpClient =
    HttpClient(engine) {
        // Statuses are handled explicitly everywhere (feed redirects, cloud errors).
        followRedirects = false
        expectSuccess = false
        install(HttpTimeout) {
            connectTimeoutMillis = CONNECTION_TIMEOUT_MS
            requestTimeoutMillis = REQUEST_TIMEOUT_MS
        }
    }
