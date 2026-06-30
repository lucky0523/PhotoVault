import Foundation
import Photos
import Combine

// MARK: - Photo Library Scanner

/// Scans the device photo library for new photos using PHPhotoLibrary.
/// Implements PHPhotoLibraryChangeObserver to detect real-time photo additions.
class PhotoLibraryScanner: NSObject, ObservableObject {
    // MARK: - Published State

    @Published private(set) var authorizationStatus: PHAuthorizationStatus = .notDetermined
    @Published private(set) var isScanning: Bool = false
    @Published private(set) var lastScanDate: Date?
    @Published private(set) var newPhotosDetected: Int = 0

    // MARK: - Properties

    private var changeObserverRegistered = false
    private var cancellables = Set<AnyCancellable>()

    /// Callback when new photos are detected via change observer
    var onNewPhotosDetected: (([String]) -> Void)?

    // MARK: - Singleton

    static let shared = PhotoLibraryScanner()

    // MARK: - Initialization

    override init() {
        super.init()
        authorizationStatus = PHPhotoLibrary.authorizationStatus(for: .readWrite)
    }

    deinit {
        unregisterChangeObserver()
    }

    // MARK: - Authorization

    /// Request photo library access.
    /// - Returns: The resulting authorization status
    @discardableResult
    func requestAuthorization() async -> PHAuthorizationStatus {
        let status = await PHPhotoLibrary.requestAuthorization(for: .readWrite)
        await MainActor.run {
            self.authorizationStatus = status
        }
        return status
    }

    /// Whether the app has sufficient access to read photos
    var hasAccess: Bool {
        return authorizationStatus == .authorized || authorizationStatus == .limited
    }

    // MARK: - Scanning

    /// Scan for new photos added after the given date.
    /// Returns the local identifiers of newly found PHAssets.
    /// - Parameter since: Only fetch photos created after this date. If nil, fetches all.
    /// - Returns: Array of PHAsset local identifiers for new images
    func scanForNewPhotos(since: Date?) async -> [String] {
        guard hasAccess else {
            print("[PhotoLibraryScanner] No photo library access")
            return []
        }

        await MainActor.run {
            self.isScanning = true
        }

        defer {
            Task { @MainActor in
                self.isScanning = false
                self.lastScanDate = Date()
            }
        }

        let fetchOptions = PHFetchOptions()

        // Filter: only images (not videos)
        var predicates: [NSPredicate] = [
            NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
        ]

        // Filter: only photos after the given date
        if let since = since {
            predicates.append(NSPredicate(format: "creationDate > %@", since as NSDate))
        }

        fetchOptions.predicate = NSCompoundPredicate(andPredicateWithSubpredicates: predicates)

        // Sort by creation date ascending (oldest first for queue ordering)
        fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: true)]

        let fetchResult = PHAsset.fetchAssets(with: fetchOptions)

        var identifiers: [String] = []
        identifiers.reserveCapacity(fetchResult.count)

        fetchResult.enumerateObjects { asset, _, _ in
            identifiers.append(asset.localIdentifier)
        }

        await MainActor.run {
            self.newPhotosDetected = identifiers.count
        }

        print("[PhotoLibraryScanner] Found \(identifiers.count) new photos since \(since?.description ?? "beginning")")
        return identifiers
    }

    /// Get PHAsset details for a given local identifier
    /// - Parameter identifier: The PHAsset local identifier
    /// - Returns: The PHAsset if found
    func getAsset(for identifier: String) -> PHAsset? {
        let result = PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil)
        return result.firstObject
    }

    /// Get file size for a PHAsset
    /// - Parameter asset: The PHAsset to inspect
    /// - Returns: File size in bytes, or 0 if unavailable
    func getFileSize(for asset: PHAsset) async -> Int64 {
        return await withCheckedContinuation { continuation in
            let resources = PHAssetResource.assetResources(for: asset)
            if let resource = resources.first(where: { $0.type == .photo }) ?? resources.first {
                if let fileSize = resource.value(forKey: "fileSize") as? Int64 {
                    continuation.resume(returning: fileSize)
                } else {
                    continuation.resume(returning: 0)
                }
            } else {
                continuation.resume(returning: 0)
            }
        }
    }

    /// Get the original filename for a PHAsset
    /// - Parameter asset: The PHAsset to inspect
    /// - Returns: Original filename or a generated one
    func getFileName(for asset: PHAsset) -> String {
        let resources = PHAssetResource.assetResources(for: asset)
        if let resource = resources.first(where: { $0.type == .photo }) ?? resources.first {
            return resource.originalFilename
        }
        return "IMG_\(asset.localIdentifier.prefix(8)).jpg"
    }

    // MARK: - Change Observer

    /// Register as a PHPhotoLibrary change observer to detect new photos in real-time
    func registerChangeObserver() {
        guard hasAccess, !changeObserverRegistered else { return }
        PHPhotoLibrary.shared().register(self)
        changeObserverRegistered = true
        print("[PhotoLibraryScanner] Registered photo library change observer")
    }

    /// Unregister the change observer
    func unregisterChangeObserver() {
        guard changeObserverRegistered else { return }
        PHPhotoLibrary.shared().unregisterChangeObserver(self)
        changeObserverRegistered = false
        print("[PhotoLibraryScanner] Unregistered photo library change observer")
    }
}

// MARK: - PHPhotoLibraryChangeObserver

extension PhotoLibraryScanner: PHPhotoLibraryChangeObserver {
    func photoLibraryDidChange(_ changeInstance: PHChange) {
        // Fetch only new images
        let fetchOptions = PHFetchOptions()
        fetchOptions.predicate = NSPredicate(format: "mediaType == %d", PHAssetMediaType.image.rawValue)
        fetchOptions.sortDescriptors = [NSSortDescriptor(key: "creationDate", ascending: false)]
        fetchOptions.fetchLimit = 50

        let allPhotos = PHAsset.fetchAssets(with: fetchOptions)
        let changeDetails = changeInstance.changeDetails(for: allPhotos)

        guard let details = changeDetails else { return }

        // Check for inserted objects (new photos)
        let insertedObjects = details.insertedObjects
        guard !insertedObjects.isEmpty else { return }

        let newIdentifiers = insertedObjects.map { $0.localIdentifier }

        DispatchQueue.main.async { [weak self] in
            self?.newPhotosDetected += newIdentifiers.count
            self?.onNewPhotosDetected?(newIdentifiers)
        }

        print("[PhotoLibraryScanner] Detected \(newIdentifiers.count) new photos via change observer")
    }
}
