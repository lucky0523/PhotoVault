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

    /// Whether backup is currently paused
    @Published var isPaused: Bool = false

    /// Current pause reason (if paused)
    @Published var pauseReason: PauseReason?

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
    private var cancellables = Set<AnyCancellable>()

    private var viewContext: NSManagedObjectContext {
        persistenceController.viewContext
    }

    // MARK: - Initialization

    init(
        persistenceController: PersistenceController = .shared,
        chunkUploader: ChunkUploader = .shared,
        conditionChecker: BackupConditionChecker = .shared
    ) {
        self.persistenceController = persistenceController
        self.chunkUploader = chunkUploader
        self.conditionChecker = conditionChecker

        setupObservers()
        fetchCurrentTasks()
        fetchHistory()
    }

    // MARK: - Public Methods

    /// Refresh all data
    func refresh() {
        fetchCurrentTasks()
        fetchHistory()
    }

    /// Retry a failed history record by resetting it to pending
    func retryFailed(record: BackupHistory) {
        // Create a new UploadRecord from the failed history entry
        let newRecord = UploadRecord.create(
            in: viewContext,
            localFilePath: record.sourcePath,
            fileHash: "",
            fileSize: record.fileSize,
            fileName: record.fileName
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

        // Observe pause state
        chunkUploader.$isPaused
            .receive(on: DispatchQueue.main)
            .sink { [weak self] paused in
                self?.isPaused = paused
                if paused {
                    self?.pauseReason = self?.conditionChecker.currentPauseReason()
                } else {
                    self?.pauseReason = nil
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
                    self?.fetchHistory()
                }
            }
            .store(in: &cancellables)

        // Observe condition state changes
        conditionChecker.$conditionState
            .receive(on: DispatchQueue.main)
            .sink { [weak self] state in
                if !state.canBackup {
                    self?.pauseReason = state.pauseReason
                } else {
                    self?.pauseReason = nil
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
