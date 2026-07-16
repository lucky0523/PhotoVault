import Foundation

// MARK: - Backup Preferences

/// Centralized access to persisted backup preferences.
///
/// Mirrors the Android `SettingsPreferences.autoBackupEnabled` flag. Kept in a
/// single place so that both the Settings screen (which the user toggles) and
/// the automatic triggers (background scan / upload) read the exact same value.
enum BackupPreferences {
    /// UserDefaults key for the "自动备份" switch.
    static let autoBackupEnabledKey = "com.photovault.settings.autoBackupEnabled"

    /// Whether automatic backup is enabled. Defaults to `true` when unset,
    /// matching the Android default (auto-backup on).
    static var autoBackupEnabled: Bool {
        get {
            // A missing key must read as the default (true), not false.
            if UserDefaults.standard.object(forKey: autoBackupEnabledKey) == nil {
                return true
            }
            return UserDefaults.standard.bool(forKey: autoBackupEnabledKey)
        }
        set {
            UserDefaults.standard.set(newValue, forKey: autoBackupEnabledKey)
        }
    }
}
