import Foundation
import Security

// MARK: - Keychain Error

/// Errors that can occur during Keychain operations.
enum KeychainError: Error, LocalizedError {
    case duplicateItem
    case itemNotFound
    case unexpectedStatus(OSStatus)
    case encodingError
    case decodingError

    var errorDescription: String? {
        switch self {
        case .duplicateItem:
            return "Keychain item already exists"
        case .itemNotFound:
            return "Keychain item not found"
        case .unexpectedStatus(let status):
            return "Keychain error: \(status)"
        case .encodingError:
            return "Failed to encode data for Keychain"
        case .decodingError:
            return "Failed to decode data from Keychain"
        }
    }
}

// MARK: - Keychain Manager

/// A wrapper around the iOS Security framework for secure credential storage.
/// Uses the system Keychain to persist sensitive data like tokens and passwords.
class KeychainManager {

    // MARK: - Properties

    /// The service identifier used for all Keychain items.
    static let serviceIdentifier = "com.photovault.ios"

    /// Shared singleton instance.
    static let shared = KeychainManager()

    private init() {}

    // MARK: - Public Methods

    /// Save a string value to the Keychain.
    /// - Parameters:
    ///   - key: The key to identify the item.
    ///   - value: The string value to store.
    /// - Throws: KeychainError if the operation fails.
    func save(key: String, value: String) throws {
        guard let data = value.data(using: .utf8) else {
            throw KeychainError.encodingError
        }

        // Build query to check if item already exists
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: KeychainManager.serviceIdentifier,
            kSecAttrAccount as String: key
        ]

        // Check if item exists
        let status = SecItemCopyMatching(query as CFDictionary, nil)

        if status == errSecSuccess {
            // Item exists — update it
            let attributesToUpdate: [String: Any] = [
                kSecValueData as String: data,
                kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock
            ]
            let updateStatus = SecItemUpdate(query as CFDictionary, attributesToUpdate as CFDictionary)
            guard updateStatus == errSecSuccess else {
                throw KeychainError.unexpectedStatus(updateStatus)
            }
        } else if status == errSecItemNotFound {
            // Item does not exist — add it
            var newItem = query
            newItem[kSecValueData as String] = data
            newItem[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock

            let addStatus = SecItemAdd(newItem as CFDictionary, nil)
            guard addStatus == errSecSuccess else {
                throw KeychainError.unexpectedStatus(addStatus)
            }
        } else {
            throw KeychainError.unexpectedStatus(status)
        }
    }

    /// Load a string value from the Keychain.
    /// - Parameter key: The key identifying the item.
    /// - Returns: The stored string value, or nil if not found.
    func load(key: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: KeychainManager.serviceIdentifier,
            kSecAttrAccount as String: key,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne
        ]

        var result: AnyObject?
        let status = SecItemCopyMatching(query as CFDictionary, &result)

        guard status == errSecSuccess, let data = result as? Data else {
            return nil
        }

        return String(data: data, encoding: .utf8)
    }

    /// Delete a value from the Keychain.
    /// - Parameter key: The key identifying the item to delete.
    /// - Throws: KeychainError if the operation fails (item not found is silently ignored).
    func delete(key: String) {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: KeychainManager.serviceIdentifier,
            kSecAttrAccount as String: key
        ]

        SecItemDelete(query as CFDictionary)
    }
}
