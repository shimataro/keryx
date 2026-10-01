import ImageIO
import SwiftUI
#if os(macOS)
import AppKit
typealias PlatformImage = NSImage
#else
import UIKit
typealias PlatformImage = UIImage
#endif

/// Process-wide in-memory cache of decoded favicons, keyed by URL — the counterpart of the Compose
/// app's Coil3 memory cache. `AsyncImage` keeps nothing between instances, so every row scrolled
/// back into view re-fetched and re-decoded its favicon, showing the letter avatar meanwhile.
///
/// The download and the decode run off the main actor (`load(_:)`), and the image is decoded
/// straight to the size it is drawn at: a site's favicon is often a 180–512 px touch icon, which
/// decoded at full size costs both main-thread time and memory for a 16–32 pt slot. A failed load is
/// remembered for `failureTTL` (`FaviconFailureCache`), so a missing favicon is not re-requested by
/// every row that shows it; after that it is tried again.
@MainActor
final class FaviconCache {
    static let shared = FaviconCache()

    /// How long a failed URL is left alone before a view asking for it tries again.
    static let failureTTL: TimeInterval = 5 * 60

    /// The longest side a favicon is decoded to: the largest slot one is drawn in (the article
    /// row's 32 pt) at the densest display scale of the platform — 2x on a Mac, 3x on an iPhone.
    /// One size per URL, since the cache is keyed by URL alone.
    #if os(macOS)
    nonisolated static let maxPixelSize = 64
    #else
    nonisolated static let maxPixelSize = 96
    #endif

    private let images = NSCache<NSString, PlatformImage>()
    /// One fetch per URL at a time, however many rows show the same feed's favicon at once.
    private var inFlight: [String: Task<PlatformImage?, Never>] = [:]
    private var failures = FaviconFailureCache(ttl: FaviconCache.failureTTL)

    func cachedImage(for url: String) -> PlatformImage? {
        images.object(forKey: url as NSString)
    }

    func image(for url: URL) async -> PlatformImage? {
        let key = url.absoluteString
        if let cached = cachedImage(for: key) { return cached }
        if let running = inFlight[key] { return await running.value }
        guard !failures.isSuppressed(key, now: Date()) else { return nil }
        let task = Task { () -> PlatformImage? in
            switch await Self.load(url) {
            case .decoded(let image): return image.platformImage
            // A format ImageIO cannot read (an SVG favicon, say) still gets the platform image
            // decoder the cache always used — rare enough to leave on the main actor.
            case .undecoded(let data): return PlatformImage(data: data)
            case .failed: return nil
            }
        }
        inFlight[key] = task
        let image = await task.value
        inFlight[key] = nil
        if let image {
            images.setObject(image, forKey: key as NSString)
            failures.recordSuccess(key)
        } else {
            failures.recordFailure(key, now: Date())
        }
        return image
    }

    /// What `load(_:)` got for a URL.
    private enum LoadResult: Sendable {
        case decoded(DecodedFavicon)
        /// Downloaded, but not an image ImageIO can read.
        case undecoded(Data)
        case failed
    }

    /// Downloads and decodes `url` off the main actor.
    @concurrent
    private nonisolated static func load(_ url: URL) async -> LoadResult {
        guard let (data, _) = try? await URLSession.shared.data(from: url) else { return .failed }
        if let image = downsampledImage(from: data, maxPixelSize: maxPixelSize) {
            return .decoded(DecodedFavicon(cgImage: image))
        }
        return .undecoded(data)
    }

    /// Decodes `data` at no more than `maxPixelSize` on its longest side (never upscaled), picking
    /// the largest frame of a multi-size file — an `.ico` often lists a 16 px frame first, which
    /// would look blurry in the article row's 32 pt slot.
    private nonisolated static func downsampledImage(from data: Data, maxPixelSize: Int) -> CGImage? {
        let sourceOptions = [kCGImageSourceShouldCache: false] as CFDictionary
        guard let source = CGImageSourceCreateWithData(data as CFData, sourceOptions) else { return nil }
        let count = CGImageSourceGetCount(source)
        guard count > 0 else { return nil }
        let index = (0..<count).max { pixelArea(of: source, at: $0) < pixelArea(of: source, at: $1) } ?? 0
        let thumbnailOptions = [
            kCGImageSourceCreateThumbnailFromImageAlways: true,
            kCGImageSourceCreateThumbnailWithTransform: true,
            kCGImageSourceShouldCacheImmediately: true,
            kCGImageSourceThumbnailMaxPixelSize: maxPixelSize,
        ] as CFDictionary
        return CGImageSourceCreateThumbnailAtIndex(source, index, thumbnailOptions)
    }

    private nonisolated static func pixelArea(of source: CGImageSource, at index: Int) -> Int {
        guard let properties = CGImageSourceCopyPropertiesAtIndex(source, index, nil) as? [CFString: Any],
              let width = properties[kCGImagePropertyPixelWidth] as? Int,
              let height = properties[kCGImagePropertyPixelHeight] as? Int else { return 0 }
        return width * height
    }
}

/// A decoded favicon handed from `FaviconCache.load(_:)` back to the main actor. A `CGImage` is
/// immutable once created, so sharing it across the hop is safe.
private struct DecodedFavicon: @unchecked Sendable {
    let cgImage: CGImage

    @MainActor
    var platformImage: PlatformImage {
        #if os(macOS)
        // `.zero` takes the pixel size as the point size; `FaviconView` scales it to its slot anyway.
        NSImage(cgImage: cgImage, size: .zero)
        #else
        UIImage(cgImage: cgImage)
        #endif
    }
}

extension Image {
    init(platformImage: PlatformImage) {
        #if os(macOS)
        self.init(nsImage: platformImage)
        #else
        self.init(uiImage: platformImage)
        #endif
    }
}
