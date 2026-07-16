import CoreData
import Foundation

// MARK: - Upload Status

/// Represents the status of a file upload
enum UploadStatus: String {
    case pending = "pending"
    case uploading = "uploading"
    case completed = "completed"
    case failed = "failed"
    case skipped = "skipped"
}

// MARK: - Backup Source

/// Records how a backup task / queued file was initiated.
///
/// Mirrors the Android `isManualRun` concept (see `BackupForegroundService`):
/// the source is the deciding factor for whether turning OFF the "自动备份"
/// switch aborts an in-progress task (R-3.13/3.14/3.15).
///
/// - `auto`: initiated by an automatic trigger (background scan, photo-library
///   change, condition recovery). These are stopped and their queued files are
///   cleared when the user disables auto-backup.
/// - `manual`: initiated by an explicit user action ("立即备份" / single
///   re-backup / task-list retry). These continue to completion regardless of
///   the auto-backup switch.
enum BackupSource: String {
    case auto = "auto"
    case manual = "manual"
}

// MARK: - Pause Source (R-29.3 / R-25.2)

/// Persisted reason a resumable `UploadRecord` is currently parked.
///
/// Mirrors the Android `UploadRecord.pause_source` column (values USER /
/// CONDITION / AUTO_OFF). On iOS only `AUTO_OFF` is persisted today — USER and
/// CONDITION pauses are represented in-memory by `BackupPauseOrigin` on the
/// `ChunkUploader` (see `ChunkUploader.pauseOrigin`) because those pauses only
/// exist while the app is running. `AUTO_OFF`, by contrast, must survive a
/// process restart so the file can be shown as a "已暂停" task on the next launch
/// (R-31.1), so it is stored on the record.
///
/// - `user`: the user tapped "暂停".
/// - `condition`: battery low / WiFi disconnected.
/// - `autoOff`: the user turned OFF the auto-backup switch while this file was
///   in-flight; the file keeps its 断点续传 progress and is shown as a paused
///   task that only resumes on an explicit "继续" (R-25.2 / R-30.4).
enum PauseSource: String {
    case user = "USER"
    case condition = "CONDITION"
    case autoOff = "AUTO_OFF"
}

// MARK: - Backup History Status

/// Represents the result status of a completed backup
enum BackupResultStatus: String {
    case success = "success"
    case failed = "failed"
    case skipped = "skipped"
}

// MARK: - BackupFolder NSManagedObject

/// Represents a local folder selected for backup.
/// Stores the folder path, storage policy configuration, and backup progress.
@objc(BackupFolder)
public class BackupFolder: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var folderPath: String
    @NSManaged public var folderName: String
    @NSManaged public var useCustomPath: Bool
    @NSManaged public var customPath: String?
    @NSManaged public var useYearMonthLayer: Bool
    @NSManaged public var totalFiles: Int32
    @NSManaged public var backedUpFiles: Int32
    @NSManaged public var lastScanTime: Date?
    @NSManaged public var createdAt: Date
    @NSManaged public var uploadRecords: NSSet?
}

// MARK: BackupFolder Convenience

extension BackupFolder {
    /// Backup progress as a percentage (0.0 - 1.0)
    var backupProgress: Double {
        guard totalFiles > 0 else { return 0.0 }
        return Double(backedUpFiles) / Double(totalFiles)
    }

    /// Whether all files have been backed up
    var isFullyBackedUp: Bool {
        return totalFiles > 0 && backedUpFiles >= totalFiles
    }

    /// Create a new BackupFolder in the given context
    static func create(
        in context: NSManagedObjectContext,
        folderPath: String,
        folderName: String,
        useCustomPath: Bool = false,
        customPath: String? = nil,
        useYearMonthLayer: Bool = false
    ) -> BackupFolder {
        let folder = BackupFolder(context: context)
        folder.id = UUID()
        folder.folderPath = folderPath
        folder.folderName = folderName
        folder.useCustomPath = useCustomPath
        folder.customPath = customPath
        folder.useYearMonthLayer = useYearMonthLayer
        folder.totalFiles = 0
        folder.backedUpFiles = 0
        folder.createdAt = Date()
        return folder
    }

    /// Fetch request for all backup folders
    static func fetchRequest() -> NSFetchRequest<BackupFolder> {
        return NSFetchRequest<BackupFolder>(entityName: "BackupFolder")
    }
}

// MARK: - UploadRecord NSManagedObject

/// Tracks the upload progress of an individual file.
/// Stores chunk progress for resumable uploads.
@objc(UploadRecord)
public class UploadRecord: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var localFilePath: String
    @NSManaged public var fileHash: String
    @NSManaged public var fileSize: Int64
    @NSManaged public var fileName: String
    @NSManaged public var status: String
    @NSManaged public var uploadedChunks: Int32
    @NSManaged public var totalChunks: Int32
    @NSManaged public var sessionId: String?
    @NSManaged public var retryCount: Int16
    @NSManaged public var errorMessage: String?
    @NSManaged public var fileModifiedTime: Date?
    @NSManaged public var createdAt: Date
    @NSManaged public var updatedAt: Date?
    /// How this file was queued: "auto" or "manual" (see `BackupSource`).
    /// Optional/defaulted to "auto" so older persisted stores decode cleanly.
    @NSManaged public var source: String?
    /// Why this resumable record is currently parked (see `PauseSource`): nil for
    /// a normal pending/uploading record, or "AUTO_OFF" when preserved after the
    /// user disabled auto-backup mid-upload (R-25.2 / R-29.3). Optional with no
    /// default so previously persisted stores load unchanged (lightweight
    /// migration adds it as NULL — see `PersistenceController`).
    @NSManaged public var pauseSource: String?
    /// When this record was parked (set together with `pauseSource == "AUTO_OFF"`).
    /// Used to sort the paused-task list newest-first (R-26.1). Optional/nil.
    @NSManaged public var pausedAt: Date?
    @NSManaged public var backupFolder: BackupFolder?
}

// MARK: UploadRecord Convenience

extension UploadRecord {
    /// Current upload status as enum
    var uploadStatus: UploadStatus {
        get { UploadStatus(rawValue: status) ?? .pending }
        set { status = newValue.rawValue }
    }

    /// The source that queued this record. Defaults to `.auto` for records
    /// created before source tracking existed (nil source).
    var uploadSource: BackupSource {
        get { BackupSource(rawValue: source ?? BackupSource.auto.rawValue) ?? .auto }
        set { source = newValue.rawValue }
    }

    /// Whether this record is currently parked because auto-backup was turned off
    /// mid-upload (R-25.2). Such records are shown as "已暂停" tasks and must not be
    /// auto-resumed (R-30.3); only an explicit "继续" clears the flag (R-30.4).
    var isAutoOffPaused: Bool {
        pauseSource == PauseSource.autoOff.rawValue
    }

    /// Whether this record represents an *in-flight* file, i.e. one that has
    /// already started uploading and therefore has 断点续传 progress worth
    /// preserving when auto-backup is disabled (R-25.1). This is the iOS
    /// equivalent of the Android criterion "存在 Upload_Record 且
    /// uploaded_chunk_index >= 0": a chunk was confirmed (`uploadedChunks > 0`) or
    /// a server session was opened (`sessionId` present). A record with neither is
    /// a Queued_Not_Started_File and is cleared instead (R-25.3).
    var isInFlight: Bool {
        if uploadedChunks > 0 { return true }
        if let session = sessionId, !session.isEmpty { return true }
        return false
    }

    /// Mark this record as an `AUTO_OFF` paused task and stamp the pause time.
    /// Mirrors Android `UploadRecordDao.markAutoOffPaused`.
    func markAutoOffPaused(at date: Date = Date()) {
        pauseSource = PauseSource.autoOff.rawValue
        pausedAt = date
        // Keep it resumable: park as pending so an explicit "继续" can pick it up
        // from the persisted breakpoint (R-27.1) without a background trigger
        // touching it (the pending fetch requests exclude AUTO_OFF).
        status = UploadStatus.pending.rawValue
        updatedAt = date
    }

    /// Clear the `AUTO_OFF` pause marker. Mirrors Android
    /// `UploadRecordDao.clearAutoOffPause`; called when the user taps "继续"
    /// (R-27.1) so the record resumes as a normal manual task.
    func clearAutoOffPause() {
        pauseSource = nil
        pausedAt = nil
        updatedAt = Date()
    }

    /// Upload progress as a percentage (0.0 - 1.0)
    var progress: Double {
        guard totalChunks > 0 else { return 0.0 }
        return Double(uploadedChunks) / Double(totalChunks)
    }

    /// Whether this record can be resumed (within 7-day window)
    var canResume: Bool {
        guard let updated = updatedAt ?? Optional(createdAt) else { return false }
        let sevenDaysAgo = Date().addingTimeInterval(-7 * 24 * 60 * 60)
        return updated > sevenDaysAgo && sessionId != nil
    }

    /// Create a new UploadRecord in the given context
    static func create(
        in context: NSManagedObjectContext,
        localFilePath: String,
        fileHash: String,
        fileSize: Int64,
        fileName: String,
        folder: BackupFolder? = nil,
        source: BackupSource = .auto
    ) -> UploadRecord {
        let record = UploadRecord(context: context)
        record.id = UUID()
        record.localFilePath = localFilePath
        record.fileHash = fileHash
        record.fileSize = fileSize
        record.fileName = fileName
        record.status = UploadStatus.pending.rawValue
        record.uploadedChunks = 0
        record.totalChunks = 0
        record.retryCount = 0
        record.createdAt = Date()
        record.source = source.rawValue
        record.backupFolder = folder
        return record
    }

    /// Fetch request for pending uploads queued by a specific source.
    ///
    /// `AUTO_OFF` paused records are always excluded (R-25.4 / R-30.3): they are
    /// pending only so an explicit "继续" can resume them, and must never be
    /// re-enqueued by an automatic trigger or counted as ordinary queued files
    /// (e.g. when clearing Queued_Not_Started_File on toggle-off).
    static func pendingFetchRequest(source: BackupSource) -> NSFetchRequest<UploadRecord> {
        let request = NSFetchRequest<UploadRecord>(entityName: "UploadRecord")
        // Treat a nil source as "auto" for legacy records.
        if source == .auto {
            request.predicate = NSPredicate(
                format: "status == %@ AND (source == %@ OR source == nil) AND (pauseSource == nil OR pauseSource != %@)",
                UploadStatus.pending.rawValue, BackupSource.auto.rawValue, PauseSource.autoOff.rawValue
            )
        } else {
            request.predicate = NSPredicate(
                format: "status == %@ AND source == %@ AND (pauseSource == nil OR pauseSource != %@)",
                UploadStatus.pending.rawValue, source.rawValue, PauseSource.autoOff.rawValue
            )
        }
        request.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: true)]
        return request
    }

    /// Fetch request for all upload records
    static func fetchRequest() -> NSFetchRequest<UploadRecord> {
        return NSFetchRequest<UploadRecord>(entityName: "UploadRecord")
    }

    /// Fetch request for pending uploads.
    ///
    /// Excludes `AUTO_OFF` paused records (R-25.4 / R-30.3) so automatic
    /// background uploads and the ordinary "排队中" queue never pick them up —
    /// those are surfaced separately via `autoOffPausedFetchRequest()` and only
    /// resume on an explicit "继续".
    static func pendingFetchRequest() -> NSFetchRequest<UploadRecord> {
        let request = NSFetchRequest<UploadRecord>(entityName: "UploadRecord")
        request.predicate = NSPredicate(
            format: "status == %@ AND (pauseSource == nil OR pauseSource != %@)",
            UploadStatus.pending.rawValue, PauseSource.autoOff.rawValue
        )
        request.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: true)]
        return request
    }

    /// Fetch request for `AUTO_OFF` paused records, newest pause first (R-26.1).
    /// These are the files preserved when the user disabled auto-backup mid-upload
    /// and are shown as "已暂停" tasks in the Tasks Tab.
    static func autoOffPausedFetchRequest() -> NSFetchRequest<UploadRecord> {
        let request = NSFetchRequest<UploadRecord>(entityName: "UploadRecord")
        request.predicate = NSPredicate(format: "pauseSource == %@", PauseSource.autoOff.rawValue)
        request.sortDescriptors = [NSSortDescriptor(key: "pausedAt", ascending: false)]
        return request
    }

    /// Fetch a single record by its PHAsset local identifier (used as the stable
    /// key for a paused task, mirroring Android's `getByFileUri`).
    static func fetchRequest(localFilePath: String) -> NSFetchRequest<UploadRecord> {
        let request = NSFetchRequest<UploadRecord>(entityName: "UploadRecord")
        request.predicate = NSPredicate(format: "localFilePath == %@", localFilePath)
        request.fetchLimit = 1
        return request
    }
}

// MARK: - BackupHistory NSManagedObject

/// Records completed backup operations for history tracking.
@objc(BackupHistory)
public class BackupHistory: NSManagedObject {
    @NSManaged public var id: UUID
    @NSManaged public var fileName: String
    @NSManaged public var fileSize: Int64
    @NSManaged public var status: String
    @NSManaged public var errorMessage: String?
    @NSManaged public var sourcePath: String
    @NSManaged public var remotePath: String?
    @NSManaged public var completedAt: Date
    @NSManaged public var durationSeconds: Double
}

// MARK: BackupHistory Convenience

extension BackupHistory {
    /// Result status as enum
    var resultStatus: BackupResultStatus {
        get { BackupResultStatus(rawValue: status) ?? .failed }
        set { status = newValue.rawValue }
    }

    /// Formatted duration string
    var formattedDuration: String {
        if durationSeconds < 1 {
            return "< 1秒"
        } else if durationSeconds < 60 {
            return "\(Int(durationSeconds))秒"
        } else {
            let minutes = Int(durationSeconds) / 60
            let seconds = Int(durationSeconds) % 60
            return "\(minutes)分\(seconds)秒"
        }
    }

    /// Create a new BackupHistory record
    static func create(
        in context: NSManagedObjectContext,
        fileName: String,
        fileSize: Int64,
        status: BackupResultStatus,
        sourcePath: String,
        remotePath: String? = nil,
        errorMessage: String? = nil,
        durationSeconds: Double = 0
    ) -> BackupHistory {
        let history = BackupHistory(context: context)
        history.id = UUID()
        history.fileName = fileName
        history.fileSize = fileSize
        history.status = status.rawValue
        history.sourcePath = sourcePath
        history.remotePath = remotePath
        history.errorMessage = errorMessage
        history.completedAt = Date()
        history.durationSeconds = durationSeconds
        return history
    }

    /// Fetch request for all history records, newest first
    static func fetchRequest() -> NSFetchRequest<BackupHistory> {
        let request = NSFetchRequest<BackupHistory>(entityName: "BackupHistory")
        request.sortDescriptors = [NSSortDescriptor(key: "completedAt", ascending: false)]
        return request
    }
}
