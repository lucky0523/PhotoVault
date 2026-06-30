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
    private var cancellables = Set<AnyCancellable>()

    // MARK: - UserDefaults Keys

    private let batteryLevelKey = "com.photovault.settings.minimumBatteryLevel"
    private let scanIntervalKey = "com.photovault.settings.scanInterval"
    private let savedServerAddressKey = "com.photovault.savedServerAddress"
    private let savedUsernameKey = "com.photovault.savedUsername"
    private let savedPasswordKey = "com.photovault.savedPassword"
    private let savedRememberPasswordKey = "com.photovault.savedRememberPassword"

    // MARK: - Initialization

    init(
        tokenManager: TokenManager = .shared,
        connectionManager: ConnectionManager = .shared,
        persistenceController: PersistenceController = .shared,
        keychain: KeychainManager = .shared
    ) {
        self.tokenManager = tokenManager
        self.connectionManager = connectionManager
        self.persistenceController = persistenceController
        self.keychain = keychain

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
}
