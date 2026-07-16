import CoreData
import Foundation

// MARK: - Persistence Controller

/// Manages the Core Data stack for PhotoVault.
/// Programmatically defines the data model since we cannot create binary .xcdatamodeld files.
class PersistenceController {
    // MARK: - Singleton

    static let shared = PersistenceController()

    // MARK: - Properties

    /// The main Core Data persistent container
    let container: NSPersistentContainer

    /// Main view context for UI operations
    var viewContext: NSManagedObjectContext {
        container.viewContext
    }

    // MARK: - Initialization

    /// Initialize with an optional in-memory flag for testing
    init(inMemory: Bool = false) {
        let model = Self.createManagedObjectModel()
        container = NSPersistentContainer(name: "PhotoVault", managedObjectModel: model)

        if inMemory {
            let description = NSPersistentStoreDescription()
            description.type = NSInMemoryStoreType
            container.persistentStoreDescriptions = [description]
        } else {
            // Enable automatic lightweight migration so additive, optional
            // attributes (e.g. `source`, `pauseSource`, `pausedAt` on
            // UploadRecord) are added to an existing on-disk store as NULL
            // without a hand-written mapping model. These options are true by
            // default, but we set them explicitly to guarantee older stores keep
            // loading after the programmatic model grows.
            for description in container.persistentStoreDescriptions {
                description.shouldMigrateStoreAutomatically = true
                description.shouldInferMappingModelAutomatically = true
            }
        }

        container.loadPersistentStores { _, error in
            if let error = error as NSError? {
                // In production, handle this more gracefully
                fatalError("Core Data store failed to load: \(error), \(error.userInfo)")
            }
        }

        container.viewContext.automaticallyMergesChangesFromParent = true
        container.viewContext.mergePolicy = NSMergeByPropertyObjectTrumpMergePolicy
    }

    // MARK: - Core Data Model Definition

    /// Programmatically creates the NSManagedObjectModel with all entity definitions
    static func createManagedObjectModel() -> NSManagedObjectModel {
        let model = NSManagedObjectModel()

        // Create entities
        let backupFolderEntity = createBackupFolderEntity()
        let uploadRecordEntity = createUploadRecordEntity()
        let backupHistoryEntity = createBackupHistoryEntity()

        // Set up relationships
        // BackupFolder -> UploadRecords (one-to-many)
        let folderToRecords = NSRelationshipDescription()
        folderToRecords.name = "uploadRecords"
        folderToRecords.destinationEntity = uploadRecordEntity
        folderToRecords.isOptional = true
        folderToRecords.deleteRule = .cascadeDeleteRule
        folderToRecords.maxCount = 0 // to-many

        let recordToFolder = NSRelationshipDescription()
        recordToFolder.name = "backupFolder"
        recordToFolder.destinationEntity = backupFolderEntity
        recordToFolder.isOptional = true
        recordToFolder.deleteRule = .nullifyDeleteRule
        recordToFolder.maxCount = 1 // to-one

        folderToRecords.inverseRelationship = recordToFolder
        recordToFolder.inverseRelationship = folderToRecords

        backupFolderEntity.properties.append(folderToRecords)
        uploadRecordEntity.properties.append(recordToFolder)

        model.entities = [backupFolderEntity, uploadRecordEntity, backupHistoryEntity]
        return model
    }

    // MARK: - Entity Definitions

    /// BackupFolder entity: represents a folder selected for backup
    private static func createBackupFolderEntity() -> NSEntityDescription {
        let entity = NSEntityDescription()
        entity.name = "BackupFolder"
        entity.managedObjectClassName = "BackupFolder"

        var properties: [NSAttributeDescription] = []

        // id - unique identifier
        let id = NSAttributeDescription()
        id.name = "id"
        id.attributeType = .UUIDAttributeType
        id.isOptional = false
        properties.append(id)

        // folderPath - local path of the source folder
        let folderPath = NSAttributeDescription()
        folderPath.name = "folderPath"
        folderPath.attributeType = .stringAttributeType
        folderPath.isOptional = false
        properties.append(folderPath)

        // folderName - display name of the folder
        let folderName = NSAttributeDescription()
        folderName.name = "folderName"
        folderName.attributeType = .stringAttributeType
        folderName.isOptional = false
        properties.append(folderName)

        // useCustomPath - whether to use a custom storage path
        let useCustomPath = NSAttributeDescription()
        useCustomPath.name = "useCustomPath"
        useCustomPath.attributeType = .booleanAttributeType
        useCustomPath.defaultValue = false
        properties.append(useCustomPath)

        // customPath - the custom storage path if useCustomPath is true
        let customPath = NSAttributeDescription()
        customPath.name = "customPath"
        customPath.attributeType = .stringAttributeType
        customPath.isOptional = true
        properties.append(customPath)

        // useYearMonthLayer - whether to organize by year/month
        let useYearMonthLayer = NSAttributeDescription()
        useYearMonthLayer.name = "useYearMonthLayer"
        useYearMonthLayer.attributeType = .booleanAttributeType
        useYearMonthLayer.defaultValue = false
        properties.append(useYearMonthLayer)

        // totalFiles - total number of files in this folder
        let totalFiles = NSAttributeDescription()
        totalFiles.name = "totalFiles"
        totalFiles.attributeType = .integer32AttributeType
        totalFiles.defaultValue = 0
        properties.append(totalFiles)

        // backedUpFiles - number of files already backed up
        let backedUpFiles = NSAttributeDescription()
        backedUpFiles.name = "backedUpFiles"
        backedUpFiles.attributeType = .integer32AttributeType
        backedUpFiles.defaultValue = 0
        properties.append(backedUpFiles)

        // lastScanTime - last time this folder was scanned
        let lastScanTime = NSAttributeDescription()
        lastScanTime.name = "lastScanTime"
        lastScanTime.attributeType = .dateAttributeType
        lastScanTime.isOptional = true
        properties.append(lastScanTime)

        // createdAt - when this folder was added
        let createdAt = NSAttributeDescription()
        createdAt.name = "createdAt"
        createdAt.attributeType = .dateAttributeType
        createdAt.isOptional = false
        properties.append(createdAt)

        entity.properties = properties
        return entity
    }

    /// UploadRecord entity: tracks individual file upload progress
    private static func createUploadRecordEntity() -> NSEntityDescription {
        let entity = NSEntityDescription()
        entity.name = "UploadRecord"
        entity.managedObjectClassName = "UploadRecord"

        var properties: [NSAttributeDescription] = []

        // id - unique identifier
        let id = NSAttributeDescription()
        id.name = "id"
        id.attributeType = .UUIDAttributeType
        id.isOptional = false
        properties.append(id)

        // localFilePath - path of the file on device
        let localFilePath = NSAttributeDescription()
        localFilePath.name = "localFilePath"
        localFilePath.attributeType = .stringAttributeType
        localFilePath.isOptional = false
        properties.append(localFilePath)

        // fileHash - SHA-256 hash of the file
        let fileHash = NSAttributeDescription()
        fileHash.name = "fileHash"
        fileHash.attributeType = .stringAttributeType
        fileHash.isOptional = false
        properties.append(fileHash)

        // fileSize - size in bytes
        let fileSize = NSAttributeDescription()
        fileSize.name = "fileSize"
        fileSize.attributeType = .integer64AttributeType
        fileSize.defaultValue = 0
        properties.append(fileSize)

        // fileName - original file name
        let fileName = NSAttributeDescription()
        fileName.name = "fileName"
        fileName.attributeType = .stringAttributeType
        fileName.isOptional = false
        properties.append(fileName)

        // status - upload status (pending, uploading, completed, failed)
        let status = NSAttributeDescription()
        status.name = "status"
        status.attributeType = .stringAttributeType
        status.defaultValue = "pending"
        properties.append(status)

        // uploadedChunks - number of chunks successfully uploaded
        let uploadedChunks = NSAttributeDescription()
        uploadedChunks.name = "uploadedChunks"
        uploadedChunks.attributeType = .integer32AttributeType
        uploadedChunks.defaultValue = 0
        properties.append(uploadedChunks)

        // totalChunks - total number of chunks for this file
        let totalChunks = NSAttributeDescription()
        totalChunks.name = "totalChunks"
        totalChunks.attributeType = .integer32AttributeType
        totalChunks.defaultValue = 0
        properties.append(totalChunks)

        // sessionId - server-side upload session identifier
        let sessionId = NSAttributeDescription()
        sessionId.name = "sessionId"
        sessionId.attributeType = .stringAttributeType
        sessionId.isOptional = true
        properties.append(sessionId)

        // retryCount - number of retry attempts
        let retryCount = NSAttributeDescription()
        retryCount.name = "retryCount"
        retryCount.attributeType = .integer16AttributeType
        retryCount.defaultValue = 0
        properties.append(retryCount)

        // errorMessage - last error message if failed
        let errorMessage = NSAttributeDescription()
        errorMessage.name = "errorMessage"
        errorMessage.attributeType = .stringAttributeType
        errorMessage.isOptional = true
        properties.append(errorMessage)

        // fileModifiedTime - last modification time of the source file
        let fileModifiedTime = NSAttributeDescription()
        fileModifiedTime.name = "fileModifiedTime"
        fileModifiedTime.attributeType = .dateAttributeType
        fileModifiedTime.isOptional = true
        properties.append(fileModifiedTime)

        // createdAt - when this record was created
        let createdAt = NSAttributeDescription()
        createdAt.name = "createdAt"
        createdAt.attributeType = .dateAttributeType
        createdAt.isOptional = false
        properties.append(createdAt)

        // updatedAt - last update time
        let updatedAt = NSAttributeDescription()
        updatedAt.name = "updatedAt"
        updatedAt.attributeType = .dateAttributeType
        updatedAt.isOptional = true
        properties.append(updatedAt)

        // source - how the file was queued ("auto" or "manual"). Optional with
        // a default of "auto" so previously persisted records decode cleanly.
        let source = NSAttributeDescription()
        source.name = "source"
        source.attributeType = .stringAttributeType
        source.isOptional = true
        source.defaultValue = "auto"
        properties.append(source)

        // pauseSource - why this resumable record is parked (USER/CONDITION/AUTO_OFF).
        // Only AUTO_OFF is persisted on iOS (R-25.2/R-29.3). Optional with no
        // default so existing stores migrate to NULL via lightweight migration.
        let pauseSource = NSAttributeDescription()
        pauseSource.name = "pauseSource"
        pauseSource.attributeType = .stringAttributeType
        pauseSource.isOptional = true
        properties.append(pauseSource)

        // pausedAt - when the record was parked (paired with pauseSource=AUTO_OFF),
        // used to sort the paused-task list newest-first (R-26.1). Optional/nil.
        let pausedAt = NSAttributeDescription()
        pausedAt.name = "pausedAt"
        pausedAt.attributeType = .dateAttributeType
        pausedAt.isOptional = true
        properties.append(pausedAt)

        entity.properties = properties
        return entity
    }

    /// BackupHistory entity: stores completed backup history records
    private static func createBackupHistoryEntity() -> NSEntityDescription {
        let entity = NSEntityDescription()
        entity.name = "BackupHistory"
        entity.managedObjectClassName = "BackupHistory"

        var properties: [NSAttributeDescription] = []

        // id - unique identifier
        let id = NSAttributeDescription()
        id.name = "id"
        id.attributeType = .UUIDAttributeType
        id.isOptional = false
        properties.append(id)

        // fileName - name of the backed up file
        let fileName = NSAttributeDescription()
        fileName.name = "fileName"
        fileName.attributeType = .stringAttributeType
        fileName.isOptional = false
        properties.append(fileName)

        // fileSize - size in bytes
        let fileSize = NSAttributeDescription()
        fileSize.name = "fileSize"
        fileSize.attributeType = .integer64AttributeType
        fileSize.defaultValue = 0
        properties.append(fileSize)

        // status - result status (success, failed, skipped)
        let status = NSAttributeDescription()
        status.name = "status"
        status.attributeType = .stringAttributeType
        status.isOptional = false
        properties.append(status)

        // errorMessage - error details if failed
        let errorMessage = NSAttributeDescription()
        errorMessage.name = "errorMessage"
        errorMessage.attributeType = .stringAttributeType
        errorMessage.isOptional = true
        properties.append(errorMessage)

        // sourcePath - original file path on device
        let sourcePath = NSAttributeDescription()
        sourcePath.name = "sourcePath"
        sourcePath.attributeType = .stringAttributeType
        sourcePath.isOptional = false
        properties.append(sourcePath)

        // remotePath - storage path on the server
        let remotePath = NSAttributeDescription()
        remotePath.name = "remotePath"
        remotePath.attributeType = .stringAttributeType
        remotePath.isOptional = true
        properties.append(remotePath)

        // completedAt - when the backup completed
        let completedAt = NSAttributeDescription()
        completedAt.name = "completedAt"
        completedAt.attributeType = .dateAttributeType
        completedAt.isOptional = false
        properties.append(completedAt)

        // durationSeconds - how long the upload took
        let durationSeconds = NSAttributeDescription()
        durationSeconds.name = "durationSeconds"
        durationSeconds.attributeType = .doubleAttributeType
        durationSeconds.defaultValue = 0.0
        properties.append(durationSeconds)

        entity.properties = properties
        return entity
    }

    // MARK: - Helper Methods

    /// Create a new background context for performing work off the main thread
    func newBackgroundContext() -> NSManagedObjectContext {
        return container.newBackgroundContext()
    }

    /// Save the view context if there are changes
    func save() {
        let context = viewContext
        if context.hasChanges {
            do {
                try context.save()
            } catch {
                let nsError = error as NSError
                print("Core Data save error: \(nsError), \(nsError.userInfo)")
            }
        }
    }

    /// Save a given context if there are changes
    func save(context: NSManagedObjectContext) {
        if context.hasChanges {
            do {
                try context.save()
            } catch {
                let nsError = error as NSError
                print("Core Data save error: \(nsError), \(nsError.userInfo)")
            }
        }
    }
}
