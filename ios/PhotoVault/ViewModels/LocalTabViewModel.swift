import Foundation
import CoreData
import Combine

// MARK: - Backup Status

/// Represents the backup status of a folder
enum FolderBackupStatus {
    case allBackedUp    // ✓ 全部已备份
    case backingUp      // ⟳ 备份中
    case pending        // ⚠ 有待备份

    var icon: String {
        switch self {
        case .allBackedUp: return "checkmark.circle.fill"
        case .backingUp: return "arrow.triangle.2.circlepath"
        case .pending: return "exclamationmark.triangle.fill"
        }
    }

    var color: String {
        switch self {
        case .allBackedUp: return "green"
        case .backingUp: return "blue"
        case .pending: return "orange"
        }
    }

    var label: String {
        switch self {
        case .allBackedUp: return "已完成"
        case .backingUp: return "备份中"
        case .pending: return "待备份"
        }
    }
}

// MARK: - Local Tab View Model

/// ViewModel for the Local Tab, manages backup folders from Core Data.
@MainActor
class LocalTabViewModel: ObservableObject {
    // MARK: - Published Properties

    @Published var folders: [BackupFolder] = []
    @Published var isLoading: Bool = false
    @Published var errorMessage: String? = nil

    // MARK: - Dependencies

    private let persistenceController: PersistenceController
    private var viewContext: NSManagedObjectContext {
        persistenceController.viewContext
    }

    // MARK: - Initialization

    init(persistenceController: PersistenceController = .shared) {
        self.persistenceController = persistenceController
        fetchFolders()
    }

    // MARK: - Public Methods

    /// Fetch all backup folders from Core Data
    func fetchFolders() {
        let request = BackupFolder.fetchRequest()
        request.sortDescriptors = [NSSortDescriptor(key: "createdAt", ascending: false)]

        do {
            folders = try viewContext.fetch(request)
        } catch {
            errorMessage = "加载文件夹失败: \(error.localizedDescription)"
        }
    }

    /// Add a new backup folder
    func addFolder(path: String, name: String) {
        let _ = BackupFolder.create(
            in: viewContext,
            folderPath: path,
            folderName: name
        )
        persistenceController.save()
        fetchFolders()
    }

    /// Remove a backup folder
    func removeFolder(_ folder: BackupFolder) {
        viewContext.delete(folder)
        persistenceController.save()
        fetchFolders()
    }

    /// Update storage policy for a folder
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
        fetchFolders()
    }

    /// Refresh / rescan folders (pull to refresh)
    func refresh() async {
        isLoading = true
        // Simulate scan delay; in production this triggers a real file system scan
        try? await Task.sleep(nanoseconds: 500_000_000)
        fetchFolders()
        isLoading = false
    }

    /// Get the backup status for a folder
    func backupStatus(for folder: BackupFolder) -> FolderBackupStatus {
        if folder.totalFiles == 0 {
            return .pending
        } else if folder.isFullyBackedUp {
            return .allBackedUp
        } else {
            return .pending
        }
    }

    /// Calculate backup progress percentage (0–100)
    func progressPercentage(for folder: BackupFolder) -> Int {
        guard folder.totalFiles > 0 else { return 0 }
        return Int((Double(folder.backedUpFiles) / Double(folder.totalFiles)) * 100)
    }
}
