import Foundation
import UIKit
import Combine

// MARK: - Backup Condition State

/// Represents the current backup condition status
struct BackupConditionState: Equatable {
    let batteryLevel: Float
    let isOnWiFi: Bool
    let canBackup: Bool
    let pauseReason: PauseReason?
}

/// Reason why backup is paused
enum PauseReason: String, Equatable {
    case lowBattery = "电量不足"
    case noWiFi = "未连接WiFi"
    case lowBatteryAndNoWiFi = "电量不足且未连接WiFi"

    var resumeHint: String {
        switch self {
        case .lowBattery:
            return "将在电量恢复至55%以上时自动恢复"
        case .noWiFi:
            return "将在WiFi连接后自动恢复"
        case .lowBatteryAndNoWiFi:
            return "将在电量恢复且WiFi连接后自动恢复"
        }
    }
}

// MARK: - Backup Condition Checker

/// Monitors battery level and WiFi connectivity to determine if backup conditions are met.
/// Battery thresholds: pause at ≤50%, resume at ≥55% (hysteresis to prevent rapid toggling).
/// WiFi requirement: must be connected via WiFi (from ConnectionManager).
class BackupConditionChecker: ObservableObject {
    // MARK: - Published State

    @Published private(set) var conditionState: BackupConditionState
    @Published private(set) var batteryLevel: Float = 1.0
    @Published private(set) var isCharging: Bool = false

    // MARK: - Thresholds

    /// Battery level at or below which backup should pause
    static let pauseThreshold: Float = 0.50
    /// Battery level at or above which backup can resume
    static let resumeThreshold: Float = 0.55

    // MARK: - Properties

    private var isMonitoring = false
    private var cancellables = Set<AnyCancellable>()
    private var wasPreviouslyPaused = false

    /// Callback when conditions change (canBackup transitions)
    var onConditionChanged: ((Bool) -> Void)?

    // MARK: - Singleton

    static let shared = BackupConditionChecker()

    // MARK: - Initialization

    init() {
        let initialBattery = UIDevice.current.batteryLevel >= 0 ? UIDevice.current.batteryLevel : 1.0
        let initialWiFi = ConnectionManager.shared.isOnWiFi
        let canBackup = initialBattery > Self.pauseThreshold && initialWiFi

        self.conditionState = BackupConditionState(
            batteryLevel: initialBattery,
            isOnWiFi: initialWiFi,
            canBackup: canBackup,
            pauseReason: canBackup ? nil : Self.determinePauseReason(battery: initialBattery, wifi: initialWiFi)
        )
    }

    // MARK: - Public Methods

    /// Check if backup conditions are currently met.
    /// Battery must be > 50% (or ≥ 55% if previously paused) AND WiFi must be connected.
    /// - Returns: true if conditions allow backup
    func canBackup() -> Bool {
        let battery = UIDevice.current.batteryLevel >= 0 ? UIDevice.current.batteryLevel : 1.0
        let wifi = ConnectionManager.shared.isOnWiFi

        // Apply hysteresis: if previously paused, require 55% to resume
        let batteryOK: Bool
        if wasPreviouslyPaused {
            batteryOK = battery >= Self.resumeThreshold
        } else {
            batteryOK = battery > Self.pauseThreshold
        }

        return batteryOK && wifi
    }

    /// Start monitoring battery and WiFi changes.
    /// Publishes condition changes for UI display.
    func startMonitoring() {
        guard !isMonitoring else { return }
        isMonitoring = true

        // Enable battery monitoring
        UIDevice.current.isBatteryMonitoringEnabled = true
        batteryLevel = UIDevice.current.batteryLevel >= 0 ? UIDevice.current.batteryLevel : 1.0
        isCharging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full

        // Observe battery level changes
        NotificationCenter.default.publisher(for: UIDevice.batteryLevelDidChangeNotification)
            .sink { [weak self] _ in
                self?.updateBatteryState()
            }
            .store(in: &cancellables)

        // Observe battery state changes (charging/unplugging)
        NotificationCenter.default.publisher(for: UIDevice.batteryStateDidChangeNotification)
            .sink { [weak self] _ in
                self?.updateBatteryState()
            }
            .store(in: &cancellables)

        // Observe WiFi changes via ConnectionManager
        ConnectionManager.shared.$isOnWiFi
            .removeDuplicates()
            .sink { [weak self] _ in
                self?.updateConditionState()
            }
            .store(in: &cancellables)

        // Initial state update
        updateConditionState()

        print("[BackupConditionChecker] Started monitoring backup conditions")
    }

    /// Stop monitoring battery and WiFi changes.
    func stopMonitoring() {
        guard isMonitoring else { return }
        isMonitoring = false
        cancellables.removeAll()
        UIDevice.current.isBatteryMonitoringEnabled = false
        print("[BackupConditionChecker] Stopped monitoring backup conditions")
    }

    /// Get the current pause reason, if any.
    /// - Returns: PauseReason or nil if conditions are met
    func currentPauseReason() -> PauseReason? {
        let battery = UIDevice.current.batteryLevel >= 0 ? UIDevice.current.batteryLevel : 1.0
        let wifi = ConnectionManager.shared.isOnWiFi
        return Self.determinePauseReason(battery: battery, wifi: wifi)
    }

    // MARK: - Private Methods

    private func updateBatteryState() {
        let level = UIDevice.current.batteryLevel >= 0 ? UIDevice.current.batteryLevel : 1.0
        let charging = UIDevice.current.batteryState == .charging || UIDevice.current.batteryState == .full

        DispatchQueue.main.async { [weak self] in
            self?.batteryLevel = level
            self?.isCharging = charging
        }

        updateConditionState()
    }

    private func updateConditionState() {
        let battery = UIDevice.current.batteryLevel >= 0 ? UIDevice.current.batteryLevel : 1.0
        let wifi = ConnectionManager.shared.isOnWiFi

        // Apply hysteresis for battery threshold
        let batteryOK: Bool
        if wasPreviouslyPaused {
            batteryOK = battery >= Self.resumeThreshold
        } else {
            batteryOK = battery > Self.pauseThreshold
        }

        let canBackupNow = batteryOK && wifi
        let pauseReason = canBackupNow ? nil : Self.determinePauseReason(battery: battery, wifi: wifi)

        // Track hysteresis state
        if !canBackupNow && battery <= Self.pauseThreshold {
            wasPreviouslyPaused = true
        } else if canBackupNow {
            wasPreviouslyPaused = false
        }

        let newState = BackupConditionState(
            batteryLevel: battery,
            isOnWiFi: wifi,
            canBackup: canBackupNow,
            pauseReason: pauseReason
        )

        let previousCanBackup = conditionState.canBackup

        DispatchQueue.main.async { [weak self] in
            self?.conditionState = newState
        }

        // Notify on transition
        if canBackupNow != previousCanBackup {
            onConditionChanged?(canBackupNow)
            print("[BackupConditionChecker] Conditions changed: canBackup=\(canBackupNow), battery=\(battery), wifi=\(wifi)")
        }
    }

    /// Determine the pause reason based on current battery and WiFi state
    private static func determinePauseReason(battery: Float, wifi: Bool) -> PauseReason? {
        let batteryLow = battery <= pauseThreshold
        let noWiFi = !wifi

        if batteryLow && noWiFi {
            return .lowBatteryAndNoWiFi
        } else if batteryLow {
            return .lowBattery
        } else if noWiFi {
            return .noWiFi
        }
        return nil
    }
}
