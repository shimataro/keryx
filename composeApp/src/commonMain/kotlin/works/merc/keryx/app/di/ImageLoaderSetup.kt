package works.merc.keryx.app.di

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.annotation.ExperimentalCoilApi
import coil3.disk.DiskCache
import coil3.network.ktor3.KtorNetworkFetcherFactory
import coil3.svg.SvgDecoder
import io.ktor.client.HttpClient
import okio.Path.Companion.toPath

/** Wires favicon/thumbnail loading: network fetch via the app's existing [HttpClient], SVG decoding, and an on-disk cache under [cacheDir]. */
@OptIn(ExperimentalCoilApi::class)
fun configureImageLoader(httpClient: HttpClient, cacheDir: String) {
    SingletonImageLoader.setSafe { context: PlatformContext ->
        ImageLoader.Builder(context)
            .components {
                add(KtorNetworkFetcherFactory(httpClient))
                add(SvgDecoder.Factory())
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.toPath())
                    .build()
            }
            .build()
    }
}
