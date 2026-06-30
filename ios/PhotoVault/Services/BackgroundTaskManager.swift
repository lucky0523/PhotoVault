import BackgroundTasks
import CoreData
import Foundation
import Photos
import UIKit

// MARK: - Background Task Manager

/// Manages background task registration and scheduling for periodic photo scanning
/// and background file uploads using the BGTaskScheduler framework.
class BackgroundTaskManager {
    // MARK: - Task Identifiers

    /// Background app refresh task for periodic photo folder scanning
    static let backgroundScanIdentifier = "com.photovault.ios.background-scan"

    /// Background processing task for file uploads
    static let backgroundUploadIdentifier = "com.photovault.ios.background-upload"

    // MARK: - Configuration

    /// Minimum interval between background scans (15 minutes)
    static let scanInterval: TimeInterval = 15 * 60

    /// Minimum interval between background upload tasks
    static let uploadInterval: TimeInterval = 5 * 60

    // MARK: - Singleton

    static let shared = BackgroundTaskManager()

    // MARK: - Properties

    private var isScanRegistered = false
    private var isUploadRegistered = false

    // MARK: - Initialization

    private init() {}

    // MARK: - Registration

    /// Register all background tasks. Must be called during app launch,
    /// before the application finishes launching.
    /// Call this in PhotoVaultApp or AppDelegate's didFinishLaunchingWithOptions.
    func registerBackgroundTasks() {
        registerScanTask()
        registerUploadTask()
    }

    /// Register the background scan task (BGAppRefreshTask)
    private func registerScanTask() {
        let registered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.backgroundScanIdentifier,
            using: nil
        ) { task in
            guard let appRefreshTask = task as? BGAppRefreshTask else { return }
            self.handleBackgroundScan(task: appRefreshTask)
        }

        isScanRegistered = registered
        if !registered {
            print("[BackgroundTaskManager] Failed to register scan task: \(Self.backgroundScanIdentifier)")
        }
    }

    /// Register the background upload task (BGProcessingTask)
    private func registerUploadTask() {
        let registered = BGTaskScheduler.shared.register(
            forTaskWithIdentifier: Self.backgroundUploadIdentifier,
            using: nil
        ) { task in
            guard let processingTask = task as? BGProcessingTask else { return }
            self.handleBackgroundUpload(task: processingTask)
        }

        isUploadRegistered = registered
        if !registered {
            print("[BackgroundTaskManager] Failed to register upload task: \(Self.backgroundUploadIdentifier)")
        }
    }

    // MARK: - Scheduling

    /// Schedule the next background scan task.
    /// Should be called after completing a scan or when the app enters background.
    func scheduleBackgroundScan() {
        let request = BGAppRefreshTaskRequest(identifier: Self.backgroundScanIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: Self.scanInterval)

        do {
            try BGTaskScheduler.shared.submit(request)
            print("[BackgroundTaskManager] Scheduled background scan for \(Self.scanInterval / 60) minutes from now")
        } catch {
            print("[BackgroundTaskManager] Failed to schedule background scan: \(error.localizedDescription)")
        }
    }

    /// Schedule the background upload processing task.
    /// Should be called when there are pending files to upload.
    func scheduleBackgroundUpload() {
        let request = BGProcessingTaskRequest(identifier: Self.backgroundUploadIdentifier)
        request.earliestBeginDate = Date(timeIntervalSinceNow: Self.uploadInterval)
        request.requiresNetworkConnectivity = true
        request.requiresExternalPower = false

        do {
            try BGTaskScheduler.shared.submit(request)
            print("[BackgroundTaskManager] Scheduled background upload")
        } catch {
            print("[BackgroundTaskManager] Failed to schedule background upload: \(error.localizedDescription)")
        }
    }

    /// Cancel all pending background task requests
    func cancelAllTasks() {
        BGTaskScheduler.shared.cancelAllTaskRequests()
        print("[BackgroundTaskManager] Cancelled all background tasks")
    }

    /// Cancel only the scan task
    func cancelScanTask() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.backgroundScanIdentifier)
    }

    /// Cancel only the upload task
    func cancelUploadTask() {
        BGTaskScheduler.shared.cancel(taskRequestWithIdentifier: Self.backgroundUploadIdentifier)
    }

    // MARK: - Task Handlers

    /// Handle the background scan task execution
    /// - Parameter task: The BGAppRefreshTask provided by the system
    private func handleBackgroundScan(task: BGAppRefreshTask) {
        // Schedule the next scan immediately so we keep the cadence
        scheduleBackgroundScan()

        // Create a task for performing the scan work
        let scanWork = Task {
            await performBackgroundScan()
        }

        // Handle task expiration
        task.expirationHandler = {
            scanWork.cancel()
        }

        // When scan completes, mark task as complete
        Task {
            _ = await scanWork.result
            task.setTaskCompleted(success: true)
        }
    }

    /// Handle the background upload task execution
    /// - Parameter task: The BGProcessingTask provided by the system
    private func handleBackgroundUpload(task: BGProcessingTask) {
        // Create a task for performing upload work
        let uploadWork = Task {
            await performBackgroundUpload()
        }

        // Handle task expiration
        task.expirationHandler = {
            uploadWork.cancel()
        }

        // When upload completes, mark task as complete
        Task {
            let result = await uploadWork.result
            switch result {
            case .success(let hasMore):
                // If there are more files to upload, schedule another task
                if hasMore {
                    scheduleBackgroundUpload()
                }
                task.setTaskCompleted(success: true)
            case .failure:
                task.setTaskCompleted(success: false)
                // Reschedule for retry
                scheduleBackgroundUpload()
            }
        }
    }

    // MARK: - Background Work

    /// Perform the background photo folder scanning.
    /// Checks backup conditions, scans the photo library for new photos,
    /// and creates UploadRecord entries in Core Data for pending uploads.
    private func performBackgroundScan() async {
        print("[BackgroundTaskManager] Performing background scan...")

        // 1. Check backup conditions (WiFi, battery > 50%)
        let conditionChecker = BackupConditionChecker.shared
        guard conditionChecker.canBackup() else {
            let reason = conditionChecker.currentPauseReason()?.rawValue ?? "未知"
            print("[BackgroundTaskManager] Scan skipped: conditions not met (\(reason))")
            return
        }

        // 2. Ensure photo library access
        let scanner = PhotoLibraryScanner.shared
        guard scanner.hasAccess else {
            let status = await scanner.requestAuthorization()
            guard status == .authorized || status == .limited else {
                print("[BackgroundTaskManager] Scan skipped: no photo library access")
                return
            }
        }

        // 3. Get last scan time from Core Data backup folders
        let context = PersistenceController.shared.newBackgroundContext()
        var lastScanDate: Date?

        await context.perform {
            let request = BackupFolder.fetchRequest()
            request.sortDescriptors = [NSSortDescriptor(key: "lastScanTime", ascending: false)]
            request.fetchLimit = 1

            if let folders = try? context.fetch(request), let latestFolder = folders.first {
                lastScanDate = latestFolder.lastScanTime
            }
        }

        // 4. Scan for new photos since the last scan
        let newPhotoIdentifiers = await scanner.scanForNewPhotos(since: lastScanDate)

        guard !newPhotoIdentifiers.isEmpty else {
            print("[BackgroundTaskManager] No new photos found")
            // Update scan time on all folders
            await updateLastScanTime(context: context)
            return
        }

        // 5. Create UploadRecords for new photos
        await context.perform {
            // Get default backup folder (or first configured one)
            let folderRequest = BackupFolder.fetchRequest()
            let folders = (try? context.fetch(folderRequest)) ?? []
            let targetFolder = folders.first

            for identifier in newPhotoIdentifiers {
                // Check if already exists in upload records
                let existingRequest = UploadRecord.fetchRequest() as NSFetchRequest<UploadRecord>
                existingRequest.predicate = NSPredicate(format: "localFilePath == %@", identifier)
                existingRequest.fetchLimit = 1

                let existingCount = (try? context.count(for: existingRequest)) ?? 0
                guard existingCount == 0 else { continue }

                // Create new upload record
                let record = UploadRecord(context: context)
                record.id = UUID()
                record.localFilePath = identifier // Store PHAsset identifier as path
                record.fileHash = "" // Will be computed during upload
                record.fileSize = 0 // Will be determined during upload
                record.fileName = identifier
                record.status = UploadStatus.pending.rawValue
                record.uploadedChunks = 0
                record.totalChunks = 0
                record.retryCount = 0
                record.createdAt = Date()
                record.backupFolder = targetFolder

                // Populate filename from PHAsset if available
                if let asset = PHAsset.fetchAssets(withLocalIdentifiers: [identifier], options: nil).firstObject {
                    let resources = PHAssetResource.assetResources(for: asset)
                    if let resource = resources.first(where: { $0.type == .photo }) ?? resources.first {
                        record.fileName = resource.originalFilename
                    }
                    record.fileModifiedTime = asset.creationDate
                }
            }

            // Update last scan time on folders
            for folder in folders {
                folder.lastScanTime = Date()
            }

            // Save context
            if context.hasChanges {
                do {
                    try context.save()
                    print("[BackgroundTaskManager] Created \(newPhotoIdentifiers.count) upload records")
                } catch {
                    print("[BackgroundTaskManager] Failed to save upload records: \(error)")
                }
            }
        }

        // 6. Schedule upload task if conditions are still met
        if conditionChecker.canBackup() {
            scheduleBackgroundUpload()
        }
    }

    /// Perform background file uploads.
    /// Returns true if there are more files to upload.
    /// Checks conditions before processing and uploads pending files.
    private func performBackgroundUpload() async -> Bool {
        print("[BackgroundTaskManager] Performing background upload...")

        // 1. Check backup conditions (WiFi, battery > 50%)
        let conditionChecker = BackupConditionChecker.shared
        guard conditionChecker.canBackup() else {
            let reason = conditionChecker.currentPauseReason()?.rawValue ?? "未知"
            print("[BackgroundTaskManager] Upload paused: conditions not met (\(reason))")
            return false
        }

        // 2. Get pending upload records from Core Data
        let context = PersistenceController.shared.newBackgroundContext()
        var hasPendingUploads = false

        await context.perform {
            let request = UploadRecord.pendingFetchRequest()
            request.fetchLimit = 10 // Process in batches

            guard let pendingRecords = try? context.fetch(request) else {
                return
            }

            hasPendingUploads = !pendingRecords.isEmpty

            if pendingRecords.isEmpty {
                print("[BackgroundTaskManager] No pending uploads")
            } else {
                print("[BackgroundTaskManager] Found \(pendingRecords.count) pending uploads")
            }
        }

        // 3. Return whether there are remaining files
        // Actual upload implementation will be added in the ChunkUploader task (21.3)
        return hasPendingUploads
    }

    /// Update lastScanTime on all backup folders
    private func updateLastScanTime(context: NSManagedObjectContext) async {
        await context.perform {
            let request = BackupFolder.fetchRequest()
            if let folders = try? context.fetch(request) {
                for folder in folders {
                    folder.lastScanTime = Date()
                }
                if context.hasChanges {
                    try? context.save()
                }
            }
        }
    }
}

// MARK: - App Lifecycle Integration

extension BackgroundTaskManager {
    /// Called when the app enters the background.
    /// Schedules appropriate background tasks.
    func applicationDidEnterBackground() {
        scheduleBackgroundScan()
        // Only schedule upload if there are pending files
        // This check will be implemented when the upload queue is ready
        scheduleBackgroundUpload()
    }

    /// Called when the app becomes active.
    /// Can be used to check task registration status.
    func applicationDidBecomeActive() {
        // Cancel and reschedule to reset timers
        // Tasks are automatically cancelled when app becomes active
    }
}
