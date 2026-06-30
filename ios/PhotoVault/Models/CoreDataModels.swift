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
    @NSManaged public var backupFolder: BackupFolder?
}

// MARK: UploadRecord Convenience

extension UploadRecord {
    /// Current upload status as enum
    var uploadStatus: UploadStatus {
        get { UploadStatus(rawValue: status) ?? .pending }
        set { status = newValue.rawValue }
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
        folder: BackupFolder? = nil
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
        record.backupFolder = folder
        return record
    }

    /// Fetch request for all upload records
    static func fetchRequest() -> NSFetchRequest<UploadRecord> {
        return NSFetchRequest<UploadRecord>(entityName: "UploadRecord")
    }

    /// Fetch request for pending uploads
    static func pendingFetchRequest() -> NSFetchRequest<UploadRecord> {
        let request = NSFetchRequest<UploadRecord>(entityName: "UploadRecord")
        request.predicate = NSPredicate(format: "status == %@", UploadStatus.pending.rawValue)
        request.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: true)]
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
