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
/// A failed load is not remembered: it is retried the next time a view asks, as `AsyncImage` did.
@MainActor
final class FaviconCache {
    static let shared = FaviconCache()

    private let images = NSCache<NSString, PlatformImage>()
    /// One fetch per URL at a time, however many rows show the same feed's favicon at once.
    private var inFlight: [String: Task<PlatformImage?, Never>] = [:]

    func cachedImage(for url: String) -> PlatformImage? {
        images.object(forKey: url as NSString)
    }

    func image(for url: URL) async -> PlatformImage? {
        let key = url.absoluteString
        if let cached = cachedImage(for: key) { return cached }
        if let running = inFlight[key] { return await running.value }
        let task = Task { () -> PlatformImage? in
            guard let (data, _) = try? await URLSession.shared.data(from: url) else { return nil }
            return PlatformImage(data: data)
        }
        inFlight[key] = task
        let image = await task.value
        inFlight[key] = nil
        if let image { images.setObject(image, forKey: key as NSString) }
        return image
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
