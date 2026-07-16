import Foundation
import CoreData
import Combine

// MARK: - History Filter

/// Filter options for backup history
enum HistoryFilter: String, CaseIterable {
    case all = "全部"
    case success = "成功"
    case failed = "失败"
}

// MARK: - Grouped History

/// A group of history records for a single date
struct HistoryDateGroup: Identifiable {
    let id: String  // date string as identifier
    let dateLabel: String
    let records: [BackupHistory]
}

// MARK: - Paused Task UI Model

/// A view-facing snapshot of an `AUTO_OFF` paused task (R-26.2).
///
/// Derived from a persisted `UploadRecord`; `id`/`fileURI` is the PHAsset local
/// identifier (the stable key used to resume or clear the task, mirroring
/// Android's `fileUri`).
struct PausedTaskUi: Identifiable, Equatable {
    /// PHAsset local identifier — stable key for resume/clear.
    let fileURI: String
    let fileName: String
    /// 0...100 integer percentage (see `BackupTaskViewModel.computeProgressPercent`).
    let progressPercent: Int
    /// When the task was paused (drives newest-first ordering).
    let pausedAt: Date

    var id: String { fileURI }
}

// MARK: - Backup Task View Model

/// ViewModel for the Backup Task Tab.
/// Manages current upload tasks, pending queue, and backup history from Core Data.
/// Observes ChunkUploader progress for real-time updates.
@MainActor
class BackupTaskViewModel: ObservableObject {
    // MARK: - Published Properties

    /// Currently uploading progress info from ChunkUploader
    @Published var currentProgress: UploadProgressInfo?

    /// The record currently being uploaded
    @Published var currentTask: UploadRecord?

    /// Pending upload records waiting in queue
    @Published var pendingQueue: [UploadRecord] = []

    /// Whether backup is currently paused (user OR condition)
    @Published var isPaused: Bool = false

    /// Whether the current pause was initiated by the user tapping "暂停" (R-24.5).
    /// When true, the banner shows the neutral "已手动暂停，点击开始继续" wording and
    /// backup only resumes on an explicit "开始"; condition recovery does not
    /// auto-resume it.
    @Published var isUserPaused: Bool = false

    /// Current *condition* pause reason (electricity/WiFi). `nil` for a user pause
    /// (see `isUserPaused`) or when not paused.
    @Published var pauseReason: PauseReason?

    /// Whether a backup is actively in progress (running and not paused). Mirrors
    /// Android `TasksTabUiState.isBackupRunning`.
    @Published var isBackupRunning: Bool = false

    /// Whether the in-progress run was started by an explicit user action (manual)
    /// vs an automatic trigger. Drives the "正在手动/自动备份" label on the button
    /// while a backup is actively running. Mirrors Android
    /// `BackupForegroundService.isManualRun` / `ChunkUploader.isManualRun`.
    @Published var isManualRun: Bool = false

    // MARK: - Manual Start/Pause Control (R-24)

    /// Label/semantics for the manual "开始/暂停" button (R-24.1): shows "暂停"
    /// while a backup is actively in progress, "开始" when paused or idle.
    var isStartPauseShowingPause: Bool {
        isBackupRunning && !isPaused
    }

    /// Whether the "开始/暂停" button is enabled (R-24.4): disabled only when the
    /// queue is empty AND there is no in-progress task (nothing running, nothing
    /// paused).
    var isStartPauseEnabled: Bool {
        !pendingQueue.isEmpty || isBackupRunning || isPaused
    }

    // MARK: - AUTO_OFF Paused Tasks (R-26)

    /// `AUTO_OFF` paused tasks rebuilt from persisted `UploadRecord`s, newest
    /// pause first. Kept separate from `pendingQueue` because these never
    /// auto-resume and are driven by user "继续"/"清除" actions (R-26.1/26.7).
    @Published var pausedTasks: [PausedTaskUi] = []

    /// Whether the paused-task list is currently loading (R-26.1).
    @Published var isPausedTasksLoading: Bool = false

    /// Set when reading paused tasks from Core Data failed; the UI shows an error
    /// with a retry entry and no record is modified (R-26.4).
    @Published var pausedTasksLoadError: Bool = false

    /// User-facing error surfaced when a "继续"/"清除" action fails (R-27.6/28.3).
    @Published var pausedTaskActionError: String?

    /// Backup history records (filtered)
    @Published var historyRecords: [BackupHistory] = []

    /// History grouped by date
    @Published var groupedHistory: [HistoryDateGroup] = []

    /// Current history filter
    @Published var historyFilter: HistoryFilter = .all {
        didSet { fetchHistory() }
    }

    /// Loading state
    @Published var isLoading: Bool = false

    // MARK: - Dependencies

    private let persistenceController: PersistenceController
    private let chunkUploader: ChunkUploader
    private let conditionChecker: BackupConditionChecker
    private let sourceExists: (String) -> Bool
    private var cancellables = Set<AnyCancellable>()

    private var viewContext: NSManagedObjectContext {
        persistenceController.viewContext
    }

    // MARK: - Initialization

    init(
        persistenceController: PersistenceController = .shared,
        chunkUploader: ChunkUploader = .shared,
        conditionChecker: BackupConditionChecker = .shared,
        sourceExists: @escaping (String) -> Bool = { identifier in
            PhotoLibraryScanner.shared.getAsset(for: identifier) != nil
        }
    ) {
        self.persistenceController = persistenceController
        self.chunkUploader = chunkUploader
        self.conditionChecker = conditionChecker
        self.sourceExists = sourceExists

        setupObservers()
        fetchCurrentTasks()
        loadPausedTasks()
        fetchHistory()
    }

    // MARK: - Public Methods

    /// Refresh all data
    func refresh() {
        fetchCurrentTasks()
        loadPausedTasks()
        fetchHistory()
    }

    // MARK: - AUTO_OFF Paused Tasks

    /// Compute the 0...100 integer upload progress for a paused task (R-26.2/26.3).
    ///
    /// Floors uploaded/total to an integer percent; returns 0 when the total is
    /// unavailable (<= 0). Pure so it can be reasoned about / unit-tested.
    static func computeProgressPercent(uploadedChunks: Int, totalChunks: Int) -> Int {
        guard totalChunks > 0 else { return 0 }
        let clampedUploaded = max(0, min(uploadedChunks, totalChunks))
        let percent = clampedUploaded * 100 / totalChunks
        return max(0, min(percent, 100))
    }

    /// Load and rebuild the `AUTO_OFF` paused-task list from persisted
    /// `UploadRecord`s (R-26.1). Filters out records past the 7-day session
    /// window (R-32.1), sorts newest-pause-first, and maps to `PausedTaskUi`.
    ///
    /// On a read failure the list is left untouched, the error flag is raised for
    /// the UI to show a retry entry, and no record is modified (R-26.4).
    func loadPausedTasks() {
        isPausedTasksLoading = true
        pausedTasksLoadError = false

        let request = UploadRecord.autoOffPausedFetchRequest()
        let sevenDaysAgo = Date().addingTimeInterval(-7 * 24 * 60 * 60)

        do {
            let records = try viewContext.fetch(request)
            let expiredRecords = records.filter { $0.createdAt <= sevenDaysAgo }
            let validRecords = records.filter { $0.createdAt > sevenDaysAgo }

            // Remove expired resumable records, not merely their UI rows. This
            // prevents an obsolete AUTO_OFF session from reappearing and lets a
            // future scan treat the source as new (R-32.1).
            if !expiredRecords.isEmpty {
                for record in expiredRecords { viewContext.delete(record) }
                do {
                    try viewContext.save()
                } catch {
                    viewContext.rollback()
                    throw error
                }
            }

            pausedTasks = validRecords.map { record in
                PausedTaskUi(
                    fileURI: record.localFilePath,
                    fileName: record.fileName,
                    progressPercent: Self.computeProgressPercent(
                        uploadedChunks: Int(record.uploadedChunks),
                        totalChunks: Int(record.totalChunks)
                    ),
                    pausedAt: record.pausedAt ?? record.createdAt
                )
            }
            isPausedTasksLoading = false
        } catch {
            // Keep existing entries, surface the error, modify nothing (R-26.4).
            isPausedTasksLoading = false
            pausedTasksLoadError = true
            print("[BackupTaskViewModel] Failed to load paused tasks: \(error.localizedDescription)")
        }
    }

    /// Resume a single `AUTO_OFF` paused task as a **manual** upload (R-27.1).
    ///
    /// - Precondition: the source PHAsset must still exist/readable; if not, the
    ///   record is deleted, the entry removed, and an error surfaced (R-27.6/32.3)
    ///   without any upload request.
    /// - Clears the AUTO_OFF marker and starts the upload with `source == manual`
    ///   so it runs to completion regardless of the auto-backup switch and is not
    ///   subject to auto-resume filtering (R-27.1/27.2).
    /// - Does NOT change the auto-backup switch, and leaves other paused tasks
    ///   untouched (R-27.2/30.5).
    func resumePausedTask(fileURI: String) {
        let request = UploadRecord.fetchRequest(localFilePath: fileURI)
        guard let record = try? viewContext.fetch(request).first else {
            // Nothing to resume; make sure the (stale) entry is gone.
            pausedTasks.removeAll { $0.fileURI == fileURI }
            return
        }

        // Source-file presence check (R-27.6/32.3). On iOS the "source file" is a
        // PHAsset addressed by local identifier.
        guard sourceExists(record.localFilePath) else {
            viewContext.delete(record)
            persistenceController.save()
            pausedTasks.removeAll { $0.fileURI == fileURI }
            pausedTaskActionError = "源文件已不存在，无法续传"
            return
        }

        // Clear the AUTO_OFF marker and re-tag as a manual run (R-27.1). Validity
        // checks (7-day expiry / modified file) are handled inside ChunkUploader's
        // resume path, which discards and restarts from chunk 0 when needed
        // (R-27.5/32.2).
        record.clearAutoOffPause()
        record.uploadSource = .manual
        record.status = UploadStatus.pending.rawValue
        persistenceController.save()

        // Remove from the paused list immediately; it becomes an in-progress task.
        pausedTasks.removeAll { $0.fileURI == fileURI }

        // Kick off the manual upload. ChunkUploader records the run as manual
        // (isManualRun == true) so toggling auto-backup off won't abort it.
        Task { [weak self] in
            _ = await self?.chunkUploader.uploadFile(record: record)
            await MainActor.run {
                self?.refresh()
            }
        }

        refresh()
    }

    /// Clear (permanently discard) a single `AUTO_OFF` paused task (R-28.2).
    ///
    /// Deletes the underlying `UploadRecord`; on success the entry is removed
    /// from the list (R-28.4). On failure the entry and record are preserved and
    /// an error is surfaced (R-28.3).
    func clearPausedTask(fileURI: String) {
        let request = UploadRecord.fetchRequest(localFilePath: fileURI)
        do {
            if let record = try viewContext.fetch(request).first {
                viewContext.delete(record)
                try viewContext.save()
            }
            // Whether or not a record existed, the entry should be gone.
            pausedTasks.removeAll { $0.fileURI == fileURI }
        } catch {
            // Preserve entry + record, surface the failure (R-28.3).
            pausedTaskActionError = "清除失败，请重试"
            print("[BackupTaskViewModel] Failed to clear paused task: \(error.localizedDescription)")
        }
    }

    /// Toggle the manual start/pause control (R-24.1/24.2/24.3).
    ///
    /// - While a backup is actively in progress, pause it as a **user pause**
    ///   (preserving 断点续传 progress, not to be auto-resumed by condition
    ///   recovery).
    /// - While paused or idle, resume from the persisted breakpoint; the uploader
    ///   re-checks `Backup_Condition` before continuing, so it only proceeds when
    ///   conditions allow.
    func toggleStartPause() {
        if isBackupRunning && !isPaused {
            chunkUploader.pause()
        } else {
            chunkUploader.resume()
        }
    }

    /// Retry a failed history record by resetting it to pending
    func retryFailed(record: BackupHistory) {
        // Create a new UploadRecord from the failed history entry
        let newRecord = UploadRecord.create(
            in: viewContext,
            localFilePath: record.sourcePath,
            fileHash: "",
            fileSize: record.fileSize,
            fileName: record.fileName,
            source: .manual  // task-page retry is an explicit user action (R-3.15)
        )
        newRecord.status = UploadStatus.pending.rawValue
        persistenceController.save()

        // Refresh data
        fetchCurrentTasks()
    }

    /// Format file size for display
    func formatFileSize(_ bytes: Int64) -> String {
        let formatter = ByteCountFormatter()
        formatter.allowedUnits = [.useKB, .useMB, .useGB]
        formatter.countStyle = .file
        return formatter.string(fromByteCount: bytes)
    }

    /// Format upload speed for display
    func formatSpeed(_ bytesPerSecond: Double) -> String {
        if bytesPerSecond <= 0 { return "0 KB/s" }

        if bytesPerSecond >= 1_048_576 {
            return String(format: "%.1f MB/s", bytesPerSecond / 1_048_576)
        } else {
            return String(format: "%.0f KB/s", bytesPerSecond / 1024)
        }
    }

    /// Estimate remaining time based on progress and speed
    func estimateRemainingTime(progress: Double, speed: Double, fileSize: Int64) -> String {
        guard speed > 0, progress < 1.0 else { return "--" }

        let remainingBytes = Double(fileSize) * (1.0 - progress)
        let remainingSeconds = remainingBytes / speed

        if remainingSeconds < 60 {
            return "\(Int(remainingSeconds))秒"
        } else if remainingSeconds < 3600 {
            let minutes = Int(remainingSeconds) / 60
            let seconds = Int(remainingSeconds) % 60
            return "\(minutes)分\(seconds)秒"
        } else {
            let hours = Int(remainingSeconds) / 3600
            let minutes = (Int(remainingSeconds) % 3600) / 60
            return "\(hours)小时\(minutes)分"
        }
    }

    // MARK: - Private Methods

    /// Set up Combine observers for ChunkUploader progress
    private func setupObservers() {
        // Observe upload progress
        chunkUploader.$currentProgress
            .receive(on: DispatchQueue.main)
            .sink { [weak self] progress in
                self?.currentProgress = progress
            }
            .store(in: &cancellables)

        // Observe run state (drives the start/pause button label & enablement)
        chunkUploader.$isRunning
            .receive(on: DispatchQueue.main)
            .sink { [weak self] running in
                self?.isBackupRunning = running
            }
            .store(in: &cancellables)

        // Observe run source (manual vs automatic) for the "正在手动/自动备份" label.
        chunkUploader.$isManualRun
            .receive(on: DispatchQueue.main)
            .sink { [weak self] manual in
                self?.isManualRun = manual
            }
            .store(in: &cancellables)

        // Observe user-pause state (R-24.5): a user pause overrides any condition
        // reason so the banner shows the neutral "点击开始继续" wording.
        chunkUploader.$isUserPaused
            .receive(on: DispatchQueue.main)
            .sink { [weak self] userPaused in
                self?.isUserPaused = userPaused
                if userPaused {
                    self?.pauseReason = nil
                }
            }
            .store(in: &cancellables)

        // Observe pause state
        chunkUploader.$isPaused
            .receive(on: DispatchQueue.main)
            .sink { [weak self] paused in
                guard let self = self else { return }
                self.isPaused = paused
                if paused {
                    // A user pause takes priority over any condition reason (R-24.5).
                    if self.chunkUploader.isUserPaused {
                        self.pauseReason = nil
                    } else {
                        self.pauseReason = self.conditionChecker.currentPauseReason()
                    }
                } else {
                    self.pauseReason = nil
                }
            }
            .store(in: &cancellables)

        // Observe upload state changes to refresh data
        chunkUploader.$uploadState
            .receive(on: DispatchQueue.main)
            .removeDuplicates()
            .sink { [weak self] state in
                if state == .completed || state == .failed || state == .skippedDuplicate {
                    self?.fetchCurrentTasks()
                    self?.loadPausedTasks()
                    self?.fetchHistory()
                }
            }
            .store(in: &cancellables)

        // Observe condition state changes. A user pause takes priority (R-24.5):
        // while user-paused we keep the neutral user-pause banner and never surface
        // a condition reason, because condition recovery must not auto-resume it.
        conditionChecker.$conditionState
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in
                guard let self = self else { return }
                if self.isUserPaused { return }
                if !state.canBackup {
                    self.pauseReason = state.pauseReason
                } else {
                    self.pauseReason = nil
                }
            }
            .store(in: &cancellables)
    }

    /// Fetch current uploading task and pending queue from Core Data
    private func fetchCurrentTasks() {
        // Fetch the currently uploading record
        let uploadingRequest = UploadRecord.fetchRequest() as NSFetchRequest<UploadRecord>
        uploadingRequest.predicate = NSPredicate(format: "status == %@", UploadStatus.uploading.rawValue)
        uploadingRequest.fetchLimit = 1

        // Fetch pending records
        let pendingRequest = UploadRecord.pendingFetchRequest()

        do {
            let uploadingRecords = try viewContext.fetch(uploadingRequest)
            currentTask = uploadingRecords.first

            pendingQueue = try viewContext.fetch(pendingRequest)
        } catch {
            print("[BackupTaskViewModel] Failed to fetch tasks: \(error.localizedDescription)")
        }
    }

    /// Fetch backup history from Core Data with current filter applied
    private func fetchHistory() {
        let request = BackupHistory.fetchRequest() as NSFetchRequest<BackupHistory>
        request.sortDescriptors = [NSSortDescriptor(key: "completedAt", ascending: false)]

        // Apply filter
        switch historyFilter {
        case .all:
            break // no predicate
        case .success:
            request.predicate = NSPredicate(format: "status == %@", BackupResultStatus.success.rawValue)
        case .failed:
            request.predicate = NSPredicate(format: "status == %@", BackupResultStatus.failed.rawValue)
        }

        do {
            historyRecords = try viewContext.fetch(request)
            groupedHistory = groupHistoryByDate(historyRecords)
        } catch {
            print("[BackupTaskViewModel] Failed to fetch history: \(error.localizedDescription)")
        }
    }

    /// Group history records by date
    private func groupHistoryByDate(_ records: [BackupHistory]) -> [HistoryDateGroup] {
        let calendar = Calendar.current
        let dateFormatter = DateFormatter()
        dateFormatter.locale = Locale(identifier: "zh_CN")

        var groups: [String: [BackupHistory]] = [:]
        var groupOrder: [String] = []

        for record in records {
            let dateKey: String
            if calendar.isDateInToday(record.completedAt) {
                dateKey = "今天"
            } else if calendar.isDateInYesterday(record.completedAt) {
                dateKey = "昨天"
            } else {
                dateFormatter.dateFormat = "MM月dd日"
                dateKey = dateFormatter.string(from: record.completedAt)
            }

            if groups[dateKey] == nil {
                groups[dateKey] = []
                groupOrder.append(dateKey)
            }
            groups[dateKey]?.append(record)
        }

        return groupOrder.compactMap { key in
            guard let records = groups[key] else { return nil }
            return HistoryDateGroup(id: key, dateLabel: key, records: records)
        }
    }
}
