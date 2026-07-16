import Foundation
import CoreData
import Combine

// MARK: - Scan Interval Option

/// Available scan interval options in minutes
enum ScanInterval: Int, CaseIterable, Identifiable {
    case fiveMinutes = 5
    case fifteenMinutes = 15
    case thirtyMinutes = 30
    case sixtyMinutes = 60

    var id: Int { rawValue }

    var displayText: String {
        switch self {
        case .fiveMinutes: return "5 分钟"
        case .fifteenMinutes: return "15 分钟"
        case .thirtyMinutes: return "30 分钟"
        case .sixtyMinutes: return "60 分钟"
        }
    }
}

// MARK: - Settings View Model

/// ViewModel for the Settings Tab.
/// Manages backup conditions, storage policies, account information, and logout.
@MainActor
class SettingsViewModel: ObservableObject {
    // MARK: - Backup Condition Properties

    /// WiFi requirement is always on (display only, cannot be disabled)
    @Published var wifiRequired: Bool = true

    /// Minimum battery level for backup (0.20 - 0.80, step 0.05)
    @Published var minimumBatteryLevel: Double = 0.50 {
        didSet {
            saveBatteryLevel()
        }
    }

    /// Scan interval selection
    @Published var scanInterval: ScanInterval = .fifteenMinutes {
        didSet {
            saveScanInterval()
        }
    }

    /// Automatic (background) backup switch. When turned off, only explicit user
    /// actions ("立即备份" / single re-backup / retry) trigger backups; all
    /// automatic triggers stop enqueuing and uploading (R-3.8/3.10). Turning it
    /// off while a task is running also stops+clears an in-progress automatic
    /// task while leaving a manual one alone (R-3.13/3.14/3.15).
    @Published var autoBackupEnabled: Bool = true {
        didSet {
            guard !isLoadingSettings else { return }
            saveAutoBackupEnabled()
            if oldValue && !autoBackupEnabled {
                handleAutoBackupTurnedOff()
            } else if !oldValue && autoBackupEnabled {
                // Re-enable automatic discovery without touching any persisted
                // AUTO_OFF entries; those still require per-item "继续".
                BackgroundTaskManager.shared.scheduleBackgroundScan()
            }
        }
    }

    // MARK: - Storage Policy Properties

    @Published var backupFolders: [BackupFolder] = []

    // MARK: - Account Info Properties

    @Published var currentUsername: String = ""
    @Published var serverAddress: String = ""
    @Published var connectionDisplayText: String = "未连接"

    // MARK: - Logout State

    @Published var showLogoutConfirmation: Bool = false

    // MARK: - Dependencies

    private let tokenManager: TokenManager
    private let connectionManager: ConnectionManager
    private let persistenceController: PersistenceController
    private let keychain: KeychainManager
    private let chunkUploader: ChunkUploader
    private var cancellables = Set<AnyCancellable>()

    /// Guards `@Published` `didSet` handlers from firing while initial values
    /// are being loaded from persistence.
    private var isLoadingSettings: Bool = false

    // MARK: - UserDefaults Keys

    private let batteryLevelKey = "com.photovault.settings.minimumBatteryLevel"
    private let scanIntervalKey = "com.photovault.settings.scanInterval"
    // Auto-backup switch is persisted via `BackupPreferences` so background
    // triggers read the same value.
    private let savedServerAddressKey = "com.photovault.savedServerAddress"
    private let savedUsernameKey = "com.photovault.savedUsername"
    private let savedPasswordKey = "com.photovault.savedPassword"
    private let savedRememberPasswordKey = "com.photovault.savedRememberPassword"

    // MARK: - Initialization

    init(
        tokenManager: TokenManager = .shared,
        connectionManager: ConnectionManager = .shared,
        persistenceController: PersistenceController = .shared,
        keychain: KeychainManager = .shared,
        chunkUploader: ChunkUploader = .shared
    ) {
        self.tokenManager = tokenManager
        self.connectionManager = connectionManager
        self.persistenceController = persistenceController
        self.keychain = keychain
        self.chunkUploader = chunkUploader

        loadSettings()
        loadAccountInfo()
        fetchBackupFolders()
        observeConnectionState()
    }

    // MARK: - Public Methods

    /// Fetch all configured backup folders from Core Data
    func fetchBackupFolders() {
        let request = BackupFolder.fetchRequest()
        request.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: false)]

        do {
            backupFolders = try persistenceController.viewContext.fetch(request)
        } catch {
            backupFolders = []
        }
    }

    /// Update storage policy for a specific folder
    func updateStoragePolicy(
        for folder: BackupFolder,
        useCustomPath: Bool,
        customPath: String?,
        useYearMonthLayer: Bool
    ) {
        folder.useCustomPath = useCustomPath
        folder.customPath = customPath
        folder.useYearMonthLayer = useYearMonthLayer
        persistenceController.save()
        fetchBackupFolders()
    }

    /// Generate a brief policy summary for display in the list
    func policySummary(for folder: BackupFolder) -> String {
        var parts: [String] = []

        if folder.useCustomPath, let path = folder.customPath, !path.isEmpty {
            parts.append("自定义路径")
        } else {
            parts.append("默认路径")
        }

        if folder.useYearMonthLayer {
            parts.append("按年月分层")
        }

        return parts.joined(separator: " · ")
    }

    /// Format battery level as a percentage string (e.g. "50%")
    var batteryLevelText: String {
        return "\(Int(minimumBatteryLevel * 100))%"
    }

    /// Perform logout: clear tokens and keychain credentials
    func logout() {
        tokenManager.clearTokens()

        // Clear saved credentials from keychain
        keychain.delete(key: savedPasswordKey)

        // Clear saved credentials from UserDefaults
        UserDefaults.standard.removeObject(forKey: savedUsernameKey)
        UserDefaults.standard.removeObject(forKey: savedServerAddressKey)
        UserDefaults.standard.removeObject(forKey: savedRememberPasswordKey)
    }

    // MARK: - Private Methods

    /// Load saved settings from UserDefaults
    private func loadSettings() {
        isLoadingSettings = true
        defer { isLoadingSettings = false }

        let savedBattery = UserDefaults.standard.double(forKey: batteryLevelKey)
        if savedBattery >= 0.20 && savedBattery <= 0.80 {
            minimumBatteryLevel = savedBattery
        } else {
            minimumBatteryLevel = 0.50
        }

        let savedInterval = UserDefaults.standard.integer(forKey: scanIntervalKey)
        if let interval = ScanInterval(rawValue: savedInterval) {
            scanInterval = interval
        } else {
            scanInterval = .fifteenMinutes
        }

        autoBackupEnabled = BackupPreferences.autoBackupEnabled
    }

    /// Load account info from saved credentials
    private func loadAccountInfo() {
        currentUsername = UserDefaults.standard.string(forKey: savedUsernameKey) ?? "未知"
        serverAddress = UserDefaults.standard.string(forKey: savedServerAddressKey) ?? "未设置"
        updateConnectionDisplay()
    }

    /// Observe connection state changes from ConnectionManager
    private func observeConnectionState() {
        connectionManager.$connectionState
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                self?.updateConnectionDisplay()
            }
            .store(in: &cancellables)
    }

    /// Update the connection display text based on current state
    private func updateConnectionDisplay() {
        switch connectionManager.connectionState {
        case .connected(let type):
            connectionDisplayText = "已连接 (\(type.rawValue))"
        case .connecting:
            connectionDisplayText = "连接中..."
        case .disconnected:
            connectionDisplayText = "未连接"
        }
    }

    /// Persist battery level to UserDefaults
    private func saveBatteryLevel() {
        UserDefaults.standard.set(minimumBatteryLevel, forKey: batteryLevelKey)
    }

    /// Persist scan interval to UserDefaults
    private func saveScanInterval() {
        UserDefaults.standard.set(scanInterval.rawValue, forKey: scanIntervalKey)
    }

    /// Persist the auto-backup switch state.
    private func saveAutoBackupEnabled() {
        BackupPreferences.autoBackupEnabled = autoBackupEnabled
    }

    /// Handle the user turning the "自动备份" switch OFF while a task may be
    /// running (R-3.13/3.14/3.15).
    ///
    /// - If an in-progress task is **manual**, leave it entirely alone: it runs
    ///   to completion and its queue is untouched (R-3.15).
    /// - Otherwise (an automatic task is running, or only queued automatic files
    ///   remain), stop the automatic task and clear the queued automatic files.
    ///   The in-flight file keeps its 断点续传 progress so it resumes when the
    ///   switch is turned back on (R-3.14). Manually queued files are preserved.
    func handleAutoBackupTurnedOff() {
        if chunkUploader.isRunning && chunkUploader.isManualRun {
            // Manual task in progress: do not stop or clear anything (R-29.1).
            return
        }

        // Cancel pending automatic triggers. This does not cancel the explicit
        // in-process Task used by a manual upload.
        BackgroundTaskManager.shared.cancelScanTask()
        BackgroundTaskManager.shared.cancelUploadTask()

        // Stop any in-flight automatic upload (preserves its resume progress).
        chunkUploader.stopAuto()

        // Preserve the in-flight file as an AUTO_OFF paused task and clear the
        // not-started queued automatic files (R-25.1/25.2/25.3).
        markInFlightAsAutoOffPausedAndClearQueue(currentRecordID: chunkUploader.currentRecordID)
    }

    /// Partition result for the toggle-off handling (pure, unit-testable).
    struct AutoOffPartition: Equatable {
        /// Records to mark as `AUTO_OFF` paused (In_Flight_File — R-25.2).
        let toMark: [UUID]
        /// Records to delete (Queued_Not_Started_File — R-25.3).
        let toClear: [UUID]
    }

    /// A minimal, value-type snapshot of a pending automatic record, decoupled
    /// from Core Data so the partition logic can be reasoned about / tested
    /// without a managed object context.
    struct PendingRecordSnapshot: Equatable {
        let id: UUID
        /// Whether the record has 断点续传 progress worth preserving (see
        /// `UploadRecord.isInFlight`).
        let isInFlight: Bool
        /// Whether this is the record ChunkUploader is actively uploading; always
        /// treated as in-flight so its progress is never dropped.
        let isCurrent: Bool
    }

    /// Pure partition of pending automatic records into "mark as AUTO_OFF" vs
    /// "clear" sets, mirroring the Android pure function in task 27.3.
    ///
    /// - In_Flight_File (has progress or is the current upload) → mark AUTO_OFF
    ///   and keep (R-25.1/25.2).
    /// - Queued_Not_Started_File (no Upload_Record progress) → clear (R-25.3).
    /// - When no In_Flight_File exists, `toMark` is empty and everything is
    ///   cleared, and no Paused_Task is created (R-25.5).
    static func partitionOnAutoOff(_ records: [PendingRecordSnapshot]) -> AutoOffPartition {
        var toMark: [UUID] = []
        var toClear: [UUID] = []
        for record in records {
            if record.isInFlight || record.isCurrent {
                toMark.append(record.id)
            } else {
                toClear.append(record.id)
            }
        }
        return AutoOffPartition(toMark: toMark, toClear: toClear)
    }

    /// Apply the AUTO_OFF partition to the persisted pending automatic records:
    /// mark in-flight records as AUTO_OFF paused (preserving 断点续传 progress) and
    /// delete the not-started queued ones. Manually queued files are untouched
    /// (the fetch is scoped to `source == auto`).
    private func markInFlightAsAutoOffPausedAndClearQueue(currentRecordID: UUID?) {
        let context = persistenceController.viewContext
        let request = UploadRecord.fetchRequest() as NSFetchRequest<UploadRecord>
        request.predicate = NSPredicate(
            format: "(status == %@ OR status == %@) AND (source == %@ OR source == nil) AND (pauseSource == nil OR pauseSource != %@)",
            UploadStatus.pending.rawValue,
            UploadStatus.uploading.rawValue,
            BackupSource.auto.rawValue,
            PauseSource.autoOff.rawValue
        )

        do {
            let activeAutoRecords = try context.fetch(request)
            let expiryCutoff = Date().addingTimeInterval(-TimeInterval(ChunkUploader.sessionExpireDays * 24 * 60 * 60))
            let validAutoRecords = activeAutoRecords.filter { $0.createdAt > expiryCutoff }

            // Expired resume sessions are discarded consistently; a later scan
            // may rediscover the source as a new upload (R-32.1).
            for record in activeAutoRecords where record.createdAt <= expiryCutoff {
                context.delete(record)
            }

            let snapshots = validAutoRecords.map { record in
                PendingRecordSnapshot(
                    id: record.id,
                    isInFlight: record.isInFlight,
                    isCurrent: record.id == currentRecordID
                )
            }
            let partition = Self.partitionOnAutoOff(snapshots)
            let toMark = Set(partition.toMark)
            let toClear = Set(partition.toClear)

            let now = Date()
            for record in validAutoRecords {
                if toMark.contains(record.id) {
                    record.markAutoOffPaused(at: now)
                } else if toClear.contains(record.id) {
                    context.delete(record)
                }
            }

            // The record ChunkUploader is actively uploading may still carry the
            // `uploading` status (the cooperative `stopAuto()` only resets it to
            // pending at the next chunk boundary), so it is not in the pending
            // fetch above. Mark it AUTO_OFF here so its 断点续传 progress is kept
            // and it surfaces as a paused task (R-25.1/25.2).
            if let currentID = currentRecordID {
                let currentRequest = UploadRecord.fetchRequest() as NSFetchRequest<UploadRecord>
                currentRequest.predicate = NSPredicate(format: "id == %@", currentID as CVarArg)
                currentRequest.fetchLimit = 1
                if let current = try context.fetch(currentRequest).first,
                   current.uploadStatus != .completed,
                   current.uploadStatus != .skipped,
                   current.uploadSource == .auto,
                   !current.isAutoOffPaused {
                    current.markAutoOffPaused(at: now)
                }
            }

            persistenceController.save()
        } catch {
            print("[SettingsViewModel] Failed to process queued automatic files: \(error.localizedDescription)")
        }
    }
}
